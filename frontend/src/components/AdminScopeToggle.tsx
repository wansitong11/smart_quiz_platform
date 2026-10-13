import { Users } from 'lucide-react';
import type { DataScopeParam } from '../api/scope';

interface AdminScopeToggleProps {
  /** 当前视图：'all' = 查看全部用户数据；undefined = 只看我的 */
  scope: DataScopeParam;
  onChange: (next: DataScopeParam) => void;
}

const OPTIONS: { value: DataScopeParam; label: string; hint: string }[] = [
  { value: undefined, label: '只看我的', hint: '只看我自己上传 / 产生的数据' },
  { value: 'all', label: '查看全部', hint: '查看全部用户的数据（仅管理员可用）' },
];

/**
 * 管理员专用的数据范围开关（多用户隔离）。
 *
 * 只有管理员渲染这个组件；普通用户看不到，即使手动带上 `scope=all` 后端也只会返回本人的数据。
 */
export default function AdminScopeToggle({ scope, onChange }: AdminScopeToggleProps) {
  return (
    <div
      className="inline-flex items-center gap-1 p-0.5 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800"
      role="group"
      aria-label="数据范围"
    >
      <Users className="w-4 h-4 mx-2 text-slate-400" />
      {OPTIONS.map(option => {
        const active = scope === option.value;
        return (
          <button
            key={option.label}
            type="button"
            onClick={() => onChange(option.value)}
            title={option.hint}
            aria-pressed={active}
            className={`px-3 py-1.5 text-sm rounded-md transition-colors ${
              active
                ? option.value === 'all'
                  ? 'bg-amber-500 text-white'
                  : 'bg-primary-500 text-white'
                : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700'
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
