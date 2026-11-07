import { AlertCircle, CheckCircle2, RotateCcw } from 'lucide-react';
import type { InterviewDetail } from '../../api/history';
import type { InterviewQuestion } from '../../types/interview';
import { sectionTitlesOf } from '../../utils/paperTree';
import { summarizeCategories, summarizePaperScore } from '../../utils/paperScore';

export function PaperAnalysisReportPanel({
  report,
  summary,
  weakCategories,
  onRetry,
}: {
  report: InterviewDetail;
  summary: ReturnType<typeof summarizePaperScore>;
  weakCategories: ReturnType<typeof summarizeCategories>;
  onRetry: () => void;
}) {
  const referenceMap = new Map<
    number,
    { referenceAnswer?: string | null; keyPoints?: string[]; analysis?: string | null }
  >();
  report.answers?.forEach(answer => {
    if (answer.questionIndex != null) {
      referenceMap.set(answer.questionIndex, {
        referenceAnswer: answer.referenceAnswer,
        keyPoints: answer.keyPoints,
        analysis: answer.analysis,
      });
    }
  });
  const optionsMap = new Map<number, NonNullable<InterviewQuestion['options']>>();
  ((report.questions ?? []) as Array<{ questionIndex?: number; options?: InterviewQuestion['options'] }>)
    .forEach(question => {
      if (question?.questionIndex != null && question.options && question.options.length >= 2) {
        optionsMap.set(question.questionIndex, question.options);
      }
    });
  const manualReviewCount = (report.answers ?? [])
    .filter(answer => answer.scoreStatus === 'MANUAL_REVIEW').length;
  const autoScoredCount = Math.max(0, (report.answers?.length ?? 0) - manualReviewCount);
  const hasAutoScore = autoScoredCount > 0;
  const displayScore = hasAutoScore
    ? summary.totalFullScore > 0 ? summary.paperScore : report.overallScore ?? 0
    : '待复核';
  const displayScoreSuffix = hasAutoScore
    ? summary.totalFullScore > 0 ? ` / ${summary.totalFullScore} 分` : ' / 100 分'
    : manualReviewCount > 0 ? ` · ${manualReviewCount} 题等待答案键/人工复核` : '';

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
          <div className="bg-gradient-to-br from-primary-50 dark:from-primary-900/30 to-white dark:to-slate-800 rounded-xl p-5">
            <span className="text-sm font-semibold text-primary-600 dark:text-primary-400 block mb-2">
              卷面得分
            </span>
            <span className="text-4xl font-bold text-slate-900 dark:text-white">
              {displayScore}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400">
              {displayScoreSuffix}
            </span>
          </div>
          <div className="bg-white dark:bg-slate-800 rounded-xl p-5 border border-slate-200 dark:border-slate-700">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 block mb-2">
              得分率
            </span>
            <span className="text-4xl font-bold text-slate-900 dark:text-white">
              {summary.scoreRate}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400"> %</span>
          </div>
          <div className="bg-white dark:bg-slate-800 rounded-xl p-5 border border-slate-200 dark:border-slate-700">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 block mb-2">
              题目数量
            </span>
            <span className="text-4xl font-bold text-slate-900 dark:text-white">
              {report.answers?.length ?? 0}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400"> 道</span>
          </div>
        </div>

        {report.overallFeedback && (
          <div className="p-4 bg-slate-50 dark:bg-slate-900/50 rounded-xl mb-4">
            <p className="text-slate-700 dark:text-slate-300 leading-relaxed whitespace-pre-wrap">
              {report.overallFeedback}
            </p>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {report.strengths && report.strengths.length > 0 && (
            <div>
              <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400 mb-2">
                做得好的地方
              </p>
              <ul className="space-y-1">
                {report.strengths.map((item, index) => (
                  <li key={index} className="text-sm text-slate-600 dark:text-slate-300 flex gap-2">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {report.improvements && report.improvements.length > 0 && (
            <div>
              <p className="text-sm font-semibold text-amber-600 dark:text-amber-400 mb-2">
                需要改进
              </p>
              <ul className="space-y-1">
                {report.improvements.map((item, index) => (
                  <li key={index} className="text-sm text-slate-600 dark:text-slate-300 flex gap-2">
                    <AlertCircle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>

      {weakCategories.length > 0 && (
        <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
          <p className="font-semibold text-slate-800 dark:text-slate-200 mb-4">知识板块表现</p>
          <div className="space-y-3">
            {weakCategories.map(stat => (
              <div key={stat.category}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span className="text-slate-700 dark:text-slate-300">
                    {stat.category}
                    <span className="ml-2 text-xs text-slate-400">
                      {stat.questionCount} 题 · {stat.score}/{stat.fullScore} 分
                    </span>
                  </span>
                  <span
                    className={`font-medium ${
                      stat.rate >= 80
                        ? 'text-emerald-600 dark:text-emerald-400'
                        : stat.rate >= 60
                          ? 'text-amber-600 dark:text-amber-400'
                          : 'text-red-600 dark:text-red-400'
                    }`}
                  >
                    {stat.rate}%
                  </span>
                </div>
                <div className="h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      stat.rate >= 80 ? 'bg-emerald-500' : stat.rate >= 60 ? 'bg-amber-500' : 'bg-red-500'
                    }`}
                    style={{ width: `${Math.min(100, stat.rate)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {report.sectionScores && report.sectionScores.length > 0 && (
        <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
          <p className="font-semibold text-slate-800 dark:text-slate-200 mb-4">按小节得分</p>
          <div className="space-y-3">
            {report.sectionScores.map(section => (
              <div key={section.title + section.level}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span className="text-slate-700 dark:text-slate-300">
                    {section.title}
                    {section.subtitle ? (
                      <span className="ml-2 text-xs text-slate-400">{section.subtitle}</span>
                    ) : null}
                    <span className="ml-2 text-xs text-slate-400">
                      {section.questionCount} 题 · {section.score}/{section.fullScore} 分
                    </span>
                  </span>
                  <span
                    className={`font-medium ${
                      section.scoreRate >= 80
                        ? 'text-emerald-600 dark:text-emerald-400'
                        : section.scoreRate >= 60
                          ? 'text-amber-600 dark:text-amber-400'
                          : 'text-red-600 dark:text-red-400'
                    }`}
                  >
                    {section.scoreRate}%
                  </span>
                </div>
                <div className="h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      section.scoreRate >= 80
                        ? 'bg-emerald-500'
                        : section.scoreRate >= 60
                          ? 'bg-amber-500'
                          : 'bg-red-500'
                    }`}
                    style={{ width: `${Math.min(100, section.scoreRate)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <p className="font-semibold text-slate-800 dark:text-slate-200 mb-4">逐题解析</p>
        <div className="space-y-5">
          {(report.answers ?? []).map(answer => {
            const reference = referenceMap.get(answer.questionIndex);
            const scoreRate = answer.score ?? 0;
            return (
              <div
                key={answer.questionIndex}
                className="border border-slate-200 dark:border-slate-700 rounded-xl p-4"
              >
                <div className="flex items-center gap-2 flex-wrap mb-3">
                  <span className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-xs">
                    第 {answer.questionIndex + 1} 题
                  </span>
                  {answer.category && (
                    <span className="px-2 py-0.5 bg-indigo-50 dark:bg-indigo-900/40 text-indigo-600 dark:text-indigo-300 rounded text-xs">
                      {answer.category}
                    </span>
                  )}
                  {sectionTitlesOf(report.paperTree, answer.questionIndex).slice(-1).map(title => (
                    <span
                      key={title}
                      className="px-2 py-0.5 bg-slate-50 dark:bg-slate-900/60 text-slate-500 dark:text-slate-400 rounded text-xs"
                    >
                      {title}
                    </span>
                  ))}
                  {answer.scoreStatus === 'MANUAL_REVIEW' ? (
                    <span className="px-2 py-0.5 rounded text-xs font-medium bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                      待人工复核
                    </span>
                  ) : (
                    <span
                      className={`px-2 py-0.5 rounded text-xs font-medium ${
                        scoreRate >= 80
                          ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400'
                          : scoreRate >= 60
                            ? 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400'
                            : 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400'
                      }`}
                    >
                      得分率 {scoreRate}%
                    </span>
                  )}
                </div>
                <p className="text-slate-800 dark:text-slate-100 whitespace-pre-wrap mb-3 text-sm leading-relaxed">
                  {answer.question}
                </p>
                {(() => {
                  const options = optionsMap.get(answer.questionIndex) ?? [];
                  if (options.length < 2) return null;
                  const correctKeys = (reference?.referenceAnswer ?? '')
                    .toUpperCase()
                    .split('')
                    .filter(key => options.some(option => option.key === key));
                  const chosenKeys = (answer.userAnswer ?? '').toUpperCase().split('');
                  return (
                    <div className="space-y-2 mb-3">
                      {options.map(option => {
                        const isCorrect = correctKeys.includes(option.key);
                        const isChosen = chosenKeys.includes(option.key);
                        const tone = isCorrect
                          ? 'border-emerald-400 bg-emerald-50 dark:bg-emerald-900/20'
                          : isChosen
                            ? 'border-red-300 bg-red-50 dark:bg-red-900/20'
                            : 'border-slate-200 dark:border-slate-700';
                        return (
                          <div key={option.key} className={`flex items-start gap-3 rounded-lg border px-3 py-2 text-sm ${tone}`}>
                            <span className="min-w-[18px] font-medium text-slate-500 dark:text-slate-400">
                              {option.key}
                            </span>
                            <span className="flex-1 text-slate-700 dark:text-slate-200 whitespace-pre-wrap">
                              {option.text}
                            </span>
                            {isCorrect && (
                              <span className="text-xs text-emerald-600 dark:text-emerald-400">正确答案</span>
                            )}
                            {isChosen && !isCorrect && (
                              <span className="text-xs text-red-600 dark:text-red-400">你的选择</span>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  );
                })()}
                {answer.lowConfidence && (
                  <div className="mb-3 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-200">
                    <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                    <span>
                      {answer.scoreMessage
                        ?? `本题答案依据由 AI 生成，置信度较低${answer.answerKeyConfidence != null ? `（${answer.answerKeyConfidence}%）` : ''}，当前判分仅供参考。`}
                    </span>
                  </div>
                )}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
                  <div className="p-3 bg-slate-50 dark:bg-slate-900/50 rounded-lg">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                      我的作答
                    </p>
                    <p className="text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {answer.userAnswer?.trim() ? answer.userAnswer : '（未作答）'}
                    </p>
                  </div>
                  <div className="p-3 bg-emerald-50/60 dark:bg-emerald-900/20 rounded-lg">
                    <p className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 mb-1">
                      参考答案
                    </p>
                    <p className="text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {reference?.referenceAnswer || '—'}
                    </p>
                    {reference?.keyPoints && reference.keyPoints.length > 0 && (
                      <ul className="mt-2 space-y-1">
                        {reference.keyPoints.map((point, index) => (
                          <li key={index} className="text-xs text-slate-500 dark:text-slate-400">
                            · {point}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
                {answer.feedback && (
                  <div className="mt-3 p-3 bg-slate-50 dark:bg-slate-900/50 rounded-lg">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                      AI 评语
                    </p>
                    <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {answer.feedback}
                    </p>
                  </div>
                )}
                {reference?.analysis && (
                  <div className="mt-3 p-3 bg-slate-50 dark:bg-slate-900/50 rounded-lg">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                      题目解析
                    </p>
                    <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {reference.analysis}
                    </p>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      <div className="flex justify-end">
        <button
          onClick={onRetry}
          className="px-5 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors flex items-center gap-2"
        >
          <RotateCcw className="w-4 h-4" />
          再练一遍
        </button>
      </div>
    </div>
  );
}
