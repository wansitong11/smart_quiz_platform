package interview.guide.modules.quiz.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.PaperNode;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeAnswerEntity;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.quiz.config.QuizProperties;
import interview.guide.modules.quiz.model.QuizSubjectSpec;
import interview.guide.modules.quiz.model.WrongQuestionAttemptEventEntity;
import interview.guide.modules.quiz.model.WrongQuestionBackfillEntity;
import interview.guide.modules.quiz.model.WrongQuestionDTO;
import interview.guide.modules.quiz.model.WrongQuestionEntity;
import interview.guide.modules.quiz.model.WrongQuestionFilter;
import interview.guide.modules.quiz.model.WrongQuestionPageDTO;
import interview.guide.modules.quiz.model.WrongQuestionRetryMode;
import interview.guide.modules.quiz.model.WrongQuestionRetryRequest;
import interview.guide.modules.quiz.model.WrongQuestionSnapshot;
import interview.guide.modules.quiz.model.WrongQuestionStatus;
import interview.guide.modules.quiz.repository.WrongQuestionAttemptEventRepository;
import interview.guide.modules.quiz.repository.WrongQuestionBackfillRepository;
import interview.guide.modules.quiz.repository.WrongQuestionRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 错题本核心服务：列表、历史回填、批改回写、状态流转与重练。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WrongQuestionBookService {

  private static final int MASTERED_CORRECT_COUNT = 3;
  private static final Set<String> OBJECTIVE_TYPES = Set.of(
      InterviewQuestionDTO.TYPE_SINGLE_CHOICE,
      InterviewQuestionDTO.TYPE_MULTI_CHOICE,
      InterviewQuestionDTO.TYPE_TRUE_FALSE,
      InterviewQuestionDTO.TYPE_FILL_BLANK);

  private final WrongQuestionRepository repository;
  private final WrongQuestionBackfillRepository backfillRepository;
  private final WrongQuestionAttemptEventRepository attemptEventRepository;
  private final InterviewPersistenceService persistenceService;
  private final InterviewSessionService sessionService;
  private final QuizCatalogService catalogService;
  private final QuizProperties properties;
  private final OwnerScope ownerScope;
  private final ObjectMapper objectMapper;

  @Transactional
  public WrongQuestionPageDTO list(WrongQuestionFilter filter, int page, int size) {
    Long ownerId = ownerScope.effectiveOwnerId();
    if (ownerId != null) {
      backfillIfNeeded(ownerId);
    }

    int safePage = Math.max(0, page);
    int safeSize = Math.min(100, Math.max(1, size));
    Specification<WrongQuestionEntity> spec = buildSpec(ownerId, filter);
    PageRequest pageRequest = PageRequest.of(safePage, safeSize,
        Sort.by(Sort.Order.desc("lastWrongAt"), Sort.Order.desc("updatedAt")));
    Page<WrongQuestionEntity> result = repository.findAll(spec, pageRequest);

    List<WrongQuestionPageDTO.CountItem> statusCounts = ownerId == null
        ? List.of()
        : countStatuses(ownerId, filter);
    List<WrongQuestionPageDTO.CountItem> subjectCounts = ownerId == null
        ? List.of()
        : toSubjectCounts(repository.countBySubject(ownerId));

    return new WrongQuestionPageDTO(
        result.getContent().stream().map(this::toDto).toList(),
        result.getTotalElements(),
        safePage,
        safeSize,
        statusCounts,
        subjectCounts);
  }

  @Transactional
  public void updateStatus(String questionKey, WrongQuestionStatus status) {
    if (status == WrongQuestionStatus.MASTERED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "掌握状态由答对次数自动产生");
    }
    Long ownerId = ownerScope.currentUserId();
    WrongQuestionEntity entity = repository.findByOwnerIdAndQuestionKey(ownerId, questionKey)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "错题不存在"));
    if (entity.getStatus() == status) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    if (status == WrongQuestionStatus.ARCHIVED) {
      entity.archive(now);
    } else {
      entity.restore();
    }
    repository.save(entity);
  }

  /**
   * 批改完成后回写。异步消费者调用，因此按 session.ownerId 而不是当前登录态取归属。
   */
  @Transactional
  public void recordAttemptResults(String sessionId) {
    PracticeSessionEntity session = persistenceService.findBySessionIdForUpdate(sessionId)
        .orElse(null);
    if (session == null || !isQuizSource(session.getSourceType())) {
      return;
    }
    Long ownerId = session.getOwnerId();
    if (ownerId == null) {
      log.warn("跳过错题回写：会话没有归属用户, sessionId={}", sessionId);
      return;
    }
    PaperTree tree = persistenceService.findPaperTree(sessionId).orElse(null);
    if (tree == null) {
      return;
    }
    Map<Integer, InterviewQuestionDTO> questions = tree.flattenQuestions().stream()
        .collect(Collectors.toMap(InterviewQuestionDTO::questionIndex, question -> question,
            (left, right) -> left, LinkedHashMap::new));
    List<PracticeAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
    for (PracticeAnswerEntity answer : answers) {
      if (attemptEventRepository.existsBySessionIdAndQuestionIndex(
          sessionId, answer.getQuestionIndex())) {
        continue;
      }
      InterviewQuestionDTO question = questions.get(answer.getQuestionIndex());
      if (question == null || answer.getScore() == null
          || "MANUAL_REVIEW".equals(answer.getScoreStatus())) {
        continue;
      }
      WrongQuestionSnapshot snapshot = buildSnapshot(
          tree, question, session.getResumeId());
      String questionKey = fingerprint(question, snapshot, session.getSkillId());
      LocalDateTime answeredAt = answer.getAnsweredAt() == null
          ? LocalDateTime.now() : answer.getAnsweredAt();
      WrongQuestionEntity entity = repository.findForUpdate(ownerId, questionKey).orElse(null);
      boolean correct = isCorrect(question.questionType(), answer.getScore());

      if (correct && entity == null) {
        saveAttemptEvent(ownerId, sessionId, answer.getQuestionIndex());
        continue;
      }
      if (correct && entity.getStatus() == WrongQuestionStatus.ARCHIVED) {
        saveAttemptEvent(ownerId, sessionId, answer.getQuestionIndex());
        continue;
      }
      if (entity == null) {
        entity = newEntity(ownerId, session.getSkillId(), question, snapshot);
      }
      WrongQuestionSnapshot previousSnapshot = readSnapshot(entity.getQuestionSnapshot());
      WrongQuestionSnapshot persistedSnapshot = previousSnapshot == null
          ? snapshot : snapshot.withPaperId(previousSnapshot.paperId());
      if (correct) {
        entity.recordCorrect(answeredAt);
      } else {
        entity.recordWrong(answeredAt);
      }
      entity.setLastScoreRate(answer.getScore());
      entity.setLastUserAnswer(answer.getUserAnswer());
      entity.setQuestionSnapshot(writeSnapshot(persistedSnapshot));
      repository.save(entity);
      saveAttemptEvent(ownerId, sessionId, answer.getQuestionIndex());
    }
  }

  @Transactional
  public InterviewSessionDTO retry(WrongQuestionRetryRequest request) {
    WrongQuestionRetryMode mode = request.mode() == null
        ? WrongQuestionRetryMode.SELECTED : request.mode();
    Long ownerId = ownerScope.currentUserId();
    List<WrongQuestionEntity> entities = mode == WrongQuestionRetryMode.RANDOM
        ? randomCandidates(ownerId, request)
        : selectedCandidates(ownerId, request.questionKeys());
    if (entities.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前筛选下没有可重练的错题");
    }
    int max = Math.max(1, properties.getWrongBookMaxRetryQuestions());
    if (entities.size() > max) {
      entities = entities.subList(0, max);
    }
    return createRetrySession(entities);
  }

  private List<WrongQuestionEntity> selectedCandidates(Long ownerId, List<String> questionKeys) {
    if (questionKeys == null || questionKeys.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请先选择要重练的错题");
    }
    List<String> keys = questionKeys.stream()
        .filter(Objects::nonNull)
        .filter(key -> !key.isBlank())
        .distinct()
        .toList();
    List<WrongQuestionEntity> entities = repository.findByOwnerIdAndQuestionKeyIn(ownerId, keys);
    if (entities.size() != keys.size()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "所选错题已失效，请刷新后重试");
    }
    if (entities.stream().anyMatch(entity -> entity.getStatus() == WrongQuestionStatus.ARCHIVED)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "已移除的错题不能重练");
    }
    return entities;
  }

  private List<WrongQuestionEntity> randomCandidates(Long ownerId,
                                                     WrongQuestionRetryRequest request) {
    WrongQuestionRetryRequest.WrongQuestionScope scope = request.scope();
    WrongQuestionFilter filter = scope == null
        ? new WrongQuestionFilter(null, null, null, null, WrongQuestionStatus.PENDING, null)
        : new WrongQuestionFilter(scope.examCode(), scope.subjectCode(),
            scope.knowledgePointKey(), scope.questionType(), WrongQuestionStatus.PENDING,
            scope.keyword());
    List<WrongQuestionEntity> entities = repository.findAll(
        buildSpec(ownerId, filter),
        Sort.by(Sort.Order.desc("lastWrongAt"), Sort.Order.desc("updatedAt")));
    int count = request.count() == null
        ? properties.getWrongBookRandomRetryCount() : request.count();
    int safeCount = Math.min(Math.max(1, count), properties.getWrongBookMaxRetryQuestions());
    List<WrongQuestionEntity> shuffled = new ArrayList<>(entities);
    Collections.shuffle(shuffled, ThreadLocalRandom.current());
    return shuffled.stream().limit(safeCount).toList();
  }

  private InterviewSessionDTO createRetrySession(List<WrongQuestionEntity> entities) {
    List<SectionGroup> groups = new ArrayList<>();
    Map<String, SectionGroup> groupMap = new LinkedHashMap<>();
    int questionIndex = 0;
    for (WrongQuestionEntity entity : entities) {
      WrongQuestionSnapshot snapshot = readSnapshot(entity.getQuestionSnapshot());
      if (snapshot == null || snapshot.question() == null) {
        log.warn("跳过无法解析的错题快照: questionKey={}", entity.getQuestionKey());
        continue;
      }
      String groupKey = entity.getSkillId() + "|" + nullToEmpty(snapshot.sectionTitle())
          + "|" + materialKey(snapshot.materials());
      SectionGroup group = groupMap.computeIfAbsent(groupKey, key -> {
        SectionGroup created = new SectionGroup(snapshot.sectionTitle(),
            snapshot.sectionSubtitle(), snapshot.materials());
        groups.add(created);
        return created;
      });
      InterviewQuestionDTO retryQuestion = snapshot.question()
          .withQuestionIndex(questionIndex++);
      group.questions.add(new QuestionEntry(retryQuestion, snapshot.assetIds()));
    }
    if (groups.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "所选错题没有可重建的题目");
    }

    List<PaperNode> sections = new ArrayList<>();
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    int sectionOrder = 1;
    for (SectionGroup group : groups) {
      List<PaperNode> children = new ArrayList<>();
      int materialOrder = 1;
      for (WrongQuestionSnapshot.MaterialBlock material : group.materials) {
        if (material != null && material.content() != null && !material.content().isBlank()) {
          children.add(materialNode("m" + sectionOrder + "_" + materialOrder,
              materialOrder, material));
          materialOrder++;
        }
      }
      for (QuestionEntry entry : group.questions) {
        children.add(questionNode(children.size() + 1, entry.question(), entry.assetIds()));
        questions.add(entry.question());
      }
      sections.add(PaperNode.section("s" + sectionOrder, sectionOrder,
          group.title == null || group.title.isBlank() ? "错题重练" : group.title,
          group.subtitle, children));
      sectionOrder++;
    }
    PaperTree tree = PaperTree.recalculate(new PaperTree(
        PaperTree.CURRENT_VERSION, PaperNode.paper("n0", "错题重练", null, sections)));
    String skillId = distinctValue(entities.stream().map(WrongQuestionEntity::getSkillId).toList());
    String subjectLabel = distinctValue(
        entities.stream().map(WrongQuestionEntity::getSubjectName).toList());
    Long paperId = distinctPaperId(entities);
    return sessionService.createSessionFromQuestions(
        questions, tree, null, skillId, "mid", null,
        subjectLabel == null ? "综合错题" : subjectLabel, paperId, "QUIZ_WRONG");
  }

  private PaperNode materialNode(String id, int order,
                                 WrongQuestionSnapshot.MaterialBlock material) {
    return new PaperNode(
        id,
        PaperNode.TYPE_MATERIAL,
        order,
        0,
        true,
        material.title(),
        null,
        material.content(),
        new PaperNode.PaperMeta(null, null, null, material.assetIds()),
        null,
        null,
        List.of());
  }

  private PaperNode questionNode(int order, InterviewQuestionDTO question,
                                 List<String> assetIds) {
    return new PaperNode(
        "q" + (question.questionIndex() + 1),
        PaperNode.TYPE_QUESTION,
        order,
        0,
        true,
        null,
        null,
        null,
        new PaperNode.PaperMeta(question.fullScore(), 1, null, assetIds),
        question.questionIndex(),
        question,
        List.of());
  }

  private WrongQuestionEntity newEntity(Long ownerId, String skillId,
                                        InterviewQuestionDTO question,
                                        WrongQuestionSnapshot snapshot) {
    QuizSubjectSpec spec = catalogService.resolveSubjectForOwner(ownerId, skillId);
    WrongQuestionEntity entity = new WrongQuestionEntity();
    entity.setOwnerId(ownerId);
    entity.setQuestionKey(fingerprint(question, snapshot, skillId));
    entity.setSkillId(skillId == null || skillId.isBlank() ? "unknown" : skillId);
    if (spec != null) {
      entity.setExamCode(spec.examCode());
      entity.setExamName(spec.examName());
      entity.setSubjectName(spec.name());
    }
    entity.setKnowledgePointKey(question.type());
    entity.setKnowledgePointName(question.category());
    entity.setQuestionType(question.questionType());
    entity.setStem(question.question());
    entity.setQuestionSnapshot(writeSnapshot(snapshot));
    entity.setStatus(WrongQuestionStatus.PENDING);
    entity.setCorrectCount(0);
    entity.setWrongCount(0);
    return entity;
  }

  private void backfillIfNeeded(Long ownerId) {
    if (backfillRepository.existsById(ownerId)) {
      return;
    }
    Map<String, WrongQuestionEntity> entries = repository.findByOwnerId(ownerId).stream()
        .collect(Collectors.toMap(WrongQuestionEntity::getQuestionKey, entity -> entity,
            (left, right) -> left, LinkedHashMap::new));
    List<PracticeSessionEntity> sessions = persistenceService.findSessionsByOwnerId(ownerId).stream()
        .filter(session -> isQuizSource(session.getSourceType()))
        .filter(this::isGradedSession)
        .sorted(Comparator.comparing(PracticeSessionEntity::getCreatedAt,
            Comparator.nullsFirst(Comparator.naturalOrder())))
        .toList();
    for (PracticeSessionEntity session : sessions) {
      PaperTree tree = persistenceService.findPaperTree(session.getSessionId()).orElse(null);
      if (tree == null) {
        continue;
      }
      Map<Integer, InterviewQuestionDTO> questions = tree.flattenQuestions().stream()
          .collect(Collectors.toMap(InterviewQuestionDTO::questionIndex, question -> question,
              (left, right) -> left, LinkedHashMap::new));
      for (PracticeAnswerEntity answer : persistenceService.findAnswersBySessionId(
          session.getSessionId())) {
        if (attemptEventRepository.existsBySessionIdAndQuestionIndex(
            session.getSessionId(), answer.getQuestionIndex())) {
          continue;
        }
        InterviewQuestionDTO question = questions.get(answer.getQuestionIndex());
        if (question == null || answer.getScore() == null
            || "MANUAL_REVIEW".equals(answer.getScoreStatus())) {
          continue;
        }
        WrongQuestionSnapshot snapshot = buildSnapshot(
            tree, question, session.getResumeId());
        String questionKey = fingerprint(question, snapshot, session.getSkillId());
        WrongQuestionEntity entity = entries.get(questionKey);
        boolean correct = isCorrect(question.questionType(), answer.getScore());
        if (correct && entity == null) {
          continue;
        }
        if (entity == null) {
          entity = newEntity(ownerId, session.getSkillId(), question, snapshot);
          entries.put(questionKey, entity);
        }
        LocalDateTime answeredAt = answer.getAnsweredAt() == null
            ? LocalDateTime.now() : answer.getAnsweredAt();
        boolean archived = entity != null && entity.getStatus() == WrongQuestionStatus.ARCHIVED;
        LocalDateTime archivedAt = entity == null ? null : entity.getArchivedAt();
        if (correct) {
          entity.recordCorrect(answeredAt);
        } else {
          entity.recordWrong(answeredAt);
        }
        if (archived) {
          entity.archive(archivedAt == null ? answeredAt : archivedAt);
        }
        entity.setLastScoreRate(answer.getScore());
        entity.setLastUserAnswer(answer.getUserAnswer());
        entity.setStem(question.question());
        entity.setQuestionSnapshot(writeSnapshot(snapshot));
        saveAttemptEvent(ownerId, session.getSessionId(), answer.getQuestionIndex());
      }
    }
    repository.saveAll(entries.values());
    backfillRepository.save(new WrongQuestionBackfillEntity(ownerId, LocalDateTime.now()));
    log.info("错题本历史回填完成: ownerId={}, count={}", ownerId, entries.size());
  }

  private WrongQuestionSnapshot buildSnapshot(PaperTree tree, InterviewQuestionDTO question,
                                              Long paperId) {
    PaperNode section = tree.nearestSection(question.questionIndex()).orElse(null);
    List<WrongQuestionSnapshot.MaterialBlock> materials = tree.materialsOf(question.questionIndex())
        .stream()
        .map(node -> new WrongQuestionSnapshot.MaterialBlock(
            node.title(), node.content(), assetIdsOf(node)))
        .toList();
    return new WrongQuestionSnapshot(
        question,
        section == null ? null : section.title(),
        section == null ? null : section.subtitle(),
        materials,
        paperId,
        tree.questionNode(question.questionIndex())
            .map(this::assetIdsOf)
            .orElse(List.of()));
  }

  private String fingerprint(InterviewQuestionDTO question, WrongQuestionSnapshot snapshot,
                             String skillId) {
    return WrongQuestionFingerprint.of(skillId, question, snapshot);
  }

  private WrongQuestionDTO toDto(WrongQuestionEntity entity) {
    WrongQuestionSnapshot snapshot = readSnapshot(entity.getQuestionSnapshot());
    InterviewQuestionDTO question = snapshot == null ? null : snapshot.question();
    return new WrongQuestionDTO(
        entity.getQuestionKey(),
        entity.getSkillId(),
        entity.getExamCode(),
        entity.getExamName(),
        entity.getSubjectName(),
        entity.getKnowledgePointKey(),
        entity.getKnowledgePointName(),
        entity.getQuestionType(),
        QuestionTypeLabels.labelOf(entity.getQuestionType()),
        entity.getStem(),
        question == null || question.options() == null ? List.of() : question.options(),
        question == null ? null : question.fullScore(),
        snapshot == null ? List.of() : snapshot.materials(),
        snapshot == null ? null : snapshot.paperId(),
        snapshot == null ? List.of() : snapshot.assetIds(),
        entity.getLastUserAnswer(),
        question == null ? null : question.referenceAnswer(),
        question == null ? null : question.analysis(),
        entity.getLastScoreRate(),
        entity.getCorrectCount(),
        entity.getWrongCount(),
        entity.getStatus(),
        entity.getStatus().label(),
        entity.getLastWrongAt(),
        entity.getLastCorrectAt(),
        entity.getMasteredAt(),
        entity.getArchivedAt());
  }

  private Specification<WrongQuestionEntity> buildSpec(Long ownerId, WrongQuestionFilter filter) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (ownerId != null) {
        predicates.add(cb.equal(root.get("ownerId"), ownerId));
      }
      if (filter != null) {
        addEq(predicates, cb, root, "examCode", filter.examCode());
        addEq(predicates, cb, root, "skillId", filter.subjectCode());
        addEq(predicates, cb, root, "knowledgePointKey", filter.knowledgePointKey());
        addEq(predicates, cb, root, "questionType", filter.questionType());
        if (filter.status() != null) {
          predicates.add(cb.equal(root.get("status"), filter.status()));
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
          String keyword = "%" + filter.keyword().trim().toLowerCase(Locale.ROOT) + "%";
          predicates.add(cb.or(
              cb.like(cb.lower(root.get("stem")), keyword),
              cb.like(cb.lower(root.get("knowledgePointName")), keyword),
              cb.like(cb.lower(root.get("subjectName")), keyword),
              cb.like(cb.lower(root.get("examName")), keyword)));
        }
      }
      return cb.and(predicates.toArray(Predicate[]::new));
    };
  }

  private void addEq(List<Predicate> predicates, CriteriaBuilder cb,
                     Root<WrongQuestionEntity> root,
                     String field, String value) {
    if (value != null && !value.isBlank()) {
      predicates.add(cb.equal(root.get(field), value));
    }
  }

  private List<WrongQuestionPageDTO.CountItem> countStatuses(
      Long ownerId, WrongQuestionFilter filter) {
    WrongQuestionFilter withoutStatus = filter == null
        ? new WrongQuestionFilter(null, null, null, null, null, null)
        : new WrongQuestionFilter(filter.examCode(), filter.subjectCode(),
            filter.knowledgePointKey(), filter.questionType(), null, filter.keyword());
    Specification<WrongQuestionEntity> scope = buildSpec(ownerId, withoutStatus);
    return java.util.Arrays.stream(WrongQuestionStatus.values())
        .map(status -> new WrongQuestionPageDTO.CountItem(
            status.name(),
            status.label(),
            repository.count(scope.and((root, query, cb) ->
                cb.equal(root.get("status"), status)))))
        .toList();
  }

  private List<WrongQuestionPageDTO.CountItem> toSubjectCounts(List<Object[]> rows) {
    return rows.stream()
        .map(row -> new WrongQuestionPageDTO.CountItem(
            row[0] == null ? "" : row[0].toString(),
            row[0] == null ? "未分类" : row[0].toString(),
            ((Number) row[1]).longValue()))
        .toList();
  }

  private boolean isQuizSource(String sourceType) {
    return "QUIZ".equals(sourceType) || "QUIZ_WRONG".equals(sourceType);
  }

  private boolean isGradedSession(PracticeSessionEntity session) {
    return session.getStatus() == PracticeSessionEntity.SessionStatus.EVALUATED
        || session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED;
  }

  private boolean isCorrect(String questionType, Integer scoreRate) {
    if (scoreRate == null) {
      return false;
    }
    if (OBJECTIVE_TYPES.contains(questionType)) {
      return scoreRate >= 100;
    }
    return scoreRate >= properties.getWrongBookSubjectiveCorrectThreshold();
  }

  private void saveAttemptEvent(Long ownerId, String sessionId, Integer questionIndex) {
    WrongQuestionAttemptEventEntity event = new WrongQuestionAttemptEventEntity();
    event.setOwnerId(ownerId);
    event.setSessionId(sessionId);
    event.setQuestionIndex(questionIndex);
    attemptEventRepository.save(event);
  }

  private WrongQuestionSnapshot readSnapshot(String json) {
    try {
      return objectMapper.readValue(json, WrongQuestionSnapshot.class);
    } catch (Exception e) {
      log.warn("错题快照解析失败", e);
      return null;
    }
  }

  private String writeSnapshot(WrongQuestionSnapshot snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存错题快照失败");
    }
  }

  private String materialKey(List<WrongQuestionSnapshot.MaterialBlock> materials) {
    if (materials == null) {
      return "";
    }
    return materials.stream()
        .filter(Objects::nonNull)
        .map(material -> nullToEmpty(material.title()) + "\u0000"
            + nullToEmpty(material.content()))
        .collect(Collectors.joining("\u0001"));
  }

  private List<String> assetIdsOf(PaperNode node) {
    if (node == null || node.meta() == null || node.meta().assetIds() == null) {
      return List.of();
    }
    return node.meta().assetIds().stream()
        .filter(Objects::nonNull)
        .filter(assetId -> !assetId.isBlank())
        .distinct()
        .toList();
  }

  private Long distinctPaperId(List<WrongQuestionEntity> entities) {
    Set<Long> paperIds = entities.stream()
        .map(entity -> readSnapshot(entity.getQuestionSnapshot()))
        .filter(Objects::nonNull)
        .map(WrongQuestionSnapshot::paperId)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return paperIds.size() == 1 ? paperIds.iterator().next() : null;
  }

  private String distinctValue(Collection<String> values) {
    Set<String> distinct = values.stream()
        .filter(value -> value != null && !value.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return distinct.size() == 1 ? distinct.iterator().next() : null;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static final class SectionGroup {
    private final String title;
    private final String subtitle;
    private final List<WrongQuestionSnapshot.MaterialBlock> materials;
    private final List<QuestionEntry> questions = new ArrayList<>();

    private SectionGroup(String title, String subtitle,
                         List<WrongQuestionSnapshot.MaterialBlock> materials) {
      this.title = title;
      this.subtitle = subtitle;
      this.materials = materials == null ? List.of() : materials;
    }
  }

  private record QuestionEntry(InterviewQuestionDTO question, List<String> assetIds) {
  }
}
