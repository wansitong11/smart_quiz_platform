import axios, {
  AxiosInstance,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios';
import { authToken, emitSessionExpired, emitUserRefreshed } from '../utils/authToken';
import type { LoginResponse } from '../types/auth';

declare module 'axios' {
  interface AxiosRequestConfig {
    skipResultTransform?: boolean;
    /** 登录/续期等接口不携带 Authorization */
    skipAuth?: boolean;
    /** 不做静默续期（续期接口自身、退出登录、改密码） */
    skipAuthRefresh?: boolean;
    /** 内部标记：本次请求是否已经因令牌过期重试过 */
    _retriedAfterRefresh?: boolean;
  }
}

/**
 * 后端统一响应结构
 */
export interface Result<T = unknown> {
  code: number;
  message: string;
  data: T;
}

const SUCCESS_CODE = 200;
const RESULT_BLOB_PARSE_LIMIT = 64 * 1024;
/** 与后端 ErrorCode 中的认证类错误码保持一致 */
const AUTH_EXPIRED_CODES = new Set([12002, 12003, 12004]);

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

const instance: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
  withCredentials: true,
});

/**
 * 续期专用实例：不挂任何拦截器，避免与主实例的 401 处理互相递归。
 * Refresh Token 在 HttpOnly Cookie 里，靠 withCredentials 自动带上。
 */
const refreshClient: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  withCredentials: true,
});

let refreshInFlight: Promise<string | null> | null = null;

/**
 * 单飞（single-flight）续期：并发的多个 401 只会触发一次 /api/auth/refresh，
 * 其余请求复用同一个 Promise，避免刷新令牌被轮换多次而互相作废。
 */
function requestRefresh(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = refreshClient
      .post<Result<LoginResponse>>('/api/auth/refresh')
      .then((response) => {
        const payload = response.data;
        if (!payload || payload.code !== SUCCESS_CODE || !payload.data) {
          return null;
        }
        authToken.set(payload.data.accessToken);
        emitUserRefreshed(payload.data.user);
        return payload.data.accessToken;
      })
      .catch(() => null)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

/**
 * 供非 axios 的请求复用（SSE / fetch）：拿到续期后的 Access Token。
 * 内部同样是单飞，多个并发请求只会触发一次 /api/auth/refresh。
 */
export function refreshAccessToken(): Promise<string | null> {
  return requestRefresh();
}

/** 判断业务码是否为"登录已过期/无效" */
export function isAuthExpiredCode(code: unknown): boolean {
  return typeof code === 'number' && AUTH_EXPIRED_CODES.has(code);
}

/**
 * 令牌过期后的统一处理：续期成功则带上新令牌重放原请求，失败则清空会话。
 */
async function retryAfterRefresh(
  config: InternalAxiosRequestConfig,
  fallbackMessage?: string,
): Promise<AxiosResponse> {
  const expiredMessage = fallbackMessage && fallbackMessage.trim() ? fallbackMessage : '登录已过期，请重新登录';

  if (config.skipAuthRefresh || config._retriedAfterRefresh) {
    authToken.clear();
    emitSessionExpired();
    throw new Error(expiredMessage);
  }

  const token = await requestRefresh();
  if (!token) {
    authToken.clear();
    emitSessionExpired();
    throw new Error(expiredMessage);
  }

  config._retriedAfterRefresh = true;
  config.headers.set('Authorization', `Bearer ${token}`);
  return instance.request(config);
}

instance.interceptors.request.use((config) => {
  if (!config.skipAuth) {
    const token = authToken.get();
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`);
    }
  }
  return config;
});

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

export function isResult(value: unknown): value is Result {
  return isRecord(value)
    && typeof value.code === 'number'
    && typeof value.message === 'string';
}

export function getResultError(value: unknown): Error | null {
  if (!isResult(value) || value.code === SUCCESS_CODE) {
    return null;
  }
  return new Error(value.message || '请求失败');
}

function parseResultText(text: string): Result | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith('{')) {
    return null;
  }

  try {
    const value = JSON.parse(trimmed) as unknown;
    return isResult(value) ? value : null;
  } catch {
    return null;
  }
}

function shouldTryParseBlob(blob: Blob): boolean {
  const type = blob.type.toLowerCase();
  return type.includes('json')
    || type.startsWith('text/')
    || blob.size <= RESULT_BLOB_PARSE_LIMIT;
}

export async function parseResultPayload(payload: unknown): Promise<Result | null> {
  if (isResult(payload)) {
    return payload;
  }

  if (payload instanceof Blob && shouldTryParseBlob(payload)) {
    return parseResultText(await payload.text());
  }

  if (typeof payload === 'string') {
    return parseResultText(payload);
  }

  return null;
}

export async function resolveBlobDownload(blob: Blob): Promise<Blob> {
  const result = await parseResultPayload(blob);
  if (!result) {
    return blob;
  }

  if (result.code !== SUCCESS_CODE) {
    throw new Error(result.message || '文件下载失败');
  }

  throw new Error(result.message && result.message !== 'success'
    ? result.message
    : '文件下载失败：服务端未返回文件内容');
}

async function getErrorFromResponseData(data: unknown): Promise<Error | null> {
  const result = await parseResultPayload(data);
  if (!result) {
    return null;
  }

  return new Error(result.message || '请求失败');
}

/**
 * 响应拦截器
 *
 * 后端约定：所有响应都是 HTTP 200 + Result
 * - code === 200 → 成功，返回 data
 * - code !== 200 → 失败，直接显示 message
 */
instance.interceptors.response.use(
  (response) => {
    if (response.config.skipResultTransform) {
      return response;
    }

    const result = response.data as Result;

    // 检查是否是 Result 格式
    if (isResult(result)) {
      if (result.code === SUCCESS_CODE) {
        // 成功：返回 data
        response.data = result.data;
        return response;
      }
      // Access Token 过期/被撤销：静默续期后自动重放，业务代码无感知
      if (AUTH_EXPIRED_CODES.has(result.code)) {
        return retryAfterRefresh(response.config, result.message);
      }
      // 失败：直接抛出 message
      return Promise.reject(new Error(result.message || '请求失败'));
    }
    
    // 非 Result 格式，直接返回
    return response;
  },
  async (error) => {
    // 有响应的情况：后端返回了结果（即使是错误）
    if (error.response) {
      const { data, status } = error.response;
      const payload = await parseResultPayload(data);
      // 401 或认证类业务码：先尝试静默续期再重放一次
      if ((status === 401 || (payload != null && AUTH_EXPIRED_CODES.has(payload.code))) && error.config) {
        return retryAfterRefresh(error.config, payload?.message);
      }
      // 尝试解析 Result 格式
      const responseError = await getErrorFromResponseData(data);
      if (responseError) {
        return Promise.reject(responseError);
      }
      // 网关和 CORS 拒绝等错误可能不使用 Result，保留状态码便于定位。
      const message = status === 403
        ? '请求被服务端拒绝（403），请检查访问权限或跨域配置'
        : `请求失败（HTTP ${status}），请稍后重试`;
      return Promise.reject(new Error(message));
    }

    // 没有响应的情况：真正的网络错误或连接被重置
    // 对于文件上传，可能是网络超时或连接中断，但不一定是文件大小问题
    // 让后端返回真实的错误信息，而不是在这里假设
    const config = error.config;
    const isUpload = config && (
      config.url?.includes('/upload') ||
      config.headers?.['Content-Type']?.toString().includes('multipart')
    );

    if (isUpload) {
      // 文件上传失败且没有响应，可能是网络超时或连接中断
      // 不直接假设是文件大小问题，返回更通用的错误信息
      return Promise.reject(new Error('上传失败，可能是网络超时或连接中断，请重试'));
    }

    // 其他网络错误
    return Promise.reject(new Error('网络连接失败，请检查网络'));
  }
);

export const request = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then(res => res.data);
  },

  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then(res => res.data);
  },

  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then(res => res.data);
  },

  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.patch(url, data, config).then(res => res.data);
  },

  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then(res => res.data);
  },

  /**
   * 文件上传
   */
  upload<T>(url: string, formData: FormData, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, formData, {
      timeout: 300000, // 5分钟，与Nginx proxy_read_timeout对齐
      headers: { 'Content-Type': 'multipart/form-data' },
      ...config,
    }).then(res => res.data);
  },

  /**
   * 文件下载，自动识别后端以 Blob 形式返回的 Result.error
   */
  async download(url: string, config?: AxiosRequestConfig): Promise<Blob> {
    const response = await instance.get<Blob>(url, {
      ...config,
      responseType: 'blob',
      skipResultTransform: true,
    });
    return resolveBlobDownload(response.data);
  },

  /**
   * 获取原始实例（用于特殊场景如下载 Blob）
   */
  getInstance(): AxiosInstance {
    return instance;
  },
};

/**
 * 获取错误信息
 */
export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '未知错误';
}

export default request;
