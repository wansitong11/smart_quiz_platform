import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { KeyRound, LogOut, Mail, Shield, User } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { ROUTES } from '../../constants/routes';
import { authToken, emitSessionExpired } from '../../utils/authToken';
import ChangePasswordModal from '../../components/ChangePasswordModal';

function roleLabel(role: string): string {
  return role.replace('ROLE_', '');
}

export default function SettingsAccountSection() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [showChangePassword, setShowChangePassword] = useState(false);

  if (!user) {
    return (
      <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-10 text-center text-sm text-slate-400">
        正在加载账号信息…
      </div>
    );
  }

  const displayName = user.displayName || user.username;
  const initial = displayName.charAt(0).toUpperCase();

  const handleLogout = async () => {
    if (!window.confirm('确认退出登录？')) return;
    await logout();
    navigate(ROUTES.login, { replace: true });
  };

  const handlePasswordChanged = () => {
    setShowChangePassword(false);
    authToken.clear();
    emitSessionExpired();
    navigate(ROUTES.login, { replace: true });
  };

  return (
    <div className="max-w-3xl">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">用户信息</h1>
        <p className="mt-1 text-sm text-slate-400">查看账号信息，修改密码或退出登录</p>
      </div>

      <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-6">
        <div className="flex items-center gap-4">
          <div className="grid h-14 w-14 shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-primary-500 to-indigo-500 text-xl font-semibold text-white">
            {initial}
          </div>
          <div className="min-w-0">
            <div className="truncate text-lg font-semibold text-slate-900 dark:text-white">
              {displayName}
            </div>
            <div className="truncate text-sm text-slate-400">{user.username}</div>
          </div>
        </div>

        <dl className="mt-6 divide-y divide-slate-100 dark:divide-slate-700">
          <div className="grid grid-cols-[88px_1fr] gap-4 py-3 text-sm">
            <dt className="flex items-center gap-2 text-slate-400"><User className="h-4 w-4" />用户名</dt>
            <dd className="text-slate-800 dark:text-slate-100">{user.username}</dd>
          </div>
          <div className="grid grid-cols-[88px_1fr] gap-4 py-3 text-sm">
            <dt className="flex items-center gap-2 text-slate-400"><User className="h-4 w-4" />显示名</dt>
            <dd className="text-slate-800 dark:text-slate-100">{user.displayName || '—'}</dd>
          </div>
          <div className="grid grid-cols-[88px_1fr] gap-4 py-3 text-sm">
            <dt className="flex items-center gap-2 text-slate-400"><Mail className="h-4 w-4" />邮箱</dt>
            <dd className="truncate text-slate-800 dark:text-slate-100">{user.email || '—'}</dd>
          </div>
          <div className="grid grid-cols-[88px_1fr] gap-4 py-3 text-sm">
            <dt className="flex items-center gap-2 text-slate-400"><Shield className="h-4 w-4" />角色</dt>
            <dd className="flex flex-wrap gap-1.5">
              {user.roles.length === 0
                ? '—'
                : user.roles.map(role => (
                  <span
                    key={role}
                    className="rounded-full bg-primary-50 px-2 py-0.5 text-xs text-primary-600 dark:bg-primary-900/30 dark:text-primary-300"
                  >
                    {roleLabel(role)}
                  </span>
                ))}
            </dd>
          </div>
        </dl>

        <div className="mt-6 flex flex-wrap gap-3">
          <button
            type="button"
            onClick={() => setShowChangePassword(true)}
            className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 px-4 py-2 text-sm font-medium text-white transition hover:opacity-95"
          >
            <KeyRound className="h-4 w-4" /> 修改密码
          </button>
          <button
            type="button"
            onClick={() => void handleLogout()}
            className="inline-flex items-center gap-2 rounded-xl border border-rose-200 px-4 py-2 text-sm font-medium text-rose-500 transition hover:bg-rose-50 dark:border-rose-900/50 dark:hover:bg-rose-900/20"
          >
            <LogOut className="h-4 w-4" /> 退出登录
          </button>
        </div>
      </div>

      {showChangePassword && (
        <ChangePasswordModal
          onClose={() => setShowChangePassword(false)}
          onChanged={handlePasswordChanged}
        />
      )}
    </div>
  );
}
