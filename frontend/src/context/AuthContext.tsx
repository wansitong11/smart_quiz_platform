import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import authApi from '../api/auth';
import type { CurrentUser, LoginRequest } from '../types/auth';
import { authToken, onSessionExpired, onUserRefreshed } from '../utils/authToken';

interface AuthContextValue {
  user: CurrentUser | null;
  /** 启动时的静默续期尚未结束，路由守卫需要等它完成再决定是否跳登录页 */
  initializing: boolean;
  login: (data: LoginRequest) => Promise<void>;
  logout: () => Promise<void>;
  reloadUser: () => Promise<void>;
  hasPermission: (permission: string) => boolean;
  hasRole: (role: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [initializing, setInitializing] = useState(true);

  // 后端可能通过 /api/auth/refresh 返回最新的用户信息（角色/权限变更后）
  useEffect(() => onUserRefreshed((next) => setUser(next as CurrentUser)), []);
  useEffect(() => onSessionExpired(() => setUser(null)), []);

  // 页面刷新后内存里的 Access Token 没了，用 Refresh Token Cookie 静默换一次
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await authApi.refresh();
        if (cancelled) {
          return;
        }
        authToken.set(data.accessToken);
        setUser(data.user);
      } catch {
        if (!cancelled) {
          authToken.clear();
          setUser(null);
        }
      } finally {
        if (!cancelled) {
          setInitializing(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (data: LoginRequest) => {
    const result = await authApi.login(data);
    authToken.set(result.accessToken);
    setUser(result.user);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      authToken.clear();
      setUser(null);
    }
  }, []);

  const reloadUser = useCallback(async () => {
    const current = await authApi.me();
    setUser(current);
  }, []);

  const hasPermission = useCallback(
    (permission: string) => Boolean(user?.permissions?.includes(permission)),
    [user],
  );

  const hasRole = useCallback((role: string) => Boolean(user?.roles?.includes(role)), [user]);

  const value = useMemo<AuthContextValue>(
    () => ({ user, initializing, login, logout, reloadUser, hasPermission, hasRole }),
    [user, initializing, login, logout, reloadUser, hasPermission, hasRole],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return context;
}
