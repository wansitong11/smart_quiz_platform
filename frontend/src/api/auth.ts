import request from './request';
import type {
  ChangePasswordRequest,
  CreateUserRequest,
  CurrentUser,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  RoleItem,
  UpdateUserRequest,
  UserItem,
} from '../types/auth';

export const authApi = {
  login(data: LoginRequest): Promise<LoginResponse> {
    return request.post<LoginResponse>('/api/auth/login', data, {
      skipAuth: true,
      skipAuthRefresh: true,
    });
  },

  /** 自助注册：只创建账号，不直接发令牌，注册后回到登录界面继续登录 */
  register(data: RegisterRequest): Promise<{ username: string }> {
    return request.post<{ username: string }>('/api/auth/register', data, {
      skipAuth: true,
      skipAuthRefresh: true,
    });
  },

  /** 用 HttpOnly Cookie 里的 Refresh Token 换新的 Access Token */
  refresh(): Promise<LoginResponse> {
    return request.post<LoginResponse>('/api/auth/refresh', undefined, {
      skipAuth: true,
      skipAuthRefresh: true,
    });
  },

  logout(): Promise<void> {
    return request.post<void>('/api/auth/logout', undefined, { skipAuthRefresh: true });
  },

  me(): Promise<CurrentUser> {
    return request.get<CurrentUser>('/api/auth/me');
  },

  changePassword(data: ChangePasswordRequest): Promise<void> {
    return request.post<void>('/api/auth/password', data, { skipAuthRefresh: true });
  },
};

export const userAdminApi = {
  listUsers(): Promise<UserItem[]> {
    return request.get<UserItem[]>('/api/admin/users');
  },

  listRoles(): Promise<RoleItem[]> {
    return request.get<RoleItem[]>('/api/admin/roles');
  },

  createUser(data: CreateUserRequest): Promise<UserItem> {
    return request.post<UserItem>('/api/admin/users', data);
  },

  updateUser(id: number, data: UpdateUserRequest): Promise<UserItem> {
    return request.put<UserItem>(`/api/admin/users/${id}`, data);
  },

  deleteUser(id: number): Promise<void> {
    return request.delete<void>(`/api/admin/users/${id}`);
  },

  resetPassword(id: number, newPassword: string): Promise<void> {
    return request.post<void>(`/api/admin/users/${id}/password`, { newPassword });
  },

  kickOut(id: number): Promise<void> {
    return request.post<void>(`/api/admin/users/${id}/kick`);
  },
};

export default authApi;
