import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ArrowLeft, GraduationCap, Loader2, LockKeyhole, User, UserPlus } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { getErrorMessage } from '../api/request';
import authApi from '../api/auth';

interface LocationState {
  from?: string;
}

type Mode = 'login' | 'register';

const inputClass =
  'w-full pl-10 pr-3 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-white outline-none focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/40 transition';

export default function LoginPage() {
  const { login, user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [mode, setMode] = useState<Mode>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const redirectTo = (location.state as LocationState | null)?.from || '/';

  if (user) {
    return <Navigate to={redirectTo} replace />;
  }

  const switchMode = (next: Mode) => {
    setMode(next);
    setError('');
    setNotice('');
    setPassword('');
    setConfirmPassword('');
  };

  const handleLogin = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setError('');
    setSubmitting(true);
    try {
      await login({ username: username.trim(), password });
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleRegister = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setError('');
    setNotice('');
    if (password !== confirmPassword) {
      setError('两次输入的密码不一致，请重新确认');
      return;
    }
    setSubmitting(true);
    try {
      const result = await authApi.register({ username: username.trim(), password });
      // 注册成功后回到登录界面，带出刚注册的用户名，直接接着登录
      setMode('login');
      setPassword('');
      setUsername(result.username);
      setNotice('注册成功，请使用该账号登录');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const isRegister = mode === 'register';
  const passwordMismatch = isRegister && confirmPassword.length > 0 && password !== confirmPassword;
  const canSubmit = Boolean(username.trim())
    && password.length >= (isRegister ? 8 : 1)
    && (!isRegister || (confirmPassword.length > 0 && password === confirmPassword));

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800 px-4">
      <div className="w-full max-w-md">
        <div className="flex flex-col items-center mb-8">
          <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-primary-500 to-indigo-500 flex items-center justify-center shadow-lg shadow-primary-500/20">
            <GraduationCap className="w-7 h-7 text-white" />
          </div>
          <h1 className="mt-4 text-xl font-bold text-slate-800 dark:text-white">智能刷题平台</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            {isRegister ? '创建一个新账号' : '请登录后使用'}
          </p>
        </div>

        <form
          onSubmit={isRegister ? handleRegister : handleLogin}
          className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-8"
        >
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">
            用户名
          </label>
          <div className="relative mb-4">
            <User className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              autoFocus
              className={inputClass}
              placeholder={isRegister ? '3~64 位字母、数字、_ . -' : '请输入用户名'}
            />
          </div>

          <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">
            密码
          </label>
          <div className={`relative ${isRegister ? 'mb-4' : 'mb-5'}`}>
            <LockKeyhole className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete={isRegister ? 'new-password' : 'current-password'}
              className={inputClass}
              placeholder={isRegister ? '至少 8 位' : '请输入密码'}
            />
          </div>

          {/* 注册要输两遍密码：本地比对一致才提交 */}
          {isRegister && (
            <>
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">
                确认密码
              </label>
              <div className="relative mb-5">
                <LockKeyhole className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  aria-invalid={passwordMismatch}
                  className={`${inputClass} ${
                    passwordMismatch ? 'border-rose-300 focus:border-rose-400 focus:ring-rose-100' : ''
                  }`}
                  placeholder="请再输入一遍密码"
                />
              </div>
              {passwordMismatch && (
                <p className="-mt-3 mb-4 text-xs text-rose-500">两次输入的密码不一致</p>
              )}
            </>
          )}

          {notice && (
            <div className="mb-4 px-3 py-2 rounded-lg bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-300 text-sm">
              {notice}
            </div>
          )}

          {error && (
            <div className="mb-4 px-3 py-2 rounded-lg bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={submitting || !canSubmit}
            className="w-full py-2.5 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white font-medium shadow-sm hover:opacity-95 disabled:opacity-50 disabled:cursor-not-allowed transition flex items-center justify-center gap-2"
          >
            {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
            {isRegister
              ? (submitting ? '注册中…' : '注册')
              : (submitting ? '登录中…' : '登录')}
          </button>

          {/* 注册按钮放在登录按钮下方；注册页里换成返回登录 */}
          {isRegister ? (
            <button
              type="button"
              onClick={() => switchMode('login')}
              className="mt-3 w-full py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700/50 transition flex items-center justify-center gap-2"
            >
              <ArrowLeft className="w-4 h-4" />
              返回登录
            </button>
          ) : (
            <button
              type="button"
              onClick={() => switchMode('register')}
              className="mt-3 w-full py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700/50 transition flex items-center justify-center gap-2"
            >
              <UserPlus className="w-4 h-4" />
              注册
            </button>
          )}
        </form>

        <p className="mt-6 text-center text-xs text-slate-400 dark:text-slate-500">
          {isRegister ? '注册后需要登录才能使用' : '忘记密码请联系管理员重置'}
        </p>
      </div>
    </div>
  );
}
