package interview.guide.common.ai;

/**
 * AI 成本归属的业务功能。新增功能在此加值，报表与预算自动纳入。
 */
public enum AiFeature {
  QUIZ_GENERATE,
  PAPER_IMPORT,
  PAPER_RECOGNIZE,
  PAPER_EXTRACTION,
  KB_QA,
  KB_VECTORIZE,
  KB_QUESTION_GEN,
  INTERVIEW_QUESTION_GEN,
  INTERVIEW_SCHEDULE_PARSE,
  ANSWER_EVALUATE,
  VOICE_EVALUATE,
  UNKNOWN
}
