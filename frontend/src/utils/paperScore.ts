/**
 * 试卷卷面分折算工具。
 *
 * 批改引擎给出的每题得分是 0-100 的得分率，试卷场景还要换算成卷面分：
 * 卷面得分 = Σ(得分率 × 该题分值 / 100)，四舍五入到整数。
 * 后端 AnswerEvaluationService 使用同一口径，两处需保持一致。
 */

export interface ScoredQuestionLike {
  questionIndex: number;
  category?: string | null;
  fullScore?: number | null;
}

export interface ScoredAnswerLike {
  questionIndex: number;
  score: number;
}

export interface PaperScoreSummary {
  /** 卷面总分（有分值的题目分值之和） */
  totalFullScore: number;
  /** 卷面得分 */
  paperScore: number;
  /** 有分值的题目数 */
  scoredQuestionCount: number;
  /** 得分率（0-100，无分值信息时退化为题目得分率的平均值） */
  scoreRate: number;
}

export interface CategoryStat {
  category: string;
  questionCount: number;
  fullScore: number;
  score: number;
  /** 该板块得分率（0-100） */
  rate: number;
}

export function questionPaperScore(scoreRate: number, fullScore?: number | null): number {
  if (fullScore == null || fullScore <= 0) {
    return 0;
  }
  const rate = Math.min(100, Math.max(0, scoreRate || 0));
  return Math.round((rate * fullScore) / 100);
}

export function summarizePaperScore(
  questions: ScoredQuestionLike[],
  answers: ScoredAnswerLike[]
): PaperScoreSummary {
  const answerMap = new Map(answers.map(answer => [answer.questionIndex, answer]));
  let totalFullScore = 0;
  let paperScore = 0;
  let scoredQuestionCount = 0;
  let rateSum = 0;
  let rateCount = 0;

  questions.forEach(question => {
    const answer = answerMap.get(question.questionIndex);
    const scoreRate = answer?.score ?? 0;
    rateSum += scoreRate;
    rateCount += 1;
    if (question.fullScore && question.fullScore > 0) {
      scoredQuestionCount += 1;
      totalFullScore += question.fullScore;
      paperScore += questionPaperScore(scoreRate, question.fullScore);
    }
  });

  const scoreRate = totalFullScore > 0
    ? Math.round((paperScore / totalFullScore) * 100)
    : rateCount > 0 ? Math.round(rateSum / rateCount) : 0;

  return { totalFullScore, paperScore, scoredQuestionCount, scoreRate };
}

/**
 * 按知识板块汇总得分，用于报告页的薄弱板块排序。
 */
export function summarizeCategories(
  questions: ScoredQuestionLike[],
  answers: ScoredAnswerLike[]
): CategoryStat[] {
  const answerMap = new Map(answers.map(answer => [answer.questionIndex, answer]));
  const stats = new Map<string, CategoryStat>();

  questions.forEach(question => {
    const category = (question.category || '未标注').trim() || '未标注';
    const answer = answerMap.get(question.questionIndex);
    const scoreRate = answer?.score ?? 0;
    const fullScore = question.fullScore && question.fullScore > 0 ? question.fullScore : 0;
    const score = questionPaperScore(scoreRate, fullScore);

    const current = stats.get(category) ?? {
      category,
      questionCount: 0,
      fullScore: 0,
      score: 0,
      rate: 0,
    };
    current.questionCount += 1;
    current.fullScore += fullScore;
    current.score += score;
    stats.set(category, current);
  });

  return Array.from(stats.values())
    .map(stat => ({
      ...stat,
      rate: stat.fullScore > 0 ? Math.round((stat.score / stat.fullScore) * 100) : 0,
    }))
    .sort((a, b) => a.rate - b.rate);
}
