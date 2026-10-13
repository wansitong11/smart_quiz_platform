/**
 * Access Token 只放内存，不落 localStorage：
 * 页面刷新会丢失，由 AuthProvider 启动时用 HttpOnly Cookie 里的 Refresh Token 静默换回。
 */
let accessToken: string | null = null;

type TokenListener = (token: string | null) => void;
type UserListener = (user: unknown) => void;
type VoidListener = () => void;

const tokenListeners = new Set<TokenListener>();
const userListeners = new Set<UserListener>();
const expiredListeners = new Set<VoidListener>();

export const authToken = {
  get(): string | null {
    return accessToken;
  },
  set(token: string | null): void {
    accessToken = token;
    tokenListeners.forEach((listener) => listener(token));
  },
  clear(): void {
    authToken.set(null);
  },
  subscribe(listener: TokenListener): () => void {
    tokenListeners.add(listener);
    return () => tokenListeners.delete(listener);
  },
};

/** 续期成功时把最新的用户信息广播出去（角色/权限可能已被管理员调整） */
export function emitUserRefreshed(user: unknown): void {
  userListeners.forEach((listener) => listener(user));
}

export function onUserRefreshed(listener: UserListener): () => void {
  userListeners.add(listener);
  return () => userListeners.delete(listener);
}

/** 续期失败：会话彻底失效，需要有办法回到登录页 */
export function emitSessionExpired(): void {
  expiredListeners.forEach((listener) => listener());
}

export function onSessionExpired(listener: VoidListener): () => void {
  expiredListeners.add(listener);
  return () => expiredListeners.delete(listener);
}
