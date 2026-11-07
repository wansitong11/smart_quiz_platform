package interview.guide.modules.quiz.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.quiz.model.QuizCatalogDTO;
import interview.guide.modules.quiz.model.QuizSubjectConfig;
import interview.guide.modules.quiz.model.QuizSubjectEntity;
import interview.guide.modules.quiz.model.QuizSubjectRequest;
import interview.guide.modules.quiz.repository.QuizSubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 自定义测验科目的增删改查。只允许操作本人科目，软删除保历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizSubjectService {

  private static final Set<String> VALID_TYPES = Set.of(
      "SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE", "FILL_BLANK", "SHORT_ANSWER",
      "READING", "WRITING", "CASE_ANALYSIS", "OTHER");

  private final QuizSubjectRepository repository;
  private final InterviewSkillService skillService;
  private final OwnerScope ownerScope;
  private final ObjectMapper objectMapper;

  public List<QuizCatalogDTO.Subject> listMine() {
    Long ownerId = ownerScope.currentUserId();
    return repository.findByOwnerIdAndDeletedAtIsNullOrderByUpdatedAtDesc(ownerId).stream()
        .map(this::toSubject)
        .filter(subject -> subject != null)
        .toList();
  }

  public QuizCatalogDTO.Subject get(String subjectCode) {
    Long ownerId = ownerScope.currentUserId();
    return repository.findByOwnerIdAndSubjectCodeAndDeletedAtIsNull(ownerId, subjectCode)
        .map(this::toSubject)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "自定义科目不存在"));
  }

  /**
   * 按显式 owner 查询自定义科目，供异步批改回写使用（异步线程没有登录上下文）。
   */
  public QuizCatalogDTO.Subject getForOwner(Long ownerId, String subjectCode) {
    if (ownerId == null || subjectCode == null || subjectCode.isBlank()) {
      return null;
    }
    return repository.findByOwnerIdAndSubjectCodeAndDeletedAtIsNull(ownerId, subjectCode)
        .map(this::toSubject)
        .orElse(null);
  }

  @Transactional
  public QuizCatalogDTO.Subject create(QuizSubjectRequest request) {
    Long ownerId = ownerScope.currentUserId();
    validateExamCode(request.examCode());
    QuizSubjectConfig config = toConfig(request, null);

    QuizSubjectEntity entity = new QuizSubjectEntity();
    entity.setSubjectCode("custom-" + shortUuid());
    entity.setOwnerId(ownerId);
    entity.setExamCode(request.examCode().trim());
    entity.setName(request.name().trim());
    entity.setConfigJson(writeConfig(config));
    entity = repository.save(entity);
    log.info("已创建自定义科目: ownerId={}, subjectCode={}, name={}",
        ownerId, entity.getSubjectCode(), entity.getName());
    return toSubject(entity);
  }

  @Transactional
  public QuizCatalogDTO.Subject update(String subjectCode, QuizSubjectRequest request) {
    Long ownerId = ownerScope.currentUserId();
    validateExamCode(request.examCode());
    QuizSubjectEntity entity = requireOwned(subjectCode);
    QuizSubjectConfig previous = readConfig(entity);
    String clonedFromCode = previous == null ? null : previous.clonedFromCode();
    entity.setName(request.name().trim());
    entity.setExamCode(request.examCode().trim());
    entity.setConfigJson(writeConfig(toConfig(request, clonedFromCode)));
    entity = repository.save(entity);
    log.info("已更新自定义科目: ownerId={}, subjectCode={}", ownerId, subjectCode);
    return toSubject(entity);
  }

  @Transactional
  public void delete(String subjectCode) {
    QuizSubjectEntity entity = requireOwned(subjectCode);
    entity.setDeletedAt(LocalDateTime.now());
    repository.save(entity);
    log.info("已软删除自定义科目: subjectCode={}", subjectCode);
  }

  @Transactional
  public QuizCatalogDTO.Subject clone(String sourceCode) {
    InterviewSkillService.SubjectDefinitionDTO source =
        skillService.getSubjectDefinition(sourceCode);
    if (source == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "内置科目不存在或已下线");
    }

    List<QuizSubjectRequest.KnowledgePointInput> points = source.categories().stream()
        .filter(category -> category.key() != null && category.label() != null)
        .map(category -> new QuizSubjectRequest.KnowledgePointInput(category.label()))
        .toList();
    List<QuizSubjectRequest.QuestionTypeInput> types = source.questionTypes().stream()
        .map(type -> new QuizSubjectRequest.QuestionTypeInput(
            type.type(), type.defaultScore(), type.weight()))
        .toList();
    QuizSubjectRequest request = new QuizSubjectRequest(
        source.name() + "（我的）",
        source.description(),
        source.examCode(),
        points.isEmpty() ? List.of(new QuizSubjectRequest.KnowledgePointInput("未分组")) : points,
        types.isEmpty()
            ? List.of(new QuizSubjectRequest.QuestionTypeInput("SHORT_ANSWER", 5, 1)) : types,
        source.suggestedMinutes(),
        null);

    Long ownerId = ownerScope.currentUserId();
    QuizSubjectEntity entity = new QuizSubjectEntity();
    entity.setSubjectCode("custom-" + shortUuid());
    entity.setOwnerId(ownerId);
    entity.setExamCode(request.examCode().trim());
    entity.setName(request.name().trim());
    entity.setConfigJson(writeConfig(toConfig(request, sourceCode)));
    entity = repository.save(entity);
    log.info("已复制内置科目: source={}, subjectCode={}", sourceCode, entity.getSubjectCode());
    return toSubject(entity);
  }

  private QuizSubjectEntity requireOwned(String subjectCode) {
    Long ownerId = ownerScope.currentUserId();
    return repository.findByOwnerIdAndSubjectCodeAndDeletedAtIsNull(ownerId, subjectCode)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "自定义科目不存在"));
  }

  private void validateExamCode(String examCode) {
    if (examCode == null || examCode.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "所属考试不能为空");
    }
    boolean exists = skillService.listExams().stream()
        .anyMatch(exam -> exam.code().equals(examCode.trim()));
    if (!exists) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "所属考试不存在");
    }
  }

  private QuizSubjectConfig toConfig(QuizSubjectRequest request, String clonedFromCode) {
    List<QuizSubjectConfig.KnowledgePoint> points = new ArrayList<>();
    int index = 1;
    for (QuizSubjectRequest.KnowledgePointInput input : request.knowledgePoints()) {
      points.add(new QuizSubjectConfig.KnowledgePoint("kp" + index++, input.label().trim()));
    }

    List<QuizSubjectConfig.QuestionType> types = request.questionTypes().stream()
        .map(input -> toQuestionType(input))
        .toList();
    return new QuizSubjectConfig(
        trimToNull(request.description()),
        points,
        types,
        request.suggestedMinutes(),
        trimToNull(request.outlineText()),
        clonedFromCode);
  }

  private QuizSubjectConfig.QuestionType toQuestionType(QuizSubjectRequest.QuestionTypeInput input) {
    String type = input.type() == null ? "" : input.type().trim().toUpperCase(Locale.ROOT);
    if (!VALID_TYPES.contains(type)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的题型：" + input.type());
    }
    int score = input.defaultScore() == null ? 0 : input.defaultScore();
    int weight = input.weight() == null ? 1 : input.weight();
    if (score <= 0 || weight <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题型默认分和权重必须大于 0");
    }
    return new QuizSubjectConfig.QuestionType(type, score, weight);
  }

  private QuizCatalogDTO.Subject toSubject(QuizSubjectEntity entity) {
    QuizSubjectConfig config = readConfig(entity);
    if (config == null) {
      return null;
    }
    List<QuizCatalogDTO.KnowledgePoint> points = config.knowledgePoints() == null
        ? List.of()
        : config.knowledgePoints().stream()
            .map(point -> new QuizCatalogDTO.KnowledgePoint(point.code(), point.label()))
            .toList();
    List<QuizCatalogDTO.QuestionType> types = config.questionTypes() == null
        ? List.of()
        : config.questionTypes().stream()
            .map(type -> new QuizCatalogDTO.QuestionType(
                type.type(), type.defaultScore(), type.weight()))
            .toList();
    return new QuizCatalogDTO.Subject(
        entity.getSubjectCode(),
        entity.getName(),
        config.description(),
        entity.getExamCode(),
        true,
        points,
        types,
        config.suggestedMinutes(),
        config.clonedFromCode(),
        config.outlineText());
  }

  private String writeConfig(QuizSubjectConfig config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存科目配置失败");
    }
  }

  private QuizSubjectConfig readConfig(QuizSubjectEntity entity) {
    try {
      return objectMapper.readValue(entity.getConfigJson(), QuizSubjectConfig.class);
    } catch (Exception e) {
      log.warn("自定义科目配置损坏，已跳过: subjectCode={}", entity.getSubjectCode());
      return null;
    }
  }

  private String shortUuid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private String trimToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
