import axios, { type AxiosRequestConfig } from 'axios';

interface ApiResult<T> {
  success: boolean;
  code?: string;
  errorMsg?: string;
  data?: T;
  total?: number;
}

export class ApiError extends Error {
  code?: string;
  status?: number;
  details?: unknown;

  constructor(message: string, code?: string, status?: number, details?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 8000,
});

api.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('token');
  if (token) {
    config.headers.Authorization = token;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      window.dispatchEvent(new CustomEvent('citycopilot:auth-expired'));
      return Promise.reject(new ApiError('请先登录', error.response?.data?.code || 'AUTH_REQUIRED'));
    }
    const message = error.response?.data?.errorMsg || error.response?.data?.message || error.message || '服务暂时不可用';
    return Promise.reject(new ApiError(message, error.response?.data?.code, error.response?.status, error.response?.data));
  },
);

async function requestData<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await api.request<ApiResult<T>>(config);
  const body = response.data;
  if (body && typeof body === 'object' && 'success' in body) {
    if (!body.success) {
      throw new ApiError(body.errorMsg || '请求失败', body.code);
    }
    return body.data as T;
  }
  return body as T;
}

export function getData<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ ...config, method: 'GET', url });
}

export function postData<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ ...config, method: 'POST', url, data });
}

export function putData<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ ...config, method: 'PUT', url, data });
}

export function deleteData<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return requestData<T>({ ...config, method: 'DELETE', url });
}
