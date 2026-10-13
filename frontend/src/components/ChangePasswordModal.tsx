import { useState, type FormEvent } from 'react';
import { Loader2, X } from 'lucide-react';
import authApi from '../api/auth';
import { getErrorMessage } from '../api/request';

interface ChangePasswordModalProps {
  onClose: () => void;
  /** 改密成功后后端会清掉刷新令牌，前端需要退出到登录页 */
  onChanged: () => void;
}

export default function ChangePasswordModal({ onClose, onChanged }: ChangePasswordModalProps) {
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError('');

    if (newPassword.length < 8) {
      setError('新密码至少 8 位');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('两次输入的新密码不一致');
      return;
    }

    setSubmitting(true);
    try {
      await authApi.changePassword({ oldPassword, newPassword });
      onChanged();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/40 backdrop-blur-sm px-4">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm bg-white dark:bg-slate-800 rounded-2xl shadow-xl border border-slate-100 dark:border-slate-700 p-6"
      >
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-base font-semibold text-slate-800 dark:text-white">修改密码</h2>
          <button
            type="button"
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 transition"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {[
          { label: '原密码', value: oldPassword, setter: setOldPassword },
          { label: '新密码（至少 8 位）', value: newPassword, setter: setNewPassword },
          { label: '确认新密码', value: confirmPassword, setter: setConfirmPassword },
        ].map((field) => (
          <div key={field.label} className="mb-4">
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1.5">
              {field.label}
            </label>
            <input
              type="password"
              value={field.value}
              onChange={(event) => field.setter(event.target.value)}
              autoComplete="off"
              className="w-full px-3 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-white outline-none focus:border-primary-400 focus:ring-2 focus:ring-primary-100 dark:focus:ring-primary-900/40 transition"
            />
          </div>
        ))}

        {error && (
          <div className="mb-4 px-3 py-2 rounded-lg bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
            {error}
          </div>
        )}

        <p className="mb-4 text-xs text-slate-400 dark:text-slate-500">
          修改成功后其它设备的登录会立即失效，需要重新登录。
        </p>

        <button
          type="submit"
          disabled={submitting || !oldPassword || !newPassword || !confirmPassword}
          className="w-full py-2.5 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white font-medium disabled:opacity-50 disabled:cursor-not-allowed transition flex items-center justify-center gap-2"
        >
          {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
          确认修改
        </button>
      </form>
    </div>
  );
}
