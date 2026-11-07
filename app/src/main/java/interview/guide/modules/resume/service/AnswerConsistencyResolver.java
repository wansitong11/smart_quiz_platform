package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.AnswerKeyDTO;
import interview.guide.modules.interview.model.AnswerKeyDTO.AnswerCandidate;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 对主求解、独立复核和冲突裁决结果做一致性判断。
 */
@Component
public class AnswerConsistencyResolver {

  public static final String STATUS_CONSENSUS = "CONSENSUS";
  public static final String STATUS_MAJORITY = "MAJORITY";
  public static final String STATUS_CONFLICT = "CONFLICT";
  public static final String STATUS_SINGLE = "SINGLE";

  public record Resolution(
      AnswerKeyDTO answerKey,
      String finalAnswer,
      int confidence,
      String status,
      int consistencyScore,
      List<AnswerCandidate> candidates
  ) {
  }

  private record Candidate(AnswerKeyDTO key, String answer) {
  }

  public Resolution resolve(
      InterviewQuestionDTO question,
      AnswerKeyDTO primary,
      AnswerKeyDTO verifier,
      AnswerKeyDTO tieBreaker
  ) {
    List<Candidate> candidates = new ArrayList<>();
    addCandidate(candidates, question, primary);
    addCandidate(candidates, question, verifier);
    addCandidate(candidates, question, tieBreaker);
    if (candidates.isEmpty()) {
      throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "答案候选不能为空");
    }

    Candidate first = candidates.getFirst();
    if (candidates.size() == 1) {
      return resolution(question, first, STATUS_SINGLE, 50, candidates, first.key().confidence());
    }

    Candidate best = candidates.stream()
        .max(Comparator.comparingInt(candidate -> candidate.key().confidence()))
        .orElse(first);
    long same = candidates.stream().filter(candidate -> candidate.answer().equals(first.answer())).count();
    if (same == candidates.size()) {
      return resolution(question, best, STATUS_CONSENSUS, 100, candidates,
          best.key().confidence());
    }

    Candidate majority = candidates.stream()
        .filter(candidate -> candidates.stream()
            .filter(other -> other.answer().equals(candidate.answer())).count() >= 2)
        .max(Comparator.comparingInt(candidate -> candidate.key().confidence()))
        .orElse(null);
    if (majority != null) {
      return resolution(question, majority, STATUS_MAJORITY,
          candidates.size() == 3 ? 67 : 50, candidates,
          Math.min(79, majority.key().confidence()));
    }

    return resolution(question, best, STATUS_CONFLICT,
        candidates.size() == 3 ? 34 : 50, candidates,
        Math.min(59, best.key().confidence()));
  }

  private void addCandidate(
      List<Candidate> candidates,
      InterviewQuestionDTO question,
      AnswerKeyDTO key
  ) {
    String answer = normalizeAnswer(question, key);
    if (answer == null) {
      return;
    }
    candidates.add(new Candidate(key, answer));
  }

  private Resolution resolution(
      InterviewQuestionDTO question,
      Candidate selected,
      String status,
      int consistencyScore,
      List<Candidate> candidates,
      int confidence
  ) {
    AnswerKeyDTO base = selected.key();
    Map<String, Boolean> correctness = question.isChoiceQuestion()
        ? AnswerKeyPreparationService.correctnessOf(question, selected.answer())
        : base.optionCorrectness();
    List<AnswerCandidate> snapshots = candidates.stream()
        .map(candidate -> new AnswerCandidate(
            candidate.answer(), candidate.key().confidence(), candidate.key().source()))
        .toList();
    AnswerKeyDTO resolved = new AnswerKeyDTO(
        base.questionIndex(),
        selected.answer(),
        correctness,
        base.analysis(),
        base.keyPoints(),
        base.scoringRubric(),
        base.figureSummary(),
        base.requiresVisualGrading(),
        Math.max(0, Math.min(100, confidence)),
        base.source(),
        snapshots,
        status,
        consistencyScore,
        base.answerMode(),
        base.fingerprint()
    );
    return new Resolution(
        resolved, selected.answer(), resolved.confidence(), status, consistencyScore, snapshots);
  }

  private String normalizeAnswer(InterviewQuestionDTO question, AnswerKeyDTO key) {
    if (key == null) {
      return null;
    }
    String answer = key.referenceAnswer();
    if (answer == null || answer.isBlank()
        || "暂缺".equals(answer.trim()) || "无法确定".equals(answer.trim())) {
      return null;
    }
    if (question.isChoiceQuestion()) {
      return AnswerKeyPreparationService.normalizeChoiceAnswer(question, answer);
    }
    String normalized = answer.replaceAll("\\s+", "");
    return normalized.isBlank() ? null : normalized;
  }
}
