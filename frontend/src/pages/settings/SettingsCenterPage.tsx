import { Link, Outlet, useLocation } from 'react-router-dom';
import { CircleUser, SlidersHorizontal, Users, WalletCards } from 'lucide-react';
import { ROUTES } from '../../constants/routes';
import { useAuth } from '../../context/AuthContext';
import { PERMISSIONS } from '../../types/auth';

interface SettingsItem {
  key: string;
  label: string;
  description: string;
  path: string;
  icon: typeof CircleUser;
  permission?: string;
}

const ITEMS: SettingsItem[] = [
  {
    key: 'account',
    label: '用户信息',
    description: '账号、密码与登录',
    path: ROUTES.settingsAccount,
    icon: CircleUser,
  },
  {
    key: 'cost',
    label: '调用成本',
    description: '用量、价格、配额',
    path: ROUTES.settingsCost,
    icon: WalletCards,
    permission: PERMISSIONS.AI_COST_VIEW,
  },
  {
    key: 'users',
    label: '用户管理',
    description: '账号与角色',
    path: ROUTES.settingsUsers,
    icon: Users,
    permission: PERMISSIONS.USER_MANAGE,
  },
  {
    key: 'system',
    label: '系统设置',
    description: '文字、向量与识图模型',
    path: ROUTES.settingsSystem,
    icon: SlidersHorizontal,
    permission: PERMISSIONS.PROVIDER_MANAGE,
  },
];

export default function SettingsCenterPage() {
  const location = useLocation();
  const { hasPermission } = useAuth();
  const visibleItems = ITEMS.filter(item => !item.permission || hasPermission(item.permission));

  return (
    <div className="max-w-7xl mx-auto">
      <div className="flex flex-col lg:flex-row gap-6">
        <aside className="lg:w-56 shrink-0">
          <div className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 p-2">
            <p className="px-3 py-2 text-xs font-semibold uppercase tracking-wider text-slate-400 dark:text-slate-500">
              设置
            </p>
            <nav className="space-y-1" aria-label="设置菜单">
              {visibleItems.map((item) => {
                const active = location.pathname === item.path
                  || (item.key === 'cost' && location.pathname.startsWith(ROUTES.settingsCost));
                const Icon = item.icon;
                return (
                  <Link
                    key={item.key}
                    to={item.path}
                    className={`flex items-start gap-3 rounded-xl px-3 py-2.5 transition-colors ${
                      active
                        ? 'bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400'
                        : 'text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-700/60'
                    }`}
                  >
                    <Icon className="mt-0.5 w-4 h-4 shrink-0" />
                    <span className="min-w-0">
                      <span className={`block text-sm ${active ? 'font-semibold' : 'font-medium'}`}>
                        {item.label}
                      </span>
                      <span className="block text-xs text-slate-400 dark:text-slate-500 truncate">
                        {item.description}
                      </span>
                    </span>
                  </Link>
                );
              })}
            </nav>
          </div>
        </aside>
        <section className="flex-1 min-w-0">
          <Outlet />
        </section>
      </div>
    </div>
  );
}
