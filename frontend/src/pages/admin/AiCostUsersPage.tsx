import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Loader2, ShieldAlert } from 'lucide-react';
import { aiCostApi, type UserUsageItem } from '../../api/aiCost';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import NumberedPagination from '../../components/admin/NumberedPagination';

function fmtCny(value: number | null | undefined): string {
  if (value == null) return '—';
  return `¥${Number(value).toFixed(4)}`;
}

function fmtTokens(value: number | null | undefined): string {
  if (value == null) return '—';
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(value);
}

export default function AiCostUsersPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [items, setItems] = useState<UserUsageItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const days = Number(searchParams.get('days') ?? 7) || 7;
  const page = Math.max(0, Number(searchParams.get('page') ?? 0) || 0);
  const size = Math.max(1, Number(searchParams.get('size') ?? 20) || 20);
  const provider = searchParams.get('provider') || undefined;
  const model = searchParams.get('model') || undefined;
  const modality = searchParams.get('modality') || undefined;

  const query = useMemo(
    () => ({ days, provider, model, modality, page, size }),
    [days, provider, model, modality, page, size],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await aiCostApi.users(query);
      setItems(result.items);
      setTotal(result.total);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const totalPages = Math.max(1, Math.ceil(total / size));

  const goPage = (next: number) => {
    const params = new URLSearchParams(searchParams);
    params.set('page', String(Math.max(0, Math.min(next, totalPages - 1))));
    params.set('size', String(size));
    setSearchParams(params);
  };

  return (
    <div className="max-w-6xl mx-auto space-y-5">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <Link to={ROUTES.aiCostAdmin} className="text-sm text-primary-500 hover:text-primary-600">← 返回调用与成本</Link>
          <h1 className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">用户用量</h1>
          <p className="text-sm text-slate-400 mt-1">按当前时间范围和模型筛选展示全量用户</p>
        </div>
        <span className="text-sm text-slate-400">共 {total} 个用户</span>
      </div>

      {error && (
        <div className="rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 px-4 py-3 text-sm flex items-center gap-2">
          <ShieldAlert className="w-4 h-4" /> {error}
        </div>
      )}

      <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : items.length === 0 ? (
          <p className="text-sm text-slate-400">暂无数据</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-400 border-b border-slate-100 dark:border-slate-700">
                <th className="py-2">用户名</th>
                <th>调用数</th>
                <th>输入 Token</th>
                <th>输出 Token</th>
                <th>成本</th>
                <th>成功率</th>
              </tr>
            </thead>
            <tbody>
              {items.map(item => (
                <tr key={item.userId} className="border-b border-slate-50 dark:border-slate-700/50">
                  <td className="py-3 text-slate-900 dark:text-white">{item.userName}</td>
                  <td>{item.calls}</td>
                  <td>{fmtTokens(item.inputTokens)}</td>
                  <td>{fmtTokens(item.outputTokens)}</td>
                  <td>{fmtCny(item.cost)}</td>
                  <td>{Number(item.successRate).toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <NumberedPagination page={page} totalPages={totalPages} onChange={goPage} />
    </div>
  );
}
