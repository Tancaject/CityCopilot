export interface ShopType {
  id: number;
  name: string;
  icon: string;
  sort?: number;
}

export interface Blog {
  id: number;
  shopId?: number;
  userId: number;
  title: string;
  images: string;
  liked: number;
  comments?: number;
  createTime?: string;
  updateTime?: string;
  name?: string;
  icon?: string;
  isLike?: boolean;
  content?: string;
}

export interface ScrollResult<T> {
  list?: T[];
  minTime?: number;
  offset?: number;
}

export interface UserDTO {
  id: number;
  nickName?: string;
  name?: string;
  icon?: string;
  role?: string;
  permissions?: string[];
}

export interface UserAdmin {
  id: number;
  phone?: string;
  nickName?: string;
  icon?: string;
  role?: string;
  createTime?: string;
  updateTime?: string;
}

export interface LoginForm {
  phone: string;
  code: string;
}

export interface Shop {
  id: number;
  name: string;
  typeId: number;
  images: string;
  area?: string;
  address?: string;
  x?: number;
  y?: number;
  poiId?: string;
  adcode?: string;
  geoSource?: string;
  geoStatus?: string;
  geoUpdatedAt?: string;
  avgPrice?: number;
  sold?: number;
  comments?: number;
  score?: number;
  openHours?: string;
  distance?: number;
}

export interface GeoPoint {
  x: number;
  y: number;
}

export interface ShopGeoUpdate {
  x: number;
  y: number;
  poiId?: string;
  adcode?: string;
  geoSource?: string;
  geoStatus?: string;
}

export interface AmapPoi {
  id: string;
  name: string;
  type?: string;
  typecode?: string;
  address?: string;
  provinceName?: string;
  cityName?: string;
  adName?: string;
  adcode?: string;
  x: number;
  y: number;
}

export interface AmapServiceStatus {
  circuitOpen: boolean;
  circuitTtlSeconds: number;
  failureStreak: number;
  rateLimitPerMinute: number;
  currentMinuteCalls: number;
  cacheTtlMinutes: number;
  failureThreshold: number;
  circuitOpenSeconds: number;
  todayMetrics: Record<string, number>;
}

export interface AiAssistantAction {
  label: string;
  type: string;
  routeName?: string;
  routePath?: string;
  query?: Record<string, unknown>;
}

export interface AiAssistantShop {
  id: number;
  name: string;
  area?: string;
  address?: string;
  avgPrice?: number;
  score?: number;
  sold?: number;
  comments?: number;
  openHours?: string;
  distance?: number;
  voucherCount?: number;
  voucherHighlights?: string[];
  reason?: string;
}

export interface AiChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

export interface AiChatRequest {
  message: string;
  scene?: 'user' | 'admin';
  conversationId?: string;
  routeName?: string;
  routePath?: string;
  currentShopId?: number;
  x?: number;
  y?: number;
  history?: AiChatMessage[];
}

export interface AiChatResponse {
  conversationId?: string;
  scene: string;
  intent: string;
  message: string;
  confidence?: number;
  shops?: AiAssistantShop[];
  actions?: AiAssistantAction[];
  diagnostics?: Record<string, unknown>;
}

export interface AgentToolEvent {
  index?: number;
  phase?: 'call' | 'result' | string;
  toolName?: string;
  args?: Record<string, unknown>;
  observation?: unknown;
}

export interface AgentSseEvent {
  event: 'message' | 'tool' | 'error' | 'final' | 'traceId' | string;
  data?: unknown;
}

export interface AgentTraceStep {
  index: number;
  type: string;
  toolName?: string;
  args?: Record<string, unknown>;
  observation?: unknown;
  errorCode?: string;
  message?: string;
}

export interface AgentTrace {
  traceId: string;
  mode: string;
  steps: AgentTraceStep[];
  finalAnswer?: string;
  durationMs: number;
  createdAt?: string;
}

export interface GeoBatchJob {
  id?: number;
  jobId: string;
  status: 'queued' | 'running' | 'finished' | string;
  city?: string;
  keyword?: string;
  verified?: boolean;
  onlyMissing?: boolean;
  limitSize?: number;
  total: number;
  processed: number;
  success: number;
  failed: number;
  message?: string;
  operatorId?: number;
  operatorName?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface GeoBatchJobItem {
  id: number;
  jobId: string;
  shopId: number;
  shopName: string;
  status: string;
  message?: string;
  beforeData?: string;
  afterData?: string;
  createTime?: string;
  updateTime?: string;
}

export interface OperationLog {
  id: number;
  actorId?: number;
  actorName?: string;
  actorRole?: string;
  module: string;
  action: string;
  targetType?: string;
  targetId?: number;
  targetKey?: string;
  beforeData?: string;
  afterData?: string;
  success: boolean;
  errorMessage?: string;
  createTime?: string;
}

export interface AuditStatus {
  retentionDays: number;
  exportLimit: number;
  cutoff?: string;
  expired: number;
  total: number;
}

export interface OpsSummary {
  users: number;
  shops: number;
  blogs: number;
  todayBlogs: number;
  orders: number;
  todayOrders: number;
  unpaidOrders: number;
  cancelledOrders: number;
  vouchers: number;
  seckillVouchers: number;
  activeVouchers: number;
  missingGeo: number;
  pendingGeo: number;
  verifiedGeo: number;
  failedAuditsToday: number;
}

export interface ApiEndpoint {
  methods: string[];
  paths: string[];
  controller: string;
  handler: string;
  permission?: {
    permissions?: string[];
    anyOf?: boolean;
  };
}

export interface ApiErrorDefinition {
  code: string;
  message: string;
}

export interface Voucher {
  id: number;
  shopId: number;
  title: string;
  subTitle?: string;
  rules?: string;
  payValue?: number;
  actualValue?: number;
  type?: number;
  status?: number;
  stock?: number;
  beginTime?: string;
  endTime?: string;
  createTime?: string;
  updateTime?: string;
}

export interface VoucherOrder {
  id: number;
  voucherId?: number;
  shopId?: number;
  shopName?: string;
  voucherTitle?: string;
  voucherSubTitle?: string;
  voucherRules?: string;
  payValue?: number;
  actualValue?: number;
  payType?: number;
  status?: number;
  state?: 'processing' | 'unpaid' | 'paid' | 'used' | 'cancelled' | 'refunding' | 'refunded' | 'unknown' | string;
  statusText?: string;
  createTime?: string;
  payTime?: string;
  useTime?: string;
  refundTime?: string;
  updateTime?: string;
}
