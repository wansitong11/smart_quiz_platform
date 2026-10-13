import type { InterviewQuestion } from '../types/interview';

interface ChoiceOptionsViewProps {
  options?: InterviewQuestion['options'] | null;
  /** 正确答案（选项 key 序列，如 "C" / "BD"） */
  referenceAnswer?: string | null;
  /** 学生作答（选项 key 序列，如 "B" / "AC"） */
  chosenAnswer?: string | null;
}

/**
 * 客观题选项全量展示：正确项标绿「正确答案」，选错的项标红「你的选择」。
 * <p>
 * 报告里只给一串 key（"C"）学生没法对照，必须把四个选项都摆出来。
 * 试卷分析与模拟测验共用这段渲染，避免两边表现不一致。
 */
export default function ChoiceOptionsView({
  options,
  referenceAnswer,
  chosenAnswer,
}: ChoiceOptionsViewProps) {
  const list = options ?? [];
  if (list.length < 2) {
    return null;
  }

  const correctKeys = (referenceAnswer ?? '')
    .toUpperCase()
    .split('')
    .filter(key => list.some(option => option.key === key));
  const chosenKeys = (chosenAnswer ?? '').toUpperCase().split('');

  return (
    <div className="space-y-2">
      {list.map(option => {
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
              <span className="text-xs text-emerald-600 dark:text-emerald-400 whitespace-nowrap">
                正确答案
              </span>
            )}
            {isChosen && !isCorrect && (
              <span className="text-xs text-red-600 dark:text-red-400 whitespace-nowrap">
                你的选择
              </span>
            )}
            {isChosen && isCorrect && (
              <span className="text-xs text-slate-400 whitespace-nowrap">你选对了</span>
            )}
          </div>
        );
      })}
    </div>
  );
}
