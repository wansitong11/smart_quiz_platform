import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Loader2, Pencil, RotateCcw, Save, Search, ShieldAlert, Users } from 'lucide-react';
import { getErrorMessage } from '../../api/request';
import { userModelsApi } from '../../api/userModels';
import { AI_PROVIDER_NAMES } from '../../constants/aiCostLabels';
import type {
  ProviderOption,
  UserModelAssignmentItem,
  UserModelOptions,
} from '../../types/userModels';
import AdminDrawer from './AdminDrawer';
import NumberedPagination from './NumberedPagination';

const PAGE_SIZE = 20;

function effectiveProviderLabel(
  providerId: string | null,
  model: string | null,
  options: ProviderOption[],
): string {
  if (!providerId) return '—';
  const providerName = AI_PROVIDER_NAMES[providerId] ?? providerId;
  const resolvedModel = model ?? options.find(item => item.id === providerId)?.model;
  return resolvedModel ? `${providerName} / ${resolvedModel}` : providerName;
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—';
}

const fieldClass =
  'mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:border-slate-600 dark:bg-slate-900 dark:text-white dark:focus:ring-primary-900/40';

export default function UserModelAssignmentPanel() {
  const [items, setItems] = useState<UserModelAssignmentItem[]>([]);
  const [total, setTotal] = useState(0);
  const [options, setOptions] = useState<UserModelOptions | null>(null);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState<UserModelAssignmentItem | null>(null);
  const [batchOpen, setBatchOpen] = useState(false);

  const loadOptions = useCallback(async () => {
    try {
      setOptions(await userModelsApi.options());
    } catch (err) {
      setError(getErrorMessage(err));
    }
  }, []);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await userModelsApi.listUsers(keyword, page, PAGE_SIZE);
      setItems(result.items);
      setTotal(result.total);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [keyword, page]);

  useEffect(() => {
    void loadOptions();
  }, [loadOptions]);

  useEffect(() => {
    void loadUsers();
  }, [loadUsers]);

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const pageIds = useMemo(() => items.map(item => item.userId), [items]);
  const allPageSelected = pageIds.length > 0 && pageIds.every(id => selected.has(id));
  const selectedIds = useMemo(() => [...selected], [selected]);

  const toggleUser = (userId: number) => {
    setSelected(current => {
      const next = new Set(current);
      if (next.has(userId)) {
        next.delete(userId);
      } else {
        next.add(userId);
      }
      return next;
    });
  };

  const togglePage = () => {
    setSelected(current => {
      const next = new Set(current);
      if (allPageSelected) {
        pageIds.forEach(id => next.delete(id));
      } else {
        pageIds.forEach(id => next.add(id));
      }
      return next;
    });
  };

  const handleSearch = (event: FormEvent) => {
    event.preventDefault();
    setPage(0);
    setKeyword(keywordInput.trim());
    setSelected(new Set());
    setNotice('');
  };

  const afterSaved = async (message: string) => {
    setEditing(null);
    setBatchOpen(false);
    setSelected(new Set());
    setNotice(message);
    await loadUsers();
  };

  const handleSaveSingle = async (textProviderId: string | null, visionProviderId: string | null) => {
    if (!editing) return;
    setSaving(true);
    setError('');
    try {
      await userModelsApi.assign(editing.userId, { textProviderId, visionProviderId });
      await afterSaved(`已更新 ${editing.displayName || editing.username} 的模型分配`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const handleSaveBatch = async (textProviderId: string | null, visionProviderId: string | null) => {
    if (selectedIds.length === 0) return;
    setSaving(true);
    setError('');
    try {
      await userModelsApi.assignBatch({ userIds: selectedIds, textProviderId, visionProviderId });
      await afterSaved(`已更新 ${selectedIds.length} 个用户的模型分配`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const handleRestoreDefault = async () => {
    if (selectedIds.length === 0) return;
    if (!window.confirm(`确认把所选 ${selectedIds.length} 个用户恢复为默认模型？`)) return;
    setSaving(true);
    setError('');
    try {
      await userModelsApi.assignBatch({
        userIds: selectedIds,
        textProviderId: null,
        visionProviderId: null,
      });
      await afterSaved(`已将 ${selectedIds.length} 个用户恢复为默认模型`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-800">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold text-slate-900 dark:text-white">模型分配</h2>
          <p className="mt-1 text-xs text-slate-400">
            文字模型与识图模型分别生效；留空表示继承全局默认（免费档）
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={selectedIds.length === 0 || saving}
            onClick={() => setBatchOpen(true)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary-500 px-3 py-1.5 text-sm text-white transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Users className="h-4 w-4" /> 批量分配
            {selectedIds.length > 0 && `（${selectedIds.length}）`}
          </button>
          <button
            type="button"
            disabled={selectedIds.length === 0 || saving}
            onClick={() => void handleRestoreDefault()}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
          >
            <RotateCcw className="h-4 w-4" /> 恢复默认
          </button>
        </div>
      </div>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <form onSubmit={handleSearch} className="flex min-w-0 items-center gap-2">
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={keywordInput}
              onChange={event => setKeywordInput(event.target.value)}
              placeholder="搜索用户名或显示名"
              className="w-60 rounded-xl border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm text-slate-800 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:border-slate-600 dark:bg-slate-900 dark:text-white dark:focus:ring-primary-900/40"
            />
          </div>
          <button
            type="submit"
            className="rounded-xl border border-slate-200 px-3 py-2 text-sm text-slate-600 transition hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
          >
            搜索
          </button>
        </form>
        <span className="text-xs text-slate-400">
          共 {total} 个用户{selectedIds.length > 0 && ` · 已选 ${selectedIds.length} 个`}
        </span>
      </div>

      {notice && (
        <div className="mb-3 rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300">
          {notice}
        </div>
      )}
      {error && (
        <div className="mb-3 flex items-center gap-2 rounded-xl bg-rose-50 px-4 py-2.5 text-sm text-rose-600 dark:bg-rose-900/30 dark:text-rose-300">
          <ShieldAlert className="h-4 w-4" /> {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-7 w-7 animate-spin text-primary-500" />
        </div>
      ) : items.length === 0 ? (
        <p className="py-10 text-center text-sm text-slate-400">没有匹配的用户</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-sm">
            <thead>
              <tr className="border-b border-slate-100 text-left text-slate-400 dark:border-slate-700">
                <th className="w-10 py-3">
                  <input
                    type="checkbox"
                    aria-label="选择当前页全部用户"
                    checked={allPageSelected}
                    onChange={togglePage}
                    className="h-4 w-4 rounded border-slate-300 text-primary-500 focus:ring-primary-400"
                  />
                </th>
                <th className="py-3">用户</th>
                <th>文字模型</th>
                <th>识图模型</th>
                <th>更新时间</th>
                <th className="text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map(item => (
                <tr
                  key={item.userId}
                  className="border-b border-slate-50 transition hover:bg-slate-50/70 dark:border-slate-700/50 dark:hover:bg-slate-900/30"
                >
                  <td className="py-3">
                    <input
                      type="checkbox"
                      aria-label={`选择 ${item.username}`}
                      checked={selected.has(item.userId)}
                      onChange={() => toggleUser(item.userId)}
                      className="h-4 w-4 rounded border-slate-300 text-primary-500 focus:ring-primary-400"
                    />
                  </td>
                  <td className="py-3">
                    <div className="font-medium text-slate-900 dark:text-white">
                      {item.displayName || item.username}
                    </div>
                    <div className="text-xs text-slate-400">{item.username}</div>
                  </td>
                  <td className="text-slate-600 dark:text-slate-300">
                    {effectiveProviderLabel(
                      item.textEffectiveProviderId,
                      item.textEffectiveModel,
                      options?.textProviders ?? [],
                    )}
                    {item.textDefault && (
                      <span className="ml-1 text-xs text-slate-400">（默认）</span>
                    )}
                  </td>
                  <td className="text-slate-600 dark:text-slate-300">
                    {effectiveProviderLabel(
                      item.visionEffectiveProviderId,
                      item.visionEffectiveModel,
                      options?.visionProviders ?? [],
                    )}
                    {item.visionDefault && (
                      <span className="ml-1 text-xs text-slate-400">（默认）</span>
                    )}
                  </td>
                  <td className="text-xs text-slate-400">{formatTime(item.updatedAt)}</td>
                  <td className="text-right">
                    <button
                      type="button"
                      onClick={() => setEditing(item)}
                      className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-primary-600 transition hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/30"
                    >
                      <Pencil className="h-3.5 w-3.5" /> 编辑
                    </button>
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

      {editing && options && (
        <AdminDrawer
          title="编辑模型分配"
          subtitle={`${editing.displayName || editing.username}（${editing.username}）`}
          onClose={() => setEditing(null)}
        >
          <AssignmentForm
            options={options}
            initialText={editing.textProviderId}
            initialVision={editing.visionProviderId}
            saving={saving}
            error={error}
            onCancel={() => setEditing(null)}
            onSubmit={handleSaveSingle}
          />
        </AdminDrawer>
      )}

      {batchOpen && options && (
        <AdminDrawer
          title="批量分配模型"
          subtitle={`将覆盖所选 ${selectedIds.length} 个用户`}
          onClose={() => setBatchOpen(false)}
        >
          <AssignmentForm
            options={options}
            initialText={null}
            initialVision={null}
            saving={saving}
            error={error}
            batch
            onCancel={() => setBatchOpen(false)}
            onSubmit={handleSaveBatch}
          />
        </AdminDrawer>
      )}
    </section>
  );
}

function AssignmentForm({
  options,
  initialText,
  initialVision,
  saving,
  error,
  batch,
  onCancel,
  onSubmit,
}: {
  options: UserModelOptions;
  initialText: string | null;
  initialVision: string | null;
  saving: boolean;
  error: string;
  batch?: boolean;
  onCancel: () => void;
  onSubmit: (textProviderId: string | null, visionProviderId: string | null) => Promise<void>;
}) {
  const [textProviderId, setTextProviderId] = useState(initialText ?? '');
  const [visionProviderId, setVisionProviderId] = useState(initialVision ?? '');

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void onSubmit(textProviderId || null, visionProviderId || null);
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      {batch && (
        <div className="rounded-xl bg-amber-50 px-3 py-2.5 text-xs leading-relaxed text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
          所选用户的文字模型和识图模型都会被这份设置覆盖。留空表示恢复默认（免费档）。
        </div>
      )}

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">文字模型</label>
        <select
          value={textProviderId}
          onChange={event => setTextProviderId(event.target.value)}
          className={fieldClass}
        >
          <option value="">默认（免费档）</option>
          {options.textProviders.map(provider => (
            <option key={provider.id} value={provider.id}>
              {AI_PROVIDER_NAMES[provider.id] ?? provider.id} / {provider.model}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">识图模型</label>
        <select
          value={visionProviderId}
          onChange={event => setVisionProviderId(event.target.value)}
          className={fieldClass}
        >
          <option value="">默认（免费档）</option>
          {options.visionProviders.map(provider => (
            <option key={provider.id} value={provider.id}>
              {AI_PROVIDER_NAMES[provider.id] ?? provider.id} / {provider.model}
            </option>
          ))}
        </select>
        {options.visionProviders.length === 0 && (
          <p className="mt-1.5 text-xs text-amber-600 dark:text-amber-400">
            当前没有已启用的识图模型，请先在系统设置中配置。
          </p>
        )}
      </div>

      {error && (
        <div className="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:bg-rose-900/30 dark:text-rose-300">
          {error}
        </div>
      )}

      <div className="flex justify-end gap-3 pt-2">
        <button
          type="button"
          onClick={onCancel}
          className="rounded-xl border border-slate-200 px-4 py-2 text-sm text-slate-600 transition hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          取消
        </button>
        <button
          type="submit"
          disabled={saving}
          className="inline-flex items-center gap-1.5 rounded-xl bg-primary-500 px-4 py-2 text-sm text-white transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          保存
        </button>
      </div>
    </form>
  );
}
