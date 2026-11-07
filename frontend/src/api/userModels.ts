import request from './request';
import type {
  UserModelAssignmentRequest,
  UserModelBatchAssignRequest,
  UserModelOptions,
  UserModelPageResult,
} from '../types/userModels';

export const userModelsApi = {
  listUsers: (keyword: string, page = 0, size = 20) =>
    request.get<UserModelPageResult>('/api/admin/user-models/users', {
      params: { keyword: keyword || undefined, page, size },
    }),

  options: () =>
    request.get<UserModelOptions>('/api/admin/user-models/options'),

  assign: (userId: number, data: UserModelAssignmentRequest) =>
    request.put<void>(`/api/admin/user-models/${userId}`, data),

  assignBatch: (data: UserModelBatchAssignRequest) =>
    request.put<void>('/api/admin/user-models/batch', data),
};
