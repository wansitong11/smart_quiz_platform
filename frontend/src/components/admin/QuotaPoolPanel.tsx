import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Loader2, Save, Search, ShieldAlert, ShieldCheck, Users } from 'lucide-react';
import {
  aiCostApi,
  type QuotaConfigView,
  type QuotaUserItem,
  type UserTier,
} from '../../api/aiCost';
import { getErrorMessage } from '../../api/request';
import NumberedPagination from './NumberedPagination';

const PAGE_SIZE = 20;

function fmtCny(value: number | null | undefined): string {
  if (value == null) return '—';
  return `¥${Number(value).toFixed(4)}`;
}

export default function QuotaPoolPanel() {
  const [config, setConfig] = useState<QuotaConfigView | null>(null);
  const [users, setUsers] = useState<QuotaUserItem[]>([]);
  const [totalInput, setTotalInput] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [cfg, list] = await Promise.all([aiCostApi.quotaConfig(), aiCostApi.quotaUsers()]);
      setConfig(cfg);
      setTotalInput(String(cfg.monthlyTotalCny));
      setUsers(list);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const filtered = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    if (!normalized) return users;
    return users.filter(user =>
      user.username.toLowerCase().includes(normalized)
      || (user.displayName ?? '').toLowerCase().includes(normalized));
  }, [keyword, users]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const pageUsers = useMemo(
    () => filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [filtered, page],
  );

  useEffect(() => {
    setPage(current => Math.max(0, Math.min(current, totalPages - 1)));
  }, [totalPages]);

  const saveTotal = async (event: FormEvent) => {
    event.preventDefault();
    const value = Number(totalInput);
    if (!Number.isFinite(value) || value <= 0) {
      setError('月度总额度必须大于 0');
      return;
    }
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await aiCostApi.updateQuotaConfig(value);
      setNotice('月度总额度已更新，所有免费用户额度已同步');
      await load();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const changeTier = async (user: QuotaUserItem, tier: UserTier) => {
    if (user.tier === tier) return;
    setSaving(true);
    setError('');
    setNotice('');
    try {
      await aiCostApi.updateUserTier(user.id, tier);
      setNotice(`${user.displayName || user.username} 已设为${tier === 'AUTHORIZED' ? '授权' : '免费'}用户`);
      await load();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-800">
      <div className="mb-4">
        <h2 className="font-semibold text-slate-900 dark:text-white">额度总池</h2>
        <p className="mt-1 text-xs text-slate-400">
          免费用户共享月度总量，人均额度 = 总量 ÷ 免费用户数；授权用户豁免、不占池
        </p>
      </div>

      <form onSubmit={saveTotal} className="mb-4 flex flex-wrap items-end gap-3">
        <label className="block">
          <span className="block text-xs text-slate-400">月度总额度（¥）</span>
          <input
            type="number"
            min="0.01"
            step="0.01"
            value={totalInput}
            onChange={event => setTotalInput(event.target.value)}
            className="mt-1 w-44 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:border-slate-600 dark:bg-slate-900 dark:text-white dark:focus:ring-primary-900/40"
          />
        </label>
        <button
          type="submit"
          disabled={saving}
          className="inline-flex items-center gap-1.5 rounded-xl bg-primary-500 px-4 py-2 text-sm text-white transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          保存总量
        </button>
        {config && (
          <div className="flex flex-wrap items-center gap-3 text-xs text-slate-500 dark:text-slate-400">
            <span className="inline-flex items-center gap-1">
              <Users className="h-3.5 w-3.5" /> 免费用户 <b className="text-slate-700 dark:text-slate-200">{config.freeUserCount}</b>
            </span>
            <span>当前人均 <b className="text-slate-700 dark:text-slate-200">{fmtCny(config.perUserCny)}</b></span>
          </div>
        )}
      </form>

      {notice && (
        <div className="mb-3 flex items-center gap-2 rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300">
          <ShieldCheck className="h-4 w-4" /> {notice}
        </div>
      )}
      {error && (
        <div className="mb-3 flex items-center gap-2 rounded-xl bg-rose-50 px-4 py-2.5 text-sm text-rose-600 dark:bg-rose-900/30 dark:text-rose-300">
          <ShieldAlert className="h-4 w-4" /> {error}
        </div>
      )}

      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <form
          onSubmit={event => {
            event.preventDefault();
            setPage(0);
            setKeyword(keywordInput.trim());
          }}
          className="flex items-center gap-2"
        >
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={keywordInput}
              onChange={event => setKeywordInput(event.target.value)}
              placeholder="搜索用户名或显示名"
              className="w-56 rounded-xl border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm text-slate-800 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:border-slate-600 dark:bg-slate-900 dark:text-white dark:focus:ring-primary-900/40"
            />
          </div>
          <button
            type="submit"
            className="rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-600 transition hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
          >
            搜索
          </button>
        </form>
        <span className="text-xs text-slate-400">共 {filtered.length} 个可配置用户</span>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-7 w-7 animate-spin text-primary-500" />
        </div>
      ) : pageUsers.length === 0 ? (
        <p className="py-10 text-center text-sm text-slate-400">没有匹配的用户</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-slate-400 dark:border-slate-700">
                <th className="py-3">用户</th>
                <th>登录名</th>
                <th>档位</th>
                <th className="text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {pageUsers.map(user => (
                <tr
                  key={user.id}
                  className="border-b border-slate-50 transition hover:bg-slate-50/70 dark:border-slate-700/50 dark:hover:bg-slate-900/30"
                >
                  <td className="py-3 font-medium text-slate-900 dark:text-white">
                    {user.displayName || user.username}
                  </td>
                  <td className="text-slate-500 dark:text-slate-400">{user.username}</td>
                  <td>
                    <span className={`rounded-full px-2 py-0.5 text-xs ${
                      user.tier === 'AUTHORIZED'
                        ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300'
                        : 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400'
                    }`}>
                      {user.tier === 'AUTHORIZED' ? '授权' : '免费'}
                    </span>
                  </td>
                  <td className="text-right">
                    <select
                      value={user.tier}
                      disabled={saving}
                      onChange={event => void changeTier(user, event.target.value as UserTier)}
                      className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs text-slate-600 outline-none focus:border-primary-400 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-300"
                    >
                      <option value="FREE">免费</option>
                      <option value="AUTHORIZED">授权</option>
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <NumberedPagination
        className="mt-4"
        page={page}
        totalPages={totalPages}
        onChange={next => setPage(Math.max(0, Math.min(next, totalPages - 1)))}
      />
    </section>
  );
}
