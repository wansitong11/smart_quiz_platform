import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { KeyRound, Loader2, Plus, ShieldAlert, Trash2, UserX, Users, Zap } from 'lucide-react';
import { userAdminApi } from '../api/auth';
import { getErrorMessage } from '../api/request';
import { useAuth } from '../context/AuthContext';
import type { RoleItem, UserItem } from '../types/auth';
import { PERMISSIONS } from '../types/auth';

type DialogState =
  | { type: 'none' }
  | { type: 'create' }
  | { type: 'reset'; user: UserItem };

export default function UserAdminPage() {
  const { user: currentUser, hasPermission } = useAuth();
  const canManage = hasPermission(PERMISSIONS.USER_MANAGE);

  const [users, setUsers] = useState<UserItem[]>([]);
  const [roles, setRoles] = useState<RoleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [dialog, setDialog] = useState<DialogState>({ type: 'none' });

  const load = useCallback(async () => {
    if (!canManage) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [userList, roleList] = await Promise.all([
        userAdminApi.listUsers(),
        userAdminApi.listRoles(),
      ]);
      setUsers(userList);
      setRoles(roleList);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [canManage]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleToggleStatus = async (target: UserItem) => {
    const nextStatus = target.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    if (nextStatus === 'DISABLED' && !window.confirm(`确认禁用 ${target.username}？该账号会立即被强制下线。`)) {
      return;
    }
    try {
      await userAdminApi.updateUser(target.id, { status: nextStatus });
      await load();
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleKick = async (target: UserItem) => {
    if (!window.confirm(`确认把 ${target.username} 踢下线？`)) {
      return;
    }
    try {
      await userAdminApi.kickOut(target.id);
      await load();
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleDelete = async (target: UserItem) => {
    if (!window.confirm(`确认删除 ${target.username}？该操作不可恢复。`)) {
      return;
    }
    try {
      await userAdminApi.deleteUser(target.id);
      await load();
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  if (!canManage) {
    return (
      <div className="max-w-3xl mx-auto mt-20 text-center">
        <ShieldAlert className="w-10 h-10 mx-auto text-amber-500" />
        <p className="mt-3 text-slate-600 dark:text-slate-300">当前账号没有用户管理权限</p>
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-indigo-500 flex items-center justify-center">
            <Users className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="text-lg font-bold text-slate-800 dark:text-white">账号管理</h1>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              角色决定功能权限；禁用或改角色会立刻让该账号的登录失效
            </p>
          </div>
        </div>
        <button
          onClick={() => setDialog({ type: 'create' })}
          className="flex items-center gap-2 px-4 py-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white text-sm font-medium hover:opacity-95 transition"
        >
          <Plus className="w-4 h-4" />
          新建账号
        </button>
      </div>

      {error && (
        <div className="mb-4 px-4 py-3 rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
          {error}
        </div>
      )}

      <div className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 overflow-hidden">
        {loading ? (
          <div className="p-12 flex justify-center">
            <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-slate-50 dark:bg-slate-900/50 text-slate-500 dark:text-slate-400">
              <tr>
                <th className="text-left font-medium px-5 py-3">账号</th>
                <th className="text-left font-medium px-5 py-3">角色</th>
                <th className="text-left font-medium px-5 py-3">状态</th>
                <th className="text-left font-medium px-5 py-3">最后登录</th>
                <th className="text-right font-medium px-5 py-3">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
              {users.map((item) => (
                <tr key={item.id} className="hover:bg-slate-50 dark:hover:bg-slate-900/40 transition">
                  <td className="px-5 py-3">
                    <div className="font-medium text-slate-800 dark:text-white">
                      {item.displayName || item.username}
                      {currentUser?.id === item.id && (
                        <span className="ml-2 text-xs text-primary-500">（我）</span>
                      )}
                    </div>
                    <div className="text-xs text-slate-400">{item.username}</div>
                  </td>
                  <td className="px-5 py-3 text-slate-600 dark:text-slate-300">
                    {item.roles.map((role) => role.replace('ROLE_', '')).join('、') || '-'}
                  </td>
                  <td className="px-5 py-3">
                    <span
                      className={`px-2 py-0.5 rounded-full text-xs ${
                        item.status === 'ACTIVE'
                          ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300'
                          : 'bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400'
                      }`}
                    >
                      {item.status === 'ACTIVE' ? '正常' : '已禁用'}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-500">
                    {item.lastLoginAt ? item.lastLoginAt.replace('T', ' ').slice(0, 16) : '从未登录'}
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex items-center justify-end gap-1.5">
                      <IconAction title="重置密码" onClick={() => setDialog({ type: 'reset', user: item })}>
                        <KeyRound className="w-4 h-4" />
                      </IconAction>
                      <IconAction title="踢下线" onClick={() => void handleKick(item)}>
                        <Zap className="w-4 h-4" />
                      </IconAction>
                      <IconAction
                        title={item.status === 'ACTIVE' ? '禁用' : '启用'}
                        onClick={() => void handleToggleStatus(item)}
                        disabled={currentUser?.id === item.id}
                      >
                        <UserX className="w-4 h-4" />
                      </IconAction>
                      <IconAction
                        title="删除"
                        danger
                        onClick={() => void handleDelete(item)}
                        disabled={currentUser?.id === item.id}
                      >
                        <Trash2 className="w-4 h-4" />
                      </IconAction>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <p className="mt-4 text-xs text-slate-400 dark:text-slate-500">
        可用角色：{roles.map((role) => `${role.code}（${role.description || role.name}）`).join('；')}
      </p>

      {dialog.type === 'create' && (
        <CreateUserDialog
          roles={roles}
          onClose={() => setDialog({ type: 'none' })}
          onCreated={async () => {
            setDialog({ type: 'none' });
            await load();
          }}
        />
      )}

      {dialog.type === 'reset' && (
        <ResetPasswordDialog
          user={dialog.user}
          onClose={() => setDialog({ type: 'none' })}
          onDone={async () => {
            setDialog({ type: 'none' });
            await load();
          }}
        />
      )}
    </div>
  );
}

function IconAction({
  title,
  onClick,
  disabled,
  danger,
  children,
}: {
  title: string;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      disabled={disabled}
      className={`p-2 rounded-lg transition disabled:opacity-30 disabled:cursor-not-allowed ${
        danger
          ? 'text-rose-500 hover:bg-rose-50 dark:hover:bg-rose-900/30'
          : 'text-slate-500 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-700'
      }`}
    >
      {children}
    </button>
  );
}

function ModalShell({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/40 backdrop-blur-sm px-4">
      <div className="w-full max-w-md bg-white dark:bg-slate-800 rounded-2xl shadow-xl border border-slate-100 dark:border-slate-700 p-6">
        <h2 className="text-base font-semibold text-slate-800 dark:text-white mb-5">{title}</h2>
        {children}
        <button type="button" onClick={onClose} className="hidden" aria-hidden />
      </div>
    </div>
  );
}

const inputClass =
  'w-full px-3 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-white outline-none focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/40 transition';

function CreateUserDialog({
  roles,
  onClose,
  onCreated,
}: {
  roles: RoleItem[];
  onClose: () => void;
  onCreated: () => Promise<void>;
}) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [roleCode, setRoleCode] = useState('ROLE_USER');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await userAdminApi.createUser({
        username: username.trim(),
        password,
        displayName: displayName.trim() || undefined,
        roles: [roleCode],
      });
      await onCreated();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title="新建账号" onClose={onClose}>
      <form onSubmit={submit}>
        <div className="mb-4">
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">用户名</label>
          <input className={inputClass} value={username} onChange={(e) => setUsername(e.target.value)}
            placeholder="3~64 位字母数字，可含 _ . -" />
        </div>
        <div className="mb-4">
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">初始密码</label>
          <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            placeholder="至少 8 位" />
        </div>
        <div className="mb-4">
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">显示名称</label>
          <input className={inputClass} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div className="mb-5">
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">角色</label>
          <select className={inputClass} value={roleCode} onChange={(e) => setRoleCode(e.target.value)}>
            {roles.map((role) => (
              <option key={role.code} value={role.code}>
                {role.name}（{role.code}）
              </option>
            ))}
          </select>
        </div>
        {error && <div className="mb-4 text-sm text-rose-600 dark:text-rose-300">{error}</div>}
        <div className="flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300">
            取消
          </button>
          <button type="submit" disabled={submitting || !username.trim() || password.length < 8}
            className="flex-1 py-2.5 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white disabled:opacity-50">
            {submitting ? '创建中…' : '创建'}
          </button>
        </div>
      </form>
    </ModalShell>
  );
}

function ResetPasswordDialog({
  user,
  onClose,
  onDone,
}: {
  user: UserItem;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await userAdminApi.resetPassword(user.id, password);
      await onDone();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <ModalShell title={`重置 ${user.username} 的密码`} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="mb-4">
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">新密码</label>
          <input className={inputClass} type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            placeholder="至少 8 位" />
        </div>
        <p className="mb-4 text-xs text-slate-400 dark:text-slate-500">
          重置后该账号的登录会立即失效，需要用新密码重新登录。
        </p>
        {error && <div className="mb-4 text-sm text-rose-600 dark:text-rose-300">{error}</div>}
        <div className="flex gap-3">
          <button type="button" onClick={onClose}
            className="flex-1 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300">
            取消
          </button>
          <button type="submit" disabled={submitting || password.length < 8}
            className="flex-1 py-2.5 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white disabled:opacity-50">
            {submitting ? '提交中…' : '确认重置'}
          </button>
        </div>
      </form>
    </ModalShell>
  );
}
