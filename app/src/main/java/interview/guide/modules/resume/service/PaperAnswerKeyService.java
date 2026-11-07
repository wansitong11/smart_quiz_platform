package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.PaperTree;
import interview.guide.modules.interview.model.PracticeSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部答案键维护。接口只供管理员使用，不进入学生端校对流程。
 */
@Service
@RequiredArgsConstructor
public class PaperAnswerKeyService {

  private final ResumeRepository resumeRepository;
  private final InterviewSessionRepository sessionRepository;
  private final InterviewAnswerRepository answerRepository;
  private final InterviewSessionCache sessionCache;
  private final ObjectMapper objectMapper;

  public record UpdateAnswerKeyRequest(
      String referenceAnswer,
      String analysis,
      List<String> keyPoints,
      Integer expectedRevision
  ) {
  }

  public record UpdateResult(int revision, AnswerKeyDTO answerKey) {
  }

  @Transactional(rollbackFor = Exception.class)
  public UpdateResult update(Long paperId, int questionIndex, UpdateAnswerKeyRequest request) {
    PracticePaperEntity paper = resumeRepository.findByIdForUpdate(paperId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "试卷不存在"));
    PaperStructureSnapshot snapshot = readSnapshot(paper);
    if (request.expectedRevision() != null
        && !request.expectedRevision().equals(snapshot.revision())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "答案键已被其他操作修改，请刷新后重试");
    }
    AnswerKeyDTO old = snapshot.answerKeys().get(questionIndex);
    if (old == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "题目答案键不存在");
    }

    List<String> optionKeys = old.optionCorrectness() == null
        ? List.of() : List.copyOf(old.optionCorrectness().keySet());
    String reference = AnswerKeyPreparationService.normalizeChoiceAnswer(
        optionKeys, request.referenceAnswer());
    AnswerKeyDTO updated = new AnswerKeyDTO(
        questionIndex,
        reference,
        AnswerKeyPreparationService.correctnessOf(optionKeys, reference),
        request.analysis(),
        request.keyPoints() == null ? List.of() : request.keyPoints(),
        old.scoringRubric(),
        old.figureSummary(),
        old.requiresVisualGrading(),
        100,
        AnswerKeyDTO.SOURCE_MANUAL,
        List.of(),
        null,
        null,
        old.effectiveAnswerMode(),
        old.fingerprint()
    );
    Map<Integer, AnswerKeyDTO> answerKeys = new LinkedHashMap<>(snapshot.answerKeys());
    answerKeys.put(questionIndex, updated);
    PaperStructureSnapshot next = snapshot.withAnswerKeysAndFingerprints(
        answerKeys, snapshot.questionFingerprints());
    paper.setStructureJson(writeJson(next));
    resumeRepository.save(paper);

    refreshUnstartedSession(paperId, updated);
    return new UpdateResult(next.revision(), updated);
  }

  private void refreshUnstartedSession(Long paperId, AnswerKeyDTO answerKey) {
    sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
            paperId, List.of(PracticeSessionEntity.SessionStatus.CREATED))
        .filter(session -> "PAPER".equals(session.getSourceType()))
        .filter(session -> !answerRepository.existsBySession_SessionId(session.getSessionId()))
        .ifPresent(session -> {
          PaperTree tree = PaperTree.parse(objectMapper, session.getPaperJson());
          List<InterviewQuestionDTO> questions = tree.flattenQuestions().stream()
              .map(question -> question.questionIndex() == answerKey.questionIndex()
                  ? question.withAnswerKey(answerKey) : question)
              .toList();
          session.setPaperJson(tree.withQuestions(questions).toJson(objectMapper));
          sessionRepository.save(session);
          sessionCache.deleteSession(session.getSessionId());
        });
  }

  private PaperStructureSnapshot readSnapshot(PracticePaperEntity paper) {
    if (paper.getStructureJson() == null || paper.getStructureHash() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "试卷结构快照不存在");
    }
    try {
      return objectMapper.readValue(paper.getStructureJson(), PaperStructureSnapshot.class);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "答案键快照解析失败");
    }
  }

  private String writeJson(PaperStructureSnapshot snapshot) {
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "答案键快照保存失败");
    }
  }

}
