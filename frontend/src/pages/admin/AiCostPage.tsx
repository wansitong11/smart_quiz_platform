import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity, Banknote, Clock, Cpu, Gauge, Loader2, RefreshCw, ShieldAlert,
  TrendingUp, Trash2, Wallet, Zap,
} from 'lucide-react';
import {
  aiCostApi,
  type ErrorItem, type EventItem, type ModelOption, type PriceItem,
  type PriceUpsertRequest, type RankingItem, type ReportSummary, type TrendPoint,
  type UserUsageItem,
} from '../../api/aiCost';
import { llmProviderApi } from '../../api/llmProvider';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import { featureName, modelLabel } from '../../constants/aiCostLabels';
import UserModelAssignmentPanel from '../../components/admin/UserModelAssignmentPanel';
import UserQuotaPanel from '../../components/admin/UserQuotaPanel';
import QuotaPoolPanel from '../../components/admin/QuotaPoolPanel';
import AdminDrawer from '../../components/admin/AdminDrawer';
import NumberedPagination from '../../components/admin/NumberedPagination';
import type { ProviderItem } from '../../types/llmProvider';

type View = 'cost' | 'prices' | 'models' | 'quotas' | 'pool';
const PRICE_PAGE_SIZE = 20;

const VIEW_ITEMS: Array<{
  key: View;
  label: string;
  description: string;
  icon: typeof Activity;
}> = [
  { key: 'cost', label: '调用与成本', description: '总览、趋势与调用明细', icon: Activity },
  { key: 'prices', label: '模型价格', description: '单价、来源与同步', icon: Banknote },
  { key: 'models', label: '模型分配', description: '文字与识图模型分配', icon: Cpu },
  { key: 'quotas', label: '用户配额', description: 'RPM/TPM/并发限流', icon: Gauge },
  { key: 'pool', label: '额度总池', description: '月总量与用户授权', icon: Wallet },
];

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

function fmtTime(value: string): string {
  return new Date(value).toLocaleString();
}

function modelValue(model: ModelOption): string {
  return [model.provider, model.model, model.modality].map(encodeURIComponent).join('|');
}

function parseModelValue(value: string): ModelOption | null {
  if (!value) return null;
  try {
    const [provider, model, modality] = value.split('|').map(decodeURIComponent);
    if (!provider || !model || !modality) return null;
    return { provider, model, modality };
  } catch {
    return null;
  }
}

function detailUrl(path: string, days: number, selected: ModelOption | null): string {
  const params = new URLSearchParams({ days: String(days) });
  if (selected) {
    params.set('provider', selected.provider);
    params.set('model', selected.model);
    params.set('modality', selected.modality);
  }
  return `${path}?${params.toString()}`;
}

function Card({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
      <p className="text-xs text-slate-400 dark:text-slate-500">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-slate-900 dark:text-white">{value}</p>
      {sub && <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">{sub}</p>}
    </div>
  );
}

export default function AiCostPage() {
  const [view, setView] = useState<View>('cost');
  const [days, setDays] = useState(7);
  const [models, setModels] = useState<ModelOption[]>([]);
  const [selectedModel, setSelectedModel] = useState<ModelOption | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [summary, setSummary] = useState<ReportSummary | null>(null);
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [featureRank, setFeatureRank] = useState<RankingItem[]>([]);
  const [providerRank, setProviderRank] = useState<RankingItem[]>([]);
  const [users, setUsers] = useState<UserUsageItem[]>([]);
  const [errors, setErrors] = useState<ErrorItem[]>([]);
  const [events, setEvents] = useState<EventItem[]>([]);

  const [prices, setPrices] = useState<PriceItem[]>([]);
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [pricesLoading, setPricesLoading] = useState(false);
  const [editing, setEditing] = useState<PriceUpsertRequest | null>(null);
  const [pricePage, setPricePage] = useState(0);
  const [priceNotice, setPriceNotice] = useState('');

  const reportQuery = useMemo(() => ({
    days,
    provider: selectedModel?.provider,
    model: selectedModel?.model,
    modality: selectedModel?.modality,
  }), [days, selectedModel]);

  const loadCost = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [s, t, fr, pr, us, er, ev] = await Promise.all([
        aiCostApi.summary(reportQuery),
        aiCostApi.trend(reportQuery),
        aiCostApi.ranking('FEATURE', reportQuery),
        aiCostApi.ranking('PROVIDER', reportQuery),
        aiCostApi.users({ ...reportQuery, page: 0, size: 10 }),
        aiCostApi.errors(reportQuery),
        aiCostApi.events({ ...reportQuery, page: 0, size: 10 }),
      ]);
      setSummary(s);
      setTrend(t);
      setFeatureRank(fr);
      setProviderRank(pr);
      setUsers(us.items);
      setErrors(er);
      setEvents(ev.items);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [reportQuery]);

  const loadPrices = useCallback(async () => {
    setPricesLoading(true);
    try {
      const [priceList, providerList] = await Promise.all([
        aiCostApi.prices(),
        llmProviderApi.list(),
      ]);
      setPrices(priceList);
      setProviders(providerList);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setPricesLoading(false);
    }
  }, []);

  useEffect(() => {
    if (view !== 'cost') return;
    let cancelled = false;
    aiCostApi.models(days)
      .then((list) => {
        if (cancelled) return;
        setModels(list);
        setSelectedModel(current => (
          current && list.some(item => modelValue(item) === modelValue(current))
            ? current
            : null
        ));
      })
      .catch((err) => {
        if (!cancelled) setError(getErrorMessage(err));
      });
    return () => {
      cancelled = true;
    };
  }, [days, view]);

  useEffect(() => {
    if (view === 'cost') {
      void loadCost();
    }
  }, [view, loadCost]);

  useEffect(() => {
    if (view === 'prices') {
      void loadPrices();
    }
  }, [view, loadPrices]);

  const maxCost = useMemo(() => Math.max(1, ...trend.map(p => Number(p.cost))), [trend]);
  const priceTotalPages = Math.max(1, Math.ceil(prices.length / PRICE_PAGE_SIZE));
  const pagedPrices = useMemo(
    () => prices.slice(pricePage * PRICE_PAGE_SIZE, (pricePage + 1) * PRICE_PAGE_SIZE),
    [prices, pricePage],
  );

  useEffect(() => {
    setPricePage(current => Math.max(0, Math.min(current, priceTotalPages - 1)));
  }, [priceTotalPages]);

  const handleSync = async () => {
    try {
      const result = await aiCostApi.syncPrices();
      await loadPrices();
      setError('');
      setPriceNotice(`已同步 ${result.applied} 条价格（来源：${result.source}）`);
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleSavePrice = async (form: PriceUpsertRequest) => {
    try {
      await aiCostApi.upsertPrice(form);
      setEditing(null);
      await loadPrices();
      setPriceNotice('价格已保存');
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleDeletePrice = async (item: PriceItem) => {
    if (item.source !== 'MANUAL') {
      setError('自动同步价不能删除，请直接改价覆盖为手工价');
      return;
    }
    if (!window.confirm(`确认删除 ${item.provider}/${item.model} 的手工价？删除后恢复自动同步价。`)) return;
    try {
      await aiCostApi.deletePrice(item.id);
      await loadPrices();
      setPriceNotice('手工价已删除，已恢复自动同步价');
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  return (
    <div className="max-w-6xl mx-auto space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">AI 调用与成本</h1>
          <p className="text-sm text-slate-400 mt-1">模型调用量、Token、费用、成功率与价格管理</p>
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          {view === 'cost' && (
            <>
              <select
                value={selectedModel ? modelValue(selectedModel) : ''}
                onChange={e => setSelectedModel(parseModelValue(e.target.value))}
                className="max-w-72 rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-1.5 text-sm"
              >
                <option value="">全部模型</option>
                {models.map(model => (
                  <option key={modelValue(model)} value={modelValue(model)}>
                    {modelLabel(model)}
                  </option>
                ))}
              </select>
              <select
                value={days}
                onChange={e => setDays(Number(e.target.value))}
                className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-1.5 text-sm"
              >
                <option value={7}>近 7 天</option>
                <option value={30}>近 30 天</option>
                <option value={90}>近 90 天</option>
              </select>
            </>
          )}
        </div>
      </div>

      <nav
        aria-label="调用成本菜单"
        className="grid grid-cols-2 gap-2 rounded-2xl border border-slate-200 bg-white p-2 dark:border-slate-700 dark:bg-slate-800 lg:grid-cols-4"
      >
        {VIEW_ITEMS.map(item => {
          const active = view === item.key;
          const Icon = item.icon;
          return (
            <button
              key={item.key}
              type="button"
              onClick={() => setView(item.key)}
              className={`flex items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                active
                  ? 'bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400'
                  : 'text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-700/60'
              }`}
            >
              <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-lg ${
                active
                  ? 'bg-primary-100 text-primary-600 dark:bg-primary-900/50 dark:text-primary-400'
                  : 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400'
              }`}>
                <Icon className="h-4 w-4" />
              </span>
              <span className="min-w-0">
                <span className={`block text-sm ${active ? 'font-semibold' : 'font-medium'}`}>
                  {item.label}
                </span>
                <span className="block truncate text-xs text-slate-400 dark:text-slate-500">
                  {item.description}
                </span>
              </span>
            </button>
          );
        })}
      </nav>

      {error && (
        <div className="rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 px-4 py-3 text-sm flex items-center gap-2">
          <ShieldAlert className="w-4 h-4" /> {error}
        </div>
      )}

      {view === 'cost' && (
        loading ? (
          <div className="flex items-center justify-center py-24">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : (
          <div className="space-y-6">
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <Card label="总调用次数" value={String(summary?.totalCalls ?? 0)} sub={`失败率 ${Number(summary?.failureRate ?? 0).toFixed(1)}%`} />
              <Card label="估算成本" value={fmtCny(summary?.estimatedCost)} sub="按价格表估算" />
              <Card label="输入 Token" value={fmtTokens(summary?.inputTokens)} sub={`输出 ${fmtTokens(summary?.outputTokens)}`} />
              <Card label="P95 耗时" value={summary?.p95LatencyMs ? `${Number(summary.p95LatencyMs).toFixed(0)}ms` : '—'} sub={`超预算 ${summary?.budgetExceeded ?? 0} 次`} />
            </div>

            <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
              <div className="flex items-center gap-2 mb-4">
                <TrendingUp className="w-4 h-4 text-primary-500" />
                <h2 className="font-semibold text-slate-900 dark:text-white">成本趋势</h2>
              </div>
              {trend.length === 0 ? (
                <p className="text-sm text-slate-400">暂无数据</p>
              ) : (
                <div className="h-40 flex items-end gap-2">
                  {trend.map((p, i) => (
                    <div key={i} className="h-full flex-1 flex flex-col items-center justify-end gap-1">
                      <div
                        className="w-full min-h-1 rounded-t bg-gradient-to-t from-primary-500 to-indigo-400"
                        style={{ height: `${Math.max(4, (Number(p.cost) / maxCost) * 100)}%` }}
                        title={`${p.date.slice(0, 10)}: ${fmtCny(p.cost)}`}
                      />
                      <span className="shrink-0 text-[10px] text-slate-400">{p.date.slice(5, 10)}</span>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <div className="grid md:grid-cols-2 gap-4">
              <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
                <h2 className="font-semibold text-slate-900 dark:text-white mb-3 flex items-center gap-2">
                  <Zap className="w-4 h-4 text-amber-500" /> 功能成本排行
                </h2>
                <RankList items={featureRank} label={featureName} />
              </section>
              <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
                <h2 className="font-semibold text-slate-900 dark:text-white mb-3 flex items-center gap-2">
                  <Banknote className="w-4 h-4 text-emerald-500" /> Provider 排行
                </h2>
                <RankList items={providerRank} />
              </section>
            </div>

            <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
              <div className="flex items-center justify-between mb-3">
                <h2 className="font-semibold text-slate-900 dark:text-white">用户用量 Top 10</h2>
                <Link
                  to={detailUrl(ROUTES.aiCostUsers, days, selectedModel)}
                  className="text-sm text-primary-500 hover:text-primary-600"
                >
                  查看 →
                </Link>
              </div>
              {users.length === 0 ? (
                <p className="text-sm text-slate-400">暂无数据</p>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-slate-400 border-b border-slate-100 dark:border-slate-700">
                      <th className="py-2">用户</th>
                      <th>调用数</th>
                      <th>输入</th>
                      <th>输出</th>
                      <th>成本</th>
                      <th>成功率</th>
                    </tr>
                  </thead>
                  <tbody>
                    {users.map(u => (
                      <tr key={u.userId} className="border-b border-slate-50 dark:border-slate-700/50">
                        <td className="py-2 text-slate-900 dark:text-white">{u.userName}</td>
                        <td>{u.calls}</td>
                        <td>{fmtTokens(u.inputTokens)}</td>
                        <td>{fmtTokens(u.outputTokens)}</td>
                        <td>{fmtCny(u.cost)}</td>
                        <td>{Number(u.successRate).toFixed(1)}%</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>

            <div className="grid md:grid-cols-2 gap-4">
              <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
                <h2 className="font-semibold text-slate-900 dark:text-white mb-3">失败原因</h2>
                {errors.length === 0 ? (
                  <p className="text-sm text-slate-400">暂无失败调用</p>
                ) : (
                  <ul className="space-y-2">
                    {errors.map(e => (
                      <li key={e.key} className="flex justify-between text-sm">
                        <span className="text-slate-600 dark:text-slate-300">{e.key}</span>
                        <span className="text-slate-400">{e.count}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
                <div className="flex items-center justify-between mb-3">
                  <h2 className="font-semibold text-slate-900 dark:text-white flex items-center gap-2">
                    <Clock className="w-4 h-4 text-primary-500" /> 最近调用
                  </h2>
                  <Link
                    to={detailUrl(ROUTES.aiCostEvents, days, selectedModel)}
                    className="text-sm text-primary-500 hover:text-primary-600"
                  >
                    查看 →
                  </Link>
                </div>
                {events.length === 0 ? (
                  <p className="text-sm text-slate-400">暂无调用</p>
                ) : (
                  <ul className="space-y-2 max-h-64 overflow-y-auto">
                    {events.map(e => (
                      <li key={e.id} className="text-xs py-1.5 border-b border-slate-50 dark:border-slate-700/50">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-slate-600 dark:text-slate-300 truncate">
                            {e.userName} · {featureName(e.feature)}
                          </span>
                          <span className={`shrink-0 ${e.status === 'SUCCESS' ? 'text-emerald-500' : 'text-rose-500'}`}>
                            {e.status === 'SUCCESS' ? '成功' : e.status} · {fmtCny(e.estimatedCost)}
                          </span>
                        </div>
                        <p className="mt-1 text-[11px] text-slate-400 truncate">
                          {fmtTime(e.occurredAt)} · {modelLabel(e)} · {e.latencyMs == null ? '—' : `${e.latencyMs}ms`}
                        </p>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </div>
          </div>
        )
      )}

      {view === 'prices' && (
        <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-5">
          <div className="flex items-center justify-between mb-4">
            <h2 className="font-semibold text-slate-900 dark:text-white">模型单价（每百万 Token · 元）</h2>
            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-400">共 {prices.length} 条</span>
              <button
                type="button"
                onClick={() => setEditing({ provider: '', model: '', modality: 'TEXT', inputPricePer1m: 0, outputPricePer1m: 0, cachedPricePer1m: null })}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary-500 text-white text-sm hover:bg-primary-600"
              >
                <Activity className="w-4 h-4" /> 新增模型价格
              </button>
              <button
                type="button"
                onClick={handleSync}
                disabled={pricesLoading}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-600 text-sm hover:bg-slate-50 dark:hover:bg-slate-700"
              >
                <RefreshCw className={`w-4 h-4 ${pricesLoading ? 'animate-spin' : ''}`} /> 立即同步
              </button>
            </div>
          </div>

          {editing && (
            <AdminDrawer
              title={editing.id != null ? '编辑模型价格' : '新增模型价格'}
              subtitle={editing.id != null
                ? `${editing.provider} / ${editing.model} · 仅可修改输入与输出单价`
                : '选择供应商后自动带出模型与模态'}
              onClose={() => setEditing(null)}
            >
              <PriceForm
                key={editing.id ?? 'new'}
                initial={editing}
                providers={providers}
                error={error}
                onCancel={() => setEditing(null)}
                onSave={handleSavePrice}
              />
            </AdminDrawer>
          )}

          {priceNotice && (
            <div className="mb-3 rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300">
              {priceNotice}
            </div>
          )}

          <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-sm">
            <thead>
              <tr className="text-left text-slate-400 border-b border-slate-100 dark:border-slate-700">
                <th className="py-2">Provider / Model</th>
                <th>模态</th>
                <th>输入</th>
                <th>输出</th>
                <th>来源</th>
                <th>生效时间</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {pagedPrices.map(p => (
                <tr key={p.id} className="border-b border-slate-50 dark:border-slate-700/50">
                  <td className="py-2">
                    <span className="text-slate-900 dark:text-white font-medium">{p.provider}</span>
                    <span className="text-slate-400"> / {p.model}</span>
                  </td>
                  <td>{p.modality}</td>
                  <td>{p.inputPricePer1m == null ? '—' : `¥${Number(p.inputPricePer1m).toFixed(4)}`}</td>
                  <td>{p.outputPricePer1m == null ? '—' : `¥${Number(p.outputPricePer1m).toFixed(4)}`}</td>
                  <td>
                    <span className={`px-2 py-0.5 rounded text-xs ${p.source === 'MANUAL' ? 'bg-amber-100 text-amber-700' : 'bg-slate-100 text-slate-500'}`}>
                      {p.source === 'MANUAL' ? '手工' : '自动'}
                    </span>
                  </td>
                  <td className="text-xs text-slate-400">{new Date(p.effectiveFrom).toLocaleString()}</td>
                  <td className="text-right">
                    <button
                      type="button"
                      onClick={() => setEditing({
                        id: p.id,
                        provider: p.provider,
                        model: p.model,
                        modality: p.modality,
                        inputPricePer1m: p.inputPricePer1m,
                        outputPricePer1m: p.outputPricePer1m,
                        cachedPricePer1m: p.cachedPricePer1m,
                      })}
                      className="text-slate-400 hover:text-primary-500 mr-2"
                      title="编辑"
                    >
                      <Activity className="w-4 h-4" />
                    </button>
                    {p.source === 'MANUAL' && (
                      <button type="button" onClick={() => handleDeletePrice(p)} className="text-slate-400 hover:text-rose-500">
                        <Trash2 className="w-4 h-4" />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>

          <NumberedPagination
            className="mt-4"
            page={pricePage}
            totalPages={priceTotalPages}
            onChange={next => setPricePage(Math.max(0, Math.min(next, priceTotalPages - 1)))}
          />
        </div>
      )}

      {view === 'models' && <UserModelAssignmentPanel />}
      {view === 'quotas' && <UserQuotaPanel />}
      {view === 'pool' && <QuotaPoolPanel />}
    </div>
  );
}

function RankList({ items, label }: { items: RankingItem[]; label?: (key: string) => string }) {
  const max = Math.max(1, ...items.map(i => Number(i.cost)));
  return (
    <ul className="space-y-3">
      {items.map(i => (
        <li key={i.key}>
          <div className="flex justify-between text-sm mb-1">
            <span className="text-slate-600 dark:text-slate-300">{label ? label(i.key) : i.key}</span>
            <span className="text-slate-400">{fmtCny(i.cost)}</span>
          </div>
          <div className="h-2 rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden">
            <div
              className="h-full rounded-full bg-gradient-to-r from-primary-500 to-indigo-400"
              style={{ width: `${(Number(i.cost) / max) * 100}%` }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

function priceChoices(provider: ProviderItem): Array<{ modality: string; model: string }> {
  const choices: Array<{ modality: string; model: string }> = [];
  if (provider.model) {
    choices.push({ modality: 'TEXT', model: provider.model });
    if (provider.supportsVision) {
      choices.push({ modality: 'VISION', model: provider.model });
    }
  }
  if (provider.supportsEmbedding && provider.embeddingModel) {
    choices.push({ modality: 'EMBEDDING', model: provider.embeddingModel });
  }
  return choices;
}

function PriceForm({ initial, providers, error, onSave, onCancel }: {
  initial: PriceUpsertRequest;
  providers: ProviderItem[];
  error?: string;
  onSave: (form: PriceUpsertRequest) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState(initial);
  const editing = initial.id != null;
  const selectedProvider = providers.find(provider => provider.id === form.provider) ?? null;
  const choices = useMemo(
    () => selectedProvider ? priceChoices(selectedProvider) : [],
    [selectedProvider],
  );

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.provider || !form.model || !form.modality) return;
    if (!editing && choices.length === 0) return;
    onSave(form);
  };

  const handleProviderChange = (providerId: string) => {
    const provider = providers.find(item => item.id === providerId);
    const first = provider ? priceChoices(provider)[0] : undefined;
    setForm(current => ({
      ...current,
      provider: providerId,
      model: first?.model ?? '',
      modality: first?.modality ?? 'TEXT',
    }));
  };

  const handleModalityChange = (modality: string) => {
    const choice = choices.find(item => item.modality === modality);
    if (!choice) return;
    setForm(current => ({ ...current, modality, model: choice.model }));
  };

  const fieldClass =
    'mt-1.5 w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2.5 text-sm text-slate-800 dark:text-white outline-none focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/40 transition';
  const lockedClass =
    `${fieldClass} bg-slate-100 text-slate-500 dark:bg-slate-900/60 dark:text-slate-400`;

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">供应商</label>
        {editing ? (
          <input disabled value={form.provider} className={lockedClass} />
        ) : (
          <select
            required
            value={form.provider}
            onChange={e => handleProviderChange(e.target.value)}
            className={fieldClass}
          >
            <option value="">选择 Provider</option>
            {providers.map(provider => (
              <option key={provider.id} value={provider.id}>
                {provider.id}（{provider.model}）
              </option>
            ))}
          </select>
        )}
        {editing && (
          <p className="mt-1.5 text-xs text-slate-400 dark:text-slate-500">
            已有价格的供应商、模型与模态不可修改，仅可调整单价
          </p>
        )}
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">
          模型
          {!editing && <span className="ml-1 text-xs font-normal text-slate-400">由供应商自动带出</span>}
        </label>
        <input
          required
          readOnly
          placeholder="选择 Provider 后自动填入"
          value={form.model}
          className={lockedClass}
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">模态</label>
        {editing ? (
          <input disabled value={form.modality} className={lockedClass} />
        ) : (
          <select
            required
            value={form.modality}
            disabled={choices.length === 0}
            onChange={e => handleModalityChange(e.target.value)}
            className={`${fieldClass} disabled:opacity-60`}
          >
            {choices.map(choice => (
              <option key={choice.modality} value={choice.modality}>
                {choice.modality}
              </option>
            ))}
          </select>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">
            输入价（¥/1M Token）
          </label>
          <input
            type="number"
            step="0.0001"
            min="0"
            placeholder="0.0000"
            value={form.inputPricePer1m ?? ''}
            onChange={e => setForm({ ...form, inputPricePer1m: e.target.value ? Number(e.target.value) : null })}
            className={fieldClass}
          />
        </div>
        <div>
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">
            输出价（¥/1M Token）
          </label>
          <input
            type="number"
            step="0.0001"
            min="0"
            placeholder="0.0000"
            value={form.outputPricePer1m ?? ''}
            onChange={e => setForm({ ...form, outputPricePer1m: e.target.value ? Number(e.target.value) : null })}
            className={fieldClass}
          />
        </div>
      </div>

      {!editing && choices.length === 0 && (
        <p className="text-xs text-rose-500">该 Provider 没有可用模型，请先在系统设置中配置。</p>
      )}
      {error && (
        <div className="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:bg-rose-900/30 dark:text-rose-300">
          {error}
        </div>
      )}

      <div className="flex justify-end gap-3 pt-2">
        <button
          type="button"
          onClick={onCancel}
          className="rounded-xl border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          取消
        </button>
        <button
          type="submit"
          disabled={!form.provider || !form.model || (!editing && choices.length === 0)}
          className="rounded-xl bg-primary-500 px-4 py-2 text-sm text-white hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
        >
          保存
        </button>
      </div>
    </form>
  );
}
