import request from './request';

export interface ReportSummary {
  totalCalls: number;
  estimatedCost: number;
  failureRate: number;
  p95LatencyMs: number;
  inputTokens: number;
  outputTokens: number;
  budgetExceeded: number;
}

export interface TrendPoint {
  date: string;
  calls: number;
  cost: number;
  failureRate: number;
}

export interface RankingItem {
  key: string;
  calls: number;
  cost: number;
  inputTokens: number;
  outputTokens: number;
}

export interface UserUsageItem {
  userId: number;
  userName: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  successRate: number;
}

export interface ErrorItem {
  key: string;
  count: number;
}

export interface EventItem {
  id: number;
  occurredAt: string;
  userId: number | null;
  userName: string;
  feature: string;
  provider: string;
  model: string;
  modality: string;
  status: string;
  latencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  estimatedCost: number | null;
}

export interface PageResult<T> {
  items: T[];
  total: number;
}

export interface ModelOption {
  provider: string;
  model: string;
  modality: string;
}

export interface ReportQuery {
  days?: number;
  provider?: string;
  model?: string;
  modality?: string;
}

export interface PriceItem {
  id: number;
  provider: string;
  model: string;
  modality: string;
  inputPricePer1m: number | null;
  outputPricePer1m: number | null;
  cachedPricePer1m: number | null;
  source: 'AUTO' | 'MANUAL';
  effectiveFrom: string;
  effectiveTo: string | null;
}

export interface PriceUpsertRequest {
  id?: number;
  provider: string;
  model: string;
  modality: string;
  inputPricePer1m: number | null;
  outputPricePer1m: number | null;
  cachedPricePer1m: number | null;
}

export interface PriceSyncResult {
  applied: number;
  source: string;
}

export interface UserLimitsView {
  feature: string | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  concurrencyLimit: number | null;
  effective: EffectiveLimits;
}

export interface EffectiveLimits {
  rpmLimit: number;
  tpmLimit: number;
  concurrencyLimit: number;
}

export interface UserLimitsUpsertRequest {
  feature: string | null;
  rpmLimit: number | null;
  tpmLimit: number | null;
  concurrencyLimit: number | null;
}

export interface QuotaUserItem {
  id: number;
  username: string;
  displayName: string | null;
  status: 'ACTIVE' | 'DISABLED';
  roles: string[];
  tier: 'FREE' | 'AUTHORIZED';
  effective: EffectiveLimits;
  featureOverrideCount: number;
}

export interface EstimateResult {
  estimatedCny: number;
  remainingCny: number;
  estimatedRemainingOps: number;
  quotaCny: number;
  usedCny: number;
}

export interface QuotaView {
  tier: 'FREE' | 'AUTHORIZED';
  unlimited: boolean;
  percent: number;
}

export interface QuotaConfigView {
  monthlyTotalCny: number;
  freeUserCount: number;
  perUserCny: number;
}

export type UserTier = 'FREE' | 'AUTHORIZED';

export interface FeatureUsage {
  feature: string;
  dayUsed: number;
  daySoft: number;
  dayHard: number;
  monthUsed: number;
  monthHard: number;
  estimatedRemainingOps: number;
}

function isoRange(days: number): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getTime() - days * 24 * 3600 * 1000);
  return { from: from.toISOString(), to: to.toISOString() };
}

function reportParams(query: ReportQuery) {
  const { from, to } = isoRange(query.days ?? 7);
  return {
    from,
    to,
    provider: query.provider,
    model: query.model,
    modality: query.modality,
  };
}

export const aiCostApi = {
  summary: (query: ReportQuery = {}) =>
    request.get<ReportSummary>('/api/admin/ai-cost/summary', { params: reportParams(query) }),
  trend: (query: ReportQuery = {}) =>
    request.get<TrendPoint[]>('/api/admin/ai-cost/trend', { params: reportParams(query) }),
  ranking: (
    dimension: 'FEATURE' | 'PROVIDER' | 'MODEL' | 'USER',
    query: ReportQuery = {},
  ) =>
    request.get<RankingItem[]>('/api/admin/ai-cost/ranking', {
      params: { ...reportParams(query), dimension },
    }),
  users: (query: ReportQuery & { page?: number; size?: number } = {}) =>
    request.get<PageResult<UserUsageItem>>('/api/admin/ai-cost/users', {
      params: {
        ...reportParams(query),
        page: query.page ?? 0,
        size: query.size ?? 20,
      },
    }),
  errors: (query: ReportQuery = {}) =>
    request.get<ErrorItem[]>('/api/admin/ai-cost/errors', { params: reportParams(query) }),
  events: (query: ReportQuery & {
    feature?: string; status?: string; userId?: number; page?: number; size?: number;
  } = {}) =>
    request.get<PageResult<EventItem>>('/api/admin/ai-cost/events', {
      params: {
        ...reportParams(query),
        feature: query.feature,
        status: query.status,
        userId: query.userId,
        page: query.page ?? 0,
        size: query.size ?? 20,
      },
    }),
  models: (days = 7) => {
    const { from, to } = isoRange(days);
    return request.get<ModelOption[]>('/api/admin/ai-cost/models', { params: { from, to } });
  },
  prices: () => request.get<PriceItem[]>('/api/admin/ai-cost/prices'),
  upsertPrice: (data: PriceUpsertRequest) =>
    request.post<PriceItem>('/api/admin/ai-cost/prices', data),
  deletePrice: (id: number) => request.delete<void>(`/api/admin/ai-cost/prices/${id}`),
  syncPrices: () => request.post<PriceSyncResult>('/api/admin/ai-cost/prices/sync'),
  features: () => request.get<string[]>('/api/admin/ai-cost/features'),
  quotaUsers: () => request.get<QuotaUserItem[]>('/api/admin/ai-cost/quota-users'),
  userLimits: (userId: number) =>
    request.get<UserLimitsView[]>(`/api/admin/ai-cost/user-limits/${userId}`),
  upsertUserLimits: (userId: number, data: UserLimitsUpsertRequest) =>
    request.put<void>(`/api/admin/ai-cost/user-limits/${userId}`, data),
  clearUserLimits: (userId: number, feature?: string | null) =>
    request.delete<void>(`/api/admin/ai-cost/user-limits/${userId}`, {
      params: { feature },
    }),
  quotaConfig: () => request.get<QuotaConfigView>('/api/admin/ai-cost/quota-config'),
  updateQuotaConfig: (monthlyTotalCny: number) =>
    request.put<void>('/api/admin/ai-cost/quota-config', { monthlyTotalCny }),
  updateUserTier: (userId: number, tier: UserTier) =>
    request.put<void>(`/api/admin/ai-cost/user-tiers/${userId}`, { tier }),
};

export const aiUsageApi = {
  summary: () => request.get<FeatureUsage[]>('/api/ai-usage/summary'),
  quota: () => request.get<QuotaView>('/api/ai-usage/quota'),
  estimate: (params: {
    feature?: string; pageCount?: number; questionCount?: number;
    contextTokens?: number; chunkCount?: number; outputTokens?: number;
  } = {}) => request.get<EstimateResult>('/api/ai-usage/estimate', { params }),
};
