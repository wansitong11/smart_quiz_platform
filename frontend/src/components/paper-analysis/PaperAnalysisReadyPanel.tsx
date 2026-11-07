import { FileText } from 'lucide-react';
import type { ResumeDetail } from '../../api/history';

export function PaperAnalysisReadyPanel({ detail, onStart, onReanalyze }: {
  detail: ResumeDetail | null;
  onStart: () => void;
  onReanalyze: () => void;
}) {
  const latest = detail?.analyses?.[0];
  const canStart = Boolean(latest?.practiceSessionId);

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <div className="flex items-start justify-between gap-4 flex-wrap mb-6">
          <div className="flex items-center gap-3">
            <FileText className="w-5 h-5 text-primary-500" />
            <div>
              <p className="font-semibold text-slate-800 dark:text-slate-200">
                {detail?.filename || '试卷 / 练习材料'}
              </p>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                已识别 {latest?.questionCount ?? 0} 道题，卷面总分 {latest?.totalScore ?? 0} 分
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={onReanalyze}
              className="px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-lg text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            >
              重新切题
            </button>
            <button
              onClick={() => onStart()}
              disabled={!canStart}
              className="px-5 py-2 bg-primary-500 text-white rounded-lg text-sm font-medium hover:bg-primary-600 transition-colors disabled:opacity-50"
            >
              开始作答
            </button>
          </div>
        </div>

        {latest?.summary && (
          <p className="text-slate-700 dark:text-slate-300 mb-6">{latest.summary}</p>
        )}

        {latest?.topics && latest.topics.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-4">
            {latest.topics.map((topic, index) => {
              const [label, count, score] = topic.split('|');
              return (
                <span
                  key={index}
                  className="px-3 py-1.5 bg-indigo-50 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-800 rounded-lg text-sm"
                >
                  {label}
                  <span className="ml-2 text-xs text-indigo-500 dark:text-indigo-400">
                    {count}
                    {score ? ` · ${score}` : ''}
                  </span>
                </span>
              );
            })}
          </div>
        )}

        {latest?.warnings && latest.warnings.length > 0 && (
          <div className="mt-4 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-xl">
            <p className="text-sm font-medium text-amber-700 dark:text-amber-400 mb-2">
              解析告警（{latest.warnings.length}）
            </p>
            <ul className="space-y-1">
              {latest.warnings.slice(0, 6).map((warning, index) => (
                <li key={index} className="text-sm text-amber-700/90 dark:text-amber-300/90">
                  · {warning}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}
