import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { Loader2, Pencil, RotateCcw, Save, Search, ShieldAlert, Users } from 'lucide-react';
import {
  aiCostApi,
  type EffectiveLimits,
  type QuotaUserItem,
  type UserLimitsUpsertRequest,
  type UserLimitsView,
} from '../../api/aiCost';
import { getErrorMessage } from '../../api/request';
import { featureName } from '../../constants/aiCostLabels';
import AdminDrawer from './AdminDrawer';
import NumberedPagination from './NumberedPagination';

const PAGE_SIZE = 20;

type Scope = string | null;
type QuotaFieldKey =
  | 'rpmLimit'
  | 'tpmLimit'
  | 'concurrencyLimit';
type QuotaForm = Record<QuotaFieldKey, string>;
type EnabledFields = Set<QuotaFieldKey>;

interface QuotaFieldDef {
  key: QuotaFieldKey;
  label: string;
  hint: string;
  step?: string;
}

const QUOTA_FIELDS: QuotaFieldDef[] = [
  { key: 'rpmLimit', label: 'RPM 上限', hint: '每分钟请求数' },
  { key: 'tpmLimit', label: 'TPM 上限', hint: '每分钟 Token 数' },
  { key: 'concurrencyLimit', label: '并发上限', hint: '同时进行的调用数' },
];

const FIELD_CLASS =
  'mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-800 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400 dark:border-slate-600 dark:bg-slate-900 dark:text-white dark:disabled:bg-slate-900/60 dark:focus:ring-primary-900/40';

function emptyForm(): QuotaForm {
  return {
    rpmLimit: '',
    tpmLimit: '',
    concurrencyLimit: '',
  };
}

function emptyView(feature: Scope): UserLimitsView {
  return {
    feature,
    rpmLimit: null,
    tpmLimit: null,
    concurrencyLimit: null,
    effective: {
      rpmLimit: 0,
      tpmLimit: 0,
      concurrencyLimit: 0,
    },
  };
}

function formFromView(view: UserLimitsView | undefined): QuotaForm {
  const source = view ?? emptyView(null);
  return {
    rpmLimit: source.rpmLimit == null ? '' : String(source.rpmLimit),
    tpmLimit: source.tpmLimit == null ? '' : String(source.tpmLimit),
    concurrencyLimit: source.concurrencyLimit == null ? '' : String(source.concurrencyLimit),
  };
}

function parseNumber(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function buildRequest(feature: Scope, form: QuotaForm): UserLimitsUpsertRequest {
  return {
    feature,
    rpmLimit: parseNumber(form.rpmLimit),
    tpmLimit: parseNumber(form.tpmLimit),
    concurrencyLimit: parseNumber(form.concurrencyLimit),
  };
}

function mergeRequest(
  feature: Scope,
  views: UserLimitsView[],
  form: QuotaForm,
  enabled: EnabledFields,
): UserLimitsUpsertRequest {
  const existing = views.find(item => item.feature === feature) ?? emptyView(feature);
  return {
    feature,
    rpmLimit: enabled.has('rpmLimit') ? parseNumber(form.rpmLimit) : existing.rpmLimit,
    tpmLimit: enabled.has('tpmLimit') ? parseNumber(form.tpmLimit) : existing.tpmLimit,
    concurrencyLimit: enabled.has('concurrencyLimit')
      ? parseNumber(form.concurrencyLimit)
      : existing.concurrencyLimit,
  };
}

function validateRequest(request: UserLimitsUpsertRequest): string | null {
  const numbers = [
    request.rpmLimit,
    request.tpmLimit,
    request.concurrencyLimit,
  ].filter((value): value is number => value != null);
  if (numbers.some(value => value < 0)) {
    return '配额不能为负数';
  }
  return null;
}

function scopeLabel(scope: Scope): string {
  return scope == null ? '全局默认' : featureName(scope);
}

function roleLabel(role: string): string {
  return role.replace('ROLE_', '');
}

function formatInteger(value: number): string {
  return Number(value).toLocaleString();
}

function effectiveValue(effective: EffectiveLimits, field: QuotaFieldKey): string {
  if (field === 'tpmLimit') {
    return formatInteger(effective.tpmLimit);
  }
  if (field === 'rpmLimit') {
    return formatInteger(effective.rpmLimit);
  }
  return formatInteger(effective.concurrencyLimit);
}

function QuotaSummary({ effective }: { effective?: EffectiveLimits }) {
  if (!effective) {
    return (
      <span className="text-xs text-slate-400" title="后端未返回实际生效值，请重启后端服务">
        —
      </span>
    );
  }
  return (
    <div className="space-y-1 text-xs text-slate-500 dark:text-slate-400">
      <div className="flex flex-wrap gap-x-3 gap-y-1">
        <span>RPM <b className="font-medium text-slate-700 dark:text-slate-200">{formatInteger(effective.rpmLimit)}</b></span>
        <span>TPM <b className="font-medium text-slate-700 dark:text-slate-200">{formatInteger(effective.tpmLimit)}</b></span>
        <span>并发 <b className="font-medium text-slate-700 dark:text-slate-200">{formatInteger(effective.concurrencyLimit)}</b></span>
      </div>
    </div>
  );
}

export default function UserQuotaPanel() {
  const [users, setUsers] = useState<QuotaUserItem[]>([]);
  const [features, setFeatures] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<QuotaUserItem | null>(null);
  const [batchOpen, setBatchOpen] = useState(false);
  const [restoreOpen, setRestoreOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [quotaUsers, featureList] = await Promise.all([
        aiCostApi.quotaUsers(),
        aiCostApi.features(),
      ]);
      setUsers(quotaUsers);
      setFeatures(featureList);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const filteredUsers = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    if (!normalized) return users;
    return users.filter(user =>
      user.username.toLowerCase().includes(normalized)
      || (user.displayName ?? '').toLowerCase().includes(normalized));
  }, [keyword, users]);

  const totalPages = Math.max(1, Math.ceil(filteredUsers.length / PAGE_SIZE));
  const pageUsers = useMemo(
    () => filteredUsers.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE),
    [filteredUsers, page],
  );
  const pageIds = useMemo(() => pageUsers.map(user => user.id), [pageUsers]);
  const allPageSelected = pageIds.length > 0 && pageIds.every(id => selected.has(id));
  const selectedIds = useMemo(() => [...selected], [selected]);
  const missingEffective = useMemo(() => users.some(user => !user.effective), [users]);

  useEffect(() => {
    setPage(current => Math.max(0, Math.min(current, totalPages - 1)));
  }, [totalPages]);

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

  const handleBatchSubmit = async (
    feature: Scope,
    form: QuotaForm,
    enabled: EnabledFields,
  ) => {
    if (selectedIds.length === 0 || enabled.size === 0) {
      throw new Error('请至少勾选一个要修改的配额字段');
    }
    const ids = [...selectedIds];
    setSaving(true);
    setError('');
    try {
      const viewsByUser = await Promise.all(ids.map(id => aiCostApi.userLimits(id)));
      const requests = ids.map((_, index) =>
        mergeRequest(feature, viewsByUser[index], form, enabled));
      for (const request of requests) {
        const message = validateRequest(request);
        if (message) {
          throw new Error(message);
        }
      }
      await Promise.all(requests.map((request, index) =>
        aiCostApi.upsertUserLimits(ids[index], request)));
      setBatchOpen(false);
      setSelected(new Set());
      setNotice(`已更新 ${ids.length} 个用户的「${scopeLabel(feature)}」配额`);
      await load();
    } catch (err) {
      const message = getErrorMessage(err);
      setError(message);
      throw new Error(message);
    } finally {
      setSaving(false);
    }
  };

  const handleRestore = async (feature: Scope) => {
    if (selectedIds.length === 0) return;
    const ids = [...selectedIds];
    setSaving(true);
    setError('');
    try {
      await Promise.all(ids.map(id =>
        aiCostApi.clearUserLimits(id, feature ?? undefined)));
      setRestoreOpen(false);
      setSelected(new Set());
      setNotice(`已清除 ${ids.length} 个用户的「${scopeLabel(feature)}」覆盖`);
      await load();
    } catch (err) {
      const message = getErrorMessage(err);
      setError(message);
      throw new Error(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-800">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold text-slate-900 dark:text-white">用户配额</h2>
          <p className="mt-1 text-xs text-slate-400">
            列表展示限流实际生效值；成本额度已统一到「额度总池」页。管理员账号不受限制，不在列表中
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            disabled={selectedIds.length === 0 || saving}
            onClick={() => setBatchOpen(true)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary-500 px-3 py-1.5 text-sm text-white transition hover:bg-primary-600 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Users className="h-4 w-4" /> 批量修改
            {selectedIds.length > 0 && `（${selectedIds.length}）`}
          </button>
          <button
            type="button"
            disabled={selectedIds.length === 0 || saving}
            onClick={() => setRestoreOpen(true)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
          >
            <RotateCcw className="h-4 w-4" /> 清除覆盖
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
          共 {filteredUsers.length} 个可配置用户{selectedIds.length > 0 && ` · 已选 ${selectedIds.length} 个`}
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
      {missingEffective && (
        <div className="mb-3 rounded-xl bg-amber-50 px-4 py-2.5 text-sm text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
          后端暂未返回实际生效配额，当前只显示用户列表。请重启后端服务后刷新页面。
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-7 w-7 animate-spin text-primary-500" />
        </div>
      ) : pageUsers.length === 0 ? (
        <p className="py-10 text-center text-sm text-slate-400">没有匹配的用户</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1080px] text-sm">
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
                <th>登录名</th>
                <th>角色</th>
                <th>全局实际生效</th>
                <th>功能覆盖</th>
                <th>状态</th>
                <th className="text-right">操作</th>
              </tr>
            </thead>
            <tbody>
              {pageUsers.map(user => (
                <tr
                  key={user.id}
                  className="border-b border-slate-50 transition hover:bg-slate-50/70 dark:border-slate-700/50 dark:hover:bg-slate-900/30"
                >
                  <td className="py-3">
                    <input
                      type="checkbox"
                      aria-label={`选择 ${user.username}`}
                      checked={selected.has(user.id)}
                      onChange={() => toggleUser(user.id)}
                      className="h-4 w-4 rounded border-slate-300 text-primary-500 focus:ring-primary-400"
                    />
                  </td>
                  <td className="py-3 font-medium text-slate-900 dark:text-white">
                    {user.displayName || user.username}
                  </td>
                  <td className="text-slate-500 dark:text-slate-400">{user.username}</td>
                  <td className="text-slate-500 dark:text-slate-400">
                    {user.roles.map(roleLabel).join('、') || '—'}
                  </td>
                  <td className="py-3">
                    <QuotaSummary effective={user.effective} />
                  </td>
                  <td className="text-xs text-slate-500 dark:text-slate-400">
                    {user.featureOverrideCount > 0
                      ? `${user.featureOverrideCount} 项功能`
                      : '无'}
                  </td>
                  <td>
                    <span className={`rounded-full px-2 py-0.5 text-xs ${
                      user.status === 'ACTIVE'
                        ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300'
                        : 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400'
                    }`}>
                      {user.status === 'ACTIVE' ? '正常' : '已禁用'}
                    </span>
                  </td>
                  <td className="text-right">
                    <button
                      type="button"
                      onClick={() => setEditing(user)}
                      className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-primary-600 transition hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/30"
                    >
                      <Pencil className="h-3.5 w-3.5" /> 配置
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

      {editing && (
        <AdminDrawer
          title="配置用户配额"
          subtitle={`${editing.displayName || editing.username}（${editing.username}）`}
          onClose={() => setEditing(null)}
        >
          <SingleQuotaForm
            user={editing}
            features={features}
            onClose={() => setEditing(null)}
            onSaved={message => {
              setEditing(null);
              setNotice(message);
              void load();
            }}
          />
        </AdminDrawer>
      )}

      {batchOpen && (
        <AdminDrawer
          title="批量修改配额"
          subtitle={`将修改所选 ${selectedIds.length} 个用户`}
          onClose={() => setBatchOpen(false)}
        >
          <BatchQuotaForm
            count={selectedIds.length}
            features={features}
            saving={saving}
            onCancel={() => setBatchOpen(false)}
            onSubmit={handleBatchSubmit}
          />
        </AdminDrawer>
      )}

      {restoreOpen && (
        <AdminDrawer
          title="清除配额覆盖"
          subtitle={`将影响所选 ${selectedIds.length} 个用户`}
          onClose={() => setRestoreOpen(false)}
        >
          <RestoreQuotaForm
            count={selectedIds.length}
            features={features}
            saving={saving}
            onCancel={() => setRestoreOpen(false)}
            onSubmit={handleRestore}
          />
        </AdminDrawer>
      )}
    </section>
  );
}

function SingleQuotaForm({
  user,
  features,
  onClose,
  onSaved,
}: {
  user: QuotaUserItem;
  features: string[];
  onClose: () => void;
  onSaved: (message: string) => void;
}) {
  const [views, setViews] = useState<UserLimitsView[] | null>(null);
  const [scope, setScope] = useState<Scope>(null);
  const [form, setForm] = useState<QuotaForm>(emptyForm());
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const selectedView = useMemo(
    () => views?.find(item => item.feature === scope),
    [scope, views],
  );

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    aiCostApi.userLimits(user.id)
      .then(list => {
        if (!cancelled) {
          setViews(list);
          setLoading(false);
        }
      })
      .catch(err => {
        if (!cancelled) {
          setError(getErrorMessage(err));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [user.id]);

  useEffect(() => {
    if (!views) return;
    setForm(formFromView(selectedView));
  }, [selectedView, views]);

  const handleSave = async (event: FormEvent) => {
    event.preventDefault();
    const request = buildRequest(scope, form);
    const message = validateRequest(request);
    if (message) {
      setError(message);
      return;
    }
    setSaving(true);
    setError('');
    try {
      await aiCostApi.upsertUserLimits(user.id, request);
      onSaved(`已更新 ${user.displayName || user.username} 的「${scopeLabel(scope)}」配额`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const handleClear = async () => {
    if (!window.confirm(`确认清除该用户「${scopeLabel(scope)}」的全部配额覆盖？`)) return;
    setSaving(true);
    setError('');
    try {
      await aiCostApi.clearUserLimits(user.id, scope ?? undefined);
      onSaved(`已清除 ${user.displayName || user.username} 的「${scopeLabel(scope)}」覆盖`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="h-7 w-7 animate-spin text-primary-500" />
      </div>
    );
  }

  return (
    <form onSubmit={handleSave} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">生效范围</label>
        <select
          value={scope ?? ''}
          onChange={event => setScope(event.target.value || null)}
          className={FIELD_CLASS}
        >
          <option value="">全局默认</option>
          {features.map(feature => (
            <option key={feature} value={feature}>{featureName(feature)}</option>
          ))}
        </select>
        <p className="mt-1.5 text-xs text-slate-400">
          功能级配额优先于全局默认；留空表示清除该范围的覆盖。
        </p>
      </div>

      <QuotaInputs form={form} effective={selectedView?.effective} onChange={setForm} />

      {error && (
        <div className="rounded-xl bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:bg-rose-900/30 dark:text-rose-300">
          {error}
        </div>
      )}

      <div className="flex flex-wrap justify-between gap-3 pt-2">
        <button
          type="button"
          onClick={() => void handleClear()}
          disabled={saving}
          className="inline-flex items-center gap-1.5 rounded-xl border border-rose-200 px-3 py-2 text-sm text-rose-500 transition hover:bg-rose-50 disabled:opacity-50 dark:border-rose-900/50 dark:hover:bg-rose-900/20"
        >
          <RotateCcw className="h-4 w-4" /> 清除当前范围
        </button>
        <div className="flex gap-3">
          <button
            type="button"
            onClick={onClose}
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
      </div>
    </form>
  );
}

function BatchQuotaForm({
  count,
  features,
  saving,
  onCancel,
  onSubmit,
}: {
  count: number;
  features: string[];
  saving: boolean;
  onCancel: () => void;
  onSubmit: (feature: Scope, form: QuotaForm, enabled: EnabledFields) => Promise<void>;
}) {
  const [scope, setScope] = useState<Scope>(null);
  const [form, setForm] = useState<QuotaForm>(emptyForm());
  const [enabled, setEnabled] = useState<EnabledFields>(new Set());
  const [error, setError] = useState('');

  const toggleField = (key: QuotaFieldKey) => {
    setEnabled(current => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (enabled.size === 0) {
      setError('请至少勾选一个要修改的配额字段');
      return;
    }
    setError('');
    try {
      await onSubmit(scope, form, enabled);
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div className="rounded-xl bg-amber-50 px-3 py-2.5 text-xs leading-relaxed text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
        将修改所选 {count} 个用户的同一生效范围。只有勾选的字段会被覆盖，未勾选字段保持原值；
        勾选后留空表示清除该项。
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">生效范围</label>
        <select
          value={scope ?? ''}
          onChange={event => setScope(event.target.value || null)}
          className={FIELD_CLASS}
        >
          <option value="">全局默认</option>
          {features.map(feature => (
            <option key={feature} value={feature}>{featureName(feature)}</option>
          ))}
        </select>
      </div>

      <div className="grid grid-cols-1 gap-3">
        {QUOTA_FIELDS.map(field => {
          const checked = enabled.has(field.key);
          return (
            <div key={field.key} className="rounded-xl border border-slate-100 p-3 dark:border-slate-700">
              <label className="flex cursor-pointer items-start gap-2">
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggleField(field.key)}
                  className="mt-1 h-4 w-4 rounded border-slate-300 text-primary-500 focus:ring-primary-400"
                />
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-slate-700 dark:text-slate-200">
                    {field.label}
                  </span>
                  <span className="block text-xs text-slate-400">{field.hint}</span>
                </span>
              </label>
              <input
                type="number"
                min="0"
                step={field.step}
                disabled={!checked}
                value={form[field.key]}
                onChange={event => setForm(current => ({ ...current, [field.key]: event.target.value }))}
                placeholder={checked ? '留空则清除该项' : '保持原值'}
                className={FIELD_CLASS}
              />
            </div>
          );
        })}
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
          应用到 {count} 个用户
        </button>
      </div>
    </form>
  );
}

function RestoreQuotaForm({
  count,
  features,
  saving,
  onCancel,
  onSubmit,
}: {
  count: number;
  features: string[];
  saving: boolean;
  onCancel: () => void;
  onSubmit: (feature: Scope) => Promise<void>;
}) {
  const [scope, setScope] = useState<Scope>(null);
  const [error, setError] = useState('');

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!window.confirm(`确认清除 ${count} 个用户的「${scopeLabel(scope)}」覆盖？`)) return;
    setError('');
    try {
      await onSubmit(scope);
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4">
      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">生效范围</label>
        <select
          value={scope ?? ''}
          onChange={event => setScope(event.target.value || null)}
          className={FIELD_CLASS}
        >
          <option value="">全局默认</option>
          {features.map(feature => (
            <option key={feature} value={feature}>{featureName(feature)}</option>
          ))}
        </select>
        <p className="mt-1.5 text-xs text-slate-400">
          只清除所选范围的覆盖，其他功能或全局配置不受影响。
        </p>
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
          className="inline-flex items-center gap-1.5 rounded-xl bg-rose-500 px-4 py-2 text-sm text-white transition hover:bg-rose-600 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
          清除 {count} 个用户
        </button>
      </div>
    </form>
  );
}

function QuotaInputs({
  form,
  effective,
  onChange,
}: {
  form: QuotaForm;
  effective?: EffectiveLimits;
  onChange: (form: QuotaForm) => void;
}) {
  return (
    <div className="grid grid-cols-1 gap-3">
      {QUOTA_FIELDS.map(field => (
        <div key={field.key}>
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200">
            {field.label}
            <span className="ml-1 text-xs font-normal text-slate-400">{field.hint}</span>
          </label>
          <input
            type="number"
            min="0"
            step={field.step}
            value={form[field.key]}
            onChange={event => onChange({ ...form, [field.key]: event.target.value })}
            placeholder={effective ? `默认 ${effectiveValue(effective, field.key)}` : '留空表示使用默认值'}
            className={FIELD_CLASS}
          />
          {effective && (
            <p className="mt-1 text-[11px] text-slate-400 dark:text-slate-500">
              当前生效：{effectiveValue(effective, field.key)}
              {form[field.key] === '' ? '（未单独配置，使用默认/继承值）' : '（已单独配置）'}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
