import { deleteData, getData, postData, putData } from './client';
import type {
  ApiEndpoint,
  ApiErrorDefinition,
  AmapPoi,
  AmapServiceStatus,
  AuditStatus,
  Blog,
  GeoBatchJob,
  GeoBatchJobItem,
  GeoPoint,
  LoginForm,
  OperationLog,
  OpsSummary,
  ScrollResult,
  Shop,
  ShopDiscoveryItem,
  ShopGeoUpdate,
  ShopType,
  UserAdmin,
  UserDTO,
  Voucher,
  VoucherOrder,
} from '../types';

interface PageResult<T> {
  records?: T[];
}

function normalizeList<T>(payload: T[] | PageResult<T> | undefined) {
  if (!payload) {
    return [];
  }
  return Array.isArray(payload) ? payload : payload.records || [];
}

function normalizeUploadedImagePath(path: string) {
  return path.startsWith('/blogs/') ? `/uploads${path}` : path;
}

export function fetchShopTypes() {
  return getData<ShopType[]>('/shop-type/list');
}

export function fetchHotBlogs(current: number) {
  return getData<Blog[]>('/blog/hot', {
    params: { current },
  });
}

export function fetchBlog(id: number) {
  return getData<Blog>(`/blog/${id}`);
}

export function likeBlog(id: number) {
  return putData<void>(`/blog/like/${id}`);
}

export function fetchBlogLikes(id: number) {
  return getData<UserDTO[]>(`/blog/likes/${id}`);
}

export function publishBlog(blog: Pick<Blog, 'title' | 'content' | 'images'> & { shopId?: number }) {
  return postData<number>('/blog', blog);
}

export async function uploadBlogImage(file: File) {
  const data = new FormData();
  data.append('file', file);
  return normalizeUploadedImagePath(await postData<string>('/upload/blog', data));
}

export async function fetchFollowBlogs(lastId: number, offset: number) {
  const payload = await getData<ScrollResult<Blog> | undefined>('/blog/of/follow', {
    params: { lastId, offset },
  });
  return {
    list: payload?.list || [],
    minTime: payload?.minTime || lastId,
    offset: payload?.offset || 0,
  };
}

export function checkFollow(userId: number) {
  return getData<boolean>(`/follow/or/not/${userId}`);
}

export function followUser(userId: number, isFollow: boolean) {
  return putData<void>(`/follow/${userId}/${isFollow}`);
}

export function sendLoginCode(phone: string) {
  return postData<void>('/user/code', undefined, {
    params: { phone },
  });
}

export function loginByCode(form: LoginForm) {
  return postData<string>('/user/login', form);
}

export function fetchMe() {
  return getData<UserDTO>('/user/me');
}

export function logoutCurrentUser() {
  return postData<void>('/user/logout');
}

export async function fetchShopsByName(name: string | undefined, current: number, size?: number) {
  const payload = await getData<Shop[]>('/shop/of/name', {
    params: { name, current, size },
  });
  return normalizeList(payload);
}

export async function fetchShopDiscovery(params: {
  typeId?: number;
  keyword?: string;
  current: number;
  point?: GeoPoint;
  size?: number;
}) {
  const payload = await getData<ShopDiscoveryItem[] | PageResult<ShopDiscoveryItem>>('/shop/discovery', {
    params: {
      typeId: params.typeId,
      keyword: params.keyword,
      current: params.current,
      size: params.size,
      x: params.point?.x,
      y: params.point?.y,
    },
  });
  return normalizeList(payload);
}

export function fetchShop(id: number) {
  return getData<Shop>(`/shop/${id}`);
}

export function createShop(data: Partial<Shop>) {
  return postData<number>('/shop', data);
}

export function updateShop(data: Partial<Shop> & { id: number }) {
  return putData<void>('/shop', data);
}

export function fetchVouchers(shopId: number) {
  return getData<Voucher[]>(`/voucher/list/${shopId}`);
}

export async function fetchAdminVouchers(params: {
  current?: number;
  shopId?: number;
  keyword?: string;
  status?: number;
}) {
  const payload = await getData<Voucher[] | PageResult<Voucher>>('/admin/vouchers', {
    params,
  });
  return normalizeList(payload);
}

export function createAdminVoucher(data: Partial<Voucher>) {
  return postData<number>('/admin/vouchers', data);
}

export function updateAdminVoucher(id: number, data: Partial<Voucher>) {
  return putData<Voucher>(`/admin/vouchers/${id}`, data);
}

export function updateAdminVoucherStatus(id: number, status: number) {
  return putData<Voucher>(`/admin/vouchers/${id}/status`, undefined, {
    params: { status },
  });
}

export function seckillVoucher(voucherId: number) {
  const idempotencyKey = crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return postData<string>(`/voucher-order/seckill/${voucherId}`, undefined, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function fetchVoucherOrder(orderId: string) {
  return getData<VoucherOrder>(`/voucher-order/${orderId}`);
}

export function cancelVoucherOrder(orderId: string) {
  return postData<VoucherOrder>(`/voucher-order/${orderId}/cancel`);
}

export function payVoucherOrder(orderId: string, payType = 1) {
  return postData<VoucherOrder>(`/voucher-order/${orderId}/pay`, undefined, {
    params: { payType },
  });
}

export function refundVoucherOrder(orderId: string) {
  return postData<VoucherOrder>(`/voucher-order/${orderId}/refund`);
}

export function useVoucherOrder(orderId: string) {
  return postData<VoucherOrder>(`/voucher-order/${orderId}/use`);
}

export async function fetchMyVoucherOrders(current: number) {
  const payload = await getData<VoucherOrder[] | PageResult<VoucherOrder>>('/voucher-order/of/me', {
    params: { current },
  });
  return normalizeList(payload);
}

export function geocodeShop(id: number, city: string, address?: string, verified = false) {
  return postData<Shop>(`/geo/shops/${id}/geocode`, undefined, {
    params: { city, address, verified },
  });
}

export function fetchShopPoiCandidates(id: number, city: string, keyword?: string) {
  return getData<AmapPoi[]>(`/geo/shops/${id}/pois`, {
    params: { city, keyword },
  });
}

export function confirmShopPoi(id: number, poiId: string, city: string, keyword?: string, verified = true) {
  return postData<Shop>(`/geo/shops/${id}/poi`, undefined, {
    params: { poiId, city, keyword, verified },
  });
}

export function updateShopGeo(id: number, data: ShopGeoUpdate) {
  return putData<Shop>(`/shop/${id}/geo`, data);
}

export function syncShopGeoIndex() {
  return postData<number>('/geo/shop-index/sync');
}

export function fetchAmapStatus() {
  return getData<AmapServiceStatus>('/geo/amap/status');
}

export function startGeoBatchGeocode(params: {
  city: string;
  keyword?: string;
  verified?: boolean;
  onlyMissing?: boolean;
  limit?: number;
}) {
  return postData<GeoBatchJob>('/geo/shops/batch-geocode', undefined, {
    params,
  });
}

export function fetchGeoBatchJob(jobId: string) {
  return getData<GeoBatchJob>(`/geo/batch-jobs/${jobId}`);
}

export async function fetchGeoBatchJobs(current = 1, status?: string) {
  const payload = await getData<GeoBatchJob[] | PageResult<GeoBatchJob>>('/geo/batch-jobs', {
    params: { current, status },
  });
  return normalizeList(payload);
}

export function fetchGeoBatchJobItems(jobId: string) {
  return getData<GeoBatchJobItem[]>(`/geo/batch-jobs/${jobId}/items`);
}

export function cancelGeoBatchJob(jobId: string) {
  return postData<GeoBatchJob>(`/geo/batch-jobs/${jobId}/cancel`);
}

export function retryGeoBatchJob(jobId: string, verified?: boolean, limit?: number) {
  return postData<GeoBatchJob>(`/geo/batch-jobs/${jobId}/retry`, undefined, {
    params: { verified, limit },
  });
}

export async function fetchOperationLogs(params: {
  current?: number;
  module?: string;
  action?: string;
  targetType?: string;
  targetId?: number | string;
}) {
  const payload = await getData<OperationLog[] | PageResult<OperationLog>>('/admin/audit-logs', {
    params,
  });
  return normalizeList(payload);
}

export function exportOperationLogs(params: {
  module?: string;
  action?: string;
  targetType?: string;
  targetId?: number;
}) {
  return getData<string>('/admin/audit-logs/export', {
    params,
  });
}

export function fetchAuditStatus() {
  return getData<AuditStatus>('/admin/audit-logs/status');
}

export function cleanupAuditLogs() {
  return postData<number>('/admin/audit-logs/cleanup');
}

export function fetchOpsSummary() {
  return getData<OpsSummary>('/admin/ops/summary');
}

export function fetchAdminVoucherOrder(orderId: string) {
  return getData<VoucherOrder>(`/admin/orders/${orderId}`);
}

export async function fetchAdminUsers(current = 1, keyword?: string) {
  const payload = await getData<UserAdmin[] | PageResult<UserAdmin>>('/admin/users', {
    params: { current, keyword },
  });
  return normalizeList(payload);
}

export function updateAdminUserRole(id: number, role: string) {
  return putData<UserAdmin>(`/admin/users/${id}/role`, undefined, {
    params: { role },
  });
}

export function fetchPermissionMatrix() {
  return getData<Record<string, string[]>>('/admin/api/permissions');
}

export function fetchApiEndpoints() {
  return getData<ApiEndpoint[]>('/admin/api/endpoints');
}

export function fetchApiErrors() {
  return getData<ApiErrorDefinition[]>('/admin/api/errors');
}

export async function fetchAdminBlogs(current = 1, keyword?: string) {
  const payload = await getData<Blog[] | PageResult<Blog>>('/admin/blogs', {
    params: { current, keyword },
  });
  return normalizeList(payload);
}

export function deleteAdminBlog(id: number) {
  return deleteData<void>(`/admin/blogs/${id}`);
}
