/** 当前登录用户（后端 /api/auth/me 返回） */
export interface CurrentUser {
  id: number;
  username: string;
  displayName: string | null;
  email: string | null;
  roles: string[];
  permissions: string[];
}

/** 登录/续期响应：Access Token 在响应体，Refresh Token 在 HttpOnly Cookie 里 */
export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: CurrentUser;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
}

export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface UserItem {
  id: number;
  username: string;
  displayName: string | null;
  email: string | null;
  status: 'ACTIVE' | 'DISABLED';
  roles: string[];
  lastLoginAt: string | null;
  createdAt: string | null;
}

export interface RoleItem {
  id: number;
  code: string;
  name: string;
  description: string | null;
  systemRole: boolean;
  permissions: string[];
}

export interface CreateUserRequest {
  username: string;
  password: string;
  displayName?: string;
  email?: string;
  roles?: string[];
}

export interface UpdateUserRequest {
  displayName?: string;
  email?: string;
  status?: 'ACTIVE' | 'DISABLED';
  roles?: string[];
}

/** 权限点常量，与后端 PermissionCode 保持一致 */
export const PERMISSIONS = {
  PAPER_READ: 'paper:read',
  PAPER_WRITE: 'paper:write',
  PRACTICE_USE: 'practice:use',
  KB_READ: 'kb:read',
  KB_WRITE: 'kb:write',
  KB_MANAGE: 'kb:manage',
  RAG_CHAT: 'rag:chat',
  TRACE_READ: 'trace:read',
  VOICE_USE: 'voice:use',
  SCHEDULE_USE: 'schedule:use',
  PROVIDER_MANAGE: 'provider:manage',
  USER_MANAGE: 'user:manage',
} as const;
