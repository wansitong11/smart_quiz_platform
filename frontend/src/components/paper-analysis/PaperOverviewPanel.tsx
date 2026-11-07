import { useEffect, useState } from 'react';
import { AlertCircle, Loader2 } from 'lucide-react';
import { historyApi, type PaperOverview } from '../../api/history';
import PaperTreeView from '../PaperTreeView';

export default function PaperOverviewPanel({ paperId }: { paperId: number }) {
  const [overview, setOverview] = useState<PaperOverview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    historyApi.getPaperOverview(paperId)
      .then(data => {
        if (!cancelled) setOverview(data);
      })
      .catch(err => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载试卷概览失败');
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [paperId]);

  if (loading) {
    return (
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
        <Loader2 className="w-8 h-8 mx-auto mb-4 text-primary-500 animate-spin" />
        <p className="text-slate-500 dark:text-slate-400">正在加载试卷概览...</p>
      </div>
    );
  }

  if (error || !overview) {
    return (
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
        <AlertCircle className="w-8 h-8 mx-auto mb-4 text-red-500" />
        <h3 className="text-lg font-semibold text-slate-700 dark:text-slate-200 mb-2">
          加载试卷概览失败
        </h3>
        <p className="text-slate-500 dark:text-slate-400">{error || '请稍后重试'}</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-5 flex items-center justify-between gap-4 flex-wrap">
        <div>
          <p className="font-semibold text-slate-800 dark:text-slate-200">只读预览</p>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            这里只展示题目内容，不会影响你的作答记录
          </p>
        </div>
        <div className="text-sm text-slate-600 dark:text-slate-300">
          {overview.questionCount} 道题
          {overview.totalScore > 0 ? ` · 总分 ${overview.totalScore} 分` : ''}
        </div>
      </div>

      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        {overview.questionCount === 0 ? (
          <p className="text-center text-slate-500 dark:text-slate-400 py-8">暂无题目</p>
        ) : (
          <PaperTreeView
            tree={overview.paperTree}
            answers={{}}
            disabled
            paperId={overview.paperId}
            onAnswerChange={() => {}}
          />
        )}
      </div>
    </div>
  );
}
