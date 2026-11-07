package interview.guide.modules.resume.service;

/**
 * 答案键生成阶段：主求解、独立复核、冲突裁决。
 */
enum AnswerPhase {
  PRIMARY,
  VERIFY,
  TIE_BREAK,
  SUBJECTIVE_REVIEW
}
