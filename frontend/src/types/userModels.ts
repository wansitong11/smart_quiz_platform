export interface UserModelAssignmentItem {
  userId: number;
  username: string;
  displayName: string | null;
  /** null 表示继承全局默认（免费档） */
  textProviderId: string | null;
  /** 按当前用户档位解析后的实际生效 Provider 与模型名 */
  textEffectiveProviderId: string | null;
  textEffectiveModel: string | null;
  /** true 表示当前实际使用的是默认路由，而不是用户显式绑定 */
  textDefault: boolean;
  /** null 表示继承全局默认（免费档） */
  visionProviderId: string | null;
  visionEffectiveProviderId: string | null;
  visionEffectiveModel: string | null;
  visionDefault: boolean;
  updatedAt: string | null;
}

export interface UserModelPageResult {
  items: UserModelAssignmentItem[];
  total: number;
  page: number;
  size: number;
}

export interface ProviderOption {
  id: string;
  model: string;
}

export interface UserModelOptions {
  textProviders: ProviderOption[];
  visionProviders: ProviderOption[];
}

export interface UserModelAssignmentRequest {
  textProviderId: string | null;
  visionProviderId: string | null;
}

export interface UserModelBatchAssignRequest {
  userIds: number[];
  textProviderId: string | null;
  visionProviderId: string | null;
}
