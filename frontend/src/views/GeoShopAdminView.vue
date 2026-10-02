<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import {
  Activity,
  AlertCircle,
  BadgeCheck,
  Ban,
  CheckCircle2,
  Database,
  Download,
  ExternalLink,
  Loader2,
  MapPin,
  RefreshCw,
  Search,
  SlidersHorizontal,
  Store,
} from '@lucide/vue';
import {
  cleanupAuditLogs,
  confirmShopPoi,
  cancelGeoBatchJob,
  exportOperationLogs,
  fetchAuditStatus,
  fetchAmapStatus,
  fetchGeoBatchJob,
  fetchGeoBatchJobs,
  fetchGeoBatchJobItems,
  fetchOperationLogs,
  fetchOpsSummary,
  fetchShopPoiCandidates,
  fetchShopsByName,
  geocodeShop,
  retryGeoBatchJob,
  startGeoBatchGeocode,
  syncShopGeoIndex,
  updateShopGeo,
} from '../api/citycopilot';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import { authState } from '../composables/auth';
import { getErrorMessage } from '../utils/errors';
import { getPaginationLabel } from '../utils/labels';
import { hasPermission } from '../utils/permissions';
import type { AmapPoi, AmapServiceStatus, AuditStatus, GeoBatchJob, GeoBatchJobItem, OpsSummary, Shop } from '../types';
import type { OperationLog } from '../types';

type GeoStatusFilter = 'all' | 'verified' | 'pending' | 'missing';

const route = useRoute();
const shops = ref<Shop[]>([]);
const candidates = ref<AmapPoi[]>([]);
const auditLogs = ref<OperationLog[]>([]);
const batchItems = ref<GeoBatchJobItem[]>([]);
const batchJobs = ref<GeoBatchJob[]>([]);
const amapStatus = ref<AmapServiceStatus | null>(null);
const auditStatus = ref<AuditStatus | null>(null);
const opsSummary = ref<OpsSummary | null>(null);
const selectedShop = ref<Shop | null>(null);
const currentPage = ref(1);
const keyword = ref('');
const city = ref(import.meta.env.VITE_DEFAULT_CITY || '杭州');
const statusFilter = ref<GeoStatusFilter>('all');
const poiKeyword = ref('');
const addressDraft = ref('');
const loading = ref(false);
const loadingMore = ref(false);
const candidatesLoading = ref(false);
const auditLoading = ref(false);
const batchItemsLoading = ref(false);
const batchJobsLoading = ref(false);
const amapStatusLoading = ref(false);
const auditExporting = ref(false);
const auditCleaning = ref(false);
const syncing = ref(false);
const batchLoading = ref(false);
const batchJob = ref<GeoBatchJob | null>(null);
const batchLimit = ref(20);
const batchOnlyMissing = ref(true);
const batchVerified = ref(false);
const savingShopId = ref<number | null>(null);
const geocodingShopId = ref<number | null>(null);
const hasMore = ref(true);
const errorMessage = ref('');
const candidateError = ref('');
const successMessage = ref('');
const cleanupAuditOpen = ref(false);
let requestId = 0;
let batchTimer: number | undefined;

const manualForm = reactive({
  x: '',
  y: '',
  poiId: '',
  adcode: '',
  geoSource: 'manual',
  geoStatus: 'verified',
});

const statusOptions: Array<{ value: GeoStatusFilter; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'verified', label: '已验证' },
  { value: 'pending', label: '待确认' },
  { value: 'missing', label: '缺坐标' },
];

const filteredShops = computed(() => {
  if (statusFilter.value === 'all') {
    return shops.value;
  }
  if (statusFilter.value === 'missing') {
    return shops.value.filter(isMissingGeo);
  }
  return shops.value.filter((shop) => normalizeStatus(shop) === statusFilter.value);
});

const stats = computed(() => {
  const total = shops.value.length;
  const verified = shops.value.filter((shop) => normalizeStatus(shop) === 'verified').length;
  const pending = shops.value.filter((shop) => normalizeStatus(shop) === 'pending').length;
  const missing = shops.value.filter(isMissingGeo).length;
  return { total, verified, pending, missing };
});

const selectedCoordinates = computed(() => (selectedShop.value ? formatCoordinates(selectedShop.value) : '未选择店铺'));
const auditTitle = computed(() => (selectedShop.value ? `${selectedShop.value.name} 的最近操作` : '最近操作'));
const canCleanupAudit = computed(() => hasPermission(authState.user, 'audit:write'));
const auditPolicyText = computed(() => {
  if (!auditStatus.value) {
    return '';
  }
  return `留存 ${auditStatus.value.retentionDays} 天 · 过期 ${auditStatus.value.expired || 0} 条`;
});
const batchProgress = computed(() => {
  if (!batchJob.value?.total) {
    return 0;
  }
  return Math.min(100, Math.round((batchJob.value.processed / batchJob.value.total) * 100));
});
const batchRunning = computed(() => batchJob.value?.status === 'queued' || batchJob.value?.status === 'running');
const visibleBatchItems = computed(() => batchItems.value.slice(0, 6));
const recentBatchJobs = computed(() => batchJobs.value.slice(0, 6));
const amapHealthText = computed(() => {
  if (!amapStatus.value) {
    return '状态读取中';
  }
  if (amapStatus.value.circuitOpen) {
    return `熔断中，约 ${amapStatus.value.circuitTtlSeconds || 0} 秒后恢复`;
  }
  return '服务正常';
});
const opsCards = computed(() => {
  const summary = opsSummary.value;
  if (!summary) {
    return [];
  }
  return [
    { label: '用户', value: summary.users, hint: '累计' },
    { label: '店铺', value: summary.shops, hint: `缺坐标 ${summary.missingGeo}` },
    { label: '订单', value: summary.orders, hint: `今日 ${summary.todayOrders}` },
    { label: '内容', value: summary.blogs, hint: `今日 ${summary.todayBlogs}` },
    { label: '待处理', value: summary.pendingGeo + summary.unpaidOrders, hint: `审计失败 ${summary.failedAuditsToday}` },
  ];
});
const amapMetricSummary = computed(() => {
  const metrics = amapStatus.value?.todayMetrics || {};
  return {
    success: metrics['total.success'] || 0,
    failure: metrics['total.failure'] || 0,
    cacheHit: metrics['total.cache_hit'] || 0,
    limited: metrics['total.rate_limited'] || 0,
  };
});
const geoPipeline = computed(() => [
  {
    label: '定位问题',
    value: stats.value.missing,
    detail: '缺坐标优先处理',
    tone: stats.value.missing > 0 ? 'warn' : 'ok',
  },
  {
    label: '人工复核',
    value: stats.value.pending,
    detail: '待确认 POI',
    tone: stats.value.pending > 0 ? 'warn' : 'ok',
  },
  {
    label: '索引可用',
    value: stats.value.verified,
    detail: '可进入附近推荐',
    tone: 'ok',
  },
  {
    label: '服务风险',
    value: amapMetricSummary.value.failure + amapMetricSummary.value.limited,
    detail: amapStatus.value?.circuitOpen ? '高德服务熔断' : '失败/限流',
    tone: amapStatus.value?.circuitOpen ? 'bad' : 'muted',
  },
]);

function normalizeStatus(shop: Shop) {
  return shop.geoStatus || (isMissingGeo(shop) ? 'missing' : 'seed');
}

function isMissingGeo(shop: Shop) {
  return typeof shop.x !== 'number' || typeof shop.y !== 'number';
}

function statusLabel(shop: Shop) {
  const status = normalizeStatus(shop);
  if (status === 'verified') {
    return '已验证';
  }
  if (status === 'pending') {
    return '待确认';
  }
  if (status === 'missing') {
    return '缺坐标';
  }
  if (status === 'seed') {
    return '种子数据';
  }
  return status;
}

function statusClass(shop: Shop) {
  return `geo-status-${normalizeStatus(shop)}`;
}

function formatCoordinates(value: Pick<Shop, 'x' | 'y'> | Pick<AmapPoi, 'x' | 'y'>) {
  if (typeof value.x !== 'number' || typeof value.y !== 'number') {
    return '未设置';
  }
  return `${value.x.toFixed(6)}, ${value.y.toFixed(6)}`;
}

function formatAddress(shop: Shop) {
  return [shop.area, shop.address].filter(Boolean).join(' · ') || '地址待补充';
}

function formatDate(value?: string) {
  if (!value) {
    return '未更新';
  }
  const date = new Date(value.replace(' ', 'T'));
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatAuditAction(action: string) {
  const labels: Record<string, string> = {
    shop_update: '店铺资料修改',
    shop_geo_update: '坐标写回',
    geo_index_sync: 'GEO 索引重建',
    geo_batch_start: '批量任务创建',
    geo_batch_retry: '批量失败重试',
    geo_batch_cancel: '批量任务取消',
    geo_batch_finish: '批量任务完成',
  };
  return labels[action] || action;
}

function defaultAddress(shop: Shop) {
  return [city.value, shop.area, shop.address].filter(Boolean).join('');
}

function hydrateManualForm(shop: Shop) {
  manualForm.x = typeof shop.x === 'number' ? String(shop.x) : '';
  manualForm.y = typeof shop.y === 'number' ? String(shop.y) : '';
  manualForm.poiId = shop.poiId || '';
  manualForm.adcode = shop.adcode || '';
  manualForm.geoSource = shop.geoSource || 'manual';
  manualForm.geoStatus = shop.geoStatus || 'verified';
}

function selectShop(shop: Shop) {
  selectedShop.value = shop;
  poiKeyword.value = shop.name;
  addressDraft.value = defaultAddress(shop);
  candidates.value = [];
  candidateError.value = '';
  hydrateManualForm(shop);
  loadAuditLogs(shop.id);
}

function mergeUpdatedShop(updated: Shop) {
  const index = shops.value.findIndex((shop) => shop.id === updated.id);
  if (index >= 0) {
    shops.value.splice(index, 1, updated);
  }
  if (selectedShop.value?.id === updated.id) {
    selectedShop.value = updated;
    hydrateManualForm(updated);
  }
}

async function loadFirstPage() {
  const currentRequest = ++requestId;
  loading.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  hasMore.value = true;
  try {
    const result = await fetchShopsByName(keyword.value.trim() || undefined, 1);
    if (currentRequest !== requestId) {
      return;
    }
    shops.value = result;
    currentPage.value = 1;
    hasMore.value = result.length > 0;
    if (selectedShop.value) {
      const fresh = result.find((shop) => shop.id === selectedShop.value?.id);
      if (fresh) {
        selectShop(fresh);
      }
    }
  } catch (error) {
    if (currentRequest === requestId) {
      errorMessage.value = getErrorMessage(error, '店铺列表加载失败');
    }
  } finally {
    if (currentRequest === requestId) {
      loading.value = false;
    }
  }
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value) {
    return;
  }
  loadingMore.value = true;
  errorMessage.value = '';
  try {
    const nextPage = currentPage.value + 1;
    const result = await fetchShopsByName(keyword.value.trim() || undefined, nextPage);
    if (!result.length) {
      hasMore.value = false;
      return;
    }
    shops.value = shops.value.concat(result);
    currentPage.value = nextPage;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '加载更多店铺失败');
  } finally {
    loadingMore.value = false;
  }
}

async function loadCandidates(shop = selectedShop.value) {
  if (!shop) {
    return;
  }
  selectedShop.value = shop;
  candidatesLoading.value = true;
  candidateError.value = '';
  successMessage.value = '';
  try {
    candidates.value = await fetchShopPoiCandidates(shop.id, city.value, poiKeyword.value.trim() || shop.name);
    if (!candidates.value.length) {
      candidateError.value = '没有找到高德 POI 候选，可以换一个关键词再试';
    }
  } catch (error) {
    candidateError.value = getErrorMessage(error, 'POI 候选加载失败');
  } finally {
    candidatesLoading.value = false;
    void loadAmapStatus();
  }
}

function fillManualFromPoi(poi: AmapPoi) {
  manualForm.x = String(poi.x);
  manualForm.y = String(poi.y);
  manualForm.poiId = poi.id;
  manualForm.adcode = poi.adcode || '';
  manualForm.geoSource = 'amap_poi';
  manualForm.geoStatus = 'verified';
}

async function confirmCandidate(poi: AmapPoi) {
  if (!selectedShop.value) {
    return;
  }
  savingShopId.value = selectedShop.value.id;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const updated = await confirmShopPoi(selectedShop.value.id, poi.id, city.value, poiKeyword.value.trim() || selectedShop.value.name, true);
    mergeUpdatedShop(updated);
    await loadAuditLogs(updated.id);
    successMessage.value = `已确认 ${updated.name} 的高德 POI`;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, 'POI 确认失败');
  } finally {
    savingShopId.value = null;
    void loadAmapStatus();
  }
}

async function handleGeocode(shop = selectedShop.value, verified = false) {
  if (!shop) {
    return;
  }
  selectedShop.value = shop;
  geocodingShopId.value = shop.id;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const updated = await geocodeShop(shop.id, city.value, addressDraft.value.trim() || defaultAddress(shop), verified);
    mergeUpdatedShop(updated);
    await loadAuditLogs(updated.id);
    successMessage.value = verified ? '地址解析已写入并标记为已验证' : '地址解析已写入，状态为待确认';
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '地址解析失败');
  } finally {
    geocodingShopId.value = null;
    void loadAmapStatus();
  }
}

async function saveManualGeo() {
  if (!selectedShop.value) {
    return;
  }
  const x = Number(manualForm.x);
  const y = Number(manualForm.y);
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    errorMessage.value = '经纬度必须是有效数字';
    return;
  }
  savingShopId.value = selectedShop.value.id;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const updated = await updateShopGeo(selectedShop.value.id, {
      x,
      y,
      poiId: manualForm.poiId.trim() || undefined,
      adcode: manualForm.adcode.trim() || undefined,
      geoSource: manualForm.geoSource.trim() || 'manual',
      geoStatus: manualForm.geoStatus,
    });
    mergeUpdatedShop(updated);
    await loadAuditLogs(updated.id);
    successMessage.value = '坐标已手动写回';
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '手动保存失败');
  } finally {
    savingShopId.value = null;
  }
}

async function handleSyncIndex() {
  syncing.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const count = await syncShopGeoIndex();
    await loadAuditLogs();
    successMessage.value = `Redis GEO 索引已重建，共同步 ${count || 0} 家店`;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, 'GEO 索引同步失败');
  } finally {
    syncing.value = false;
  }
}

async function loadAmapStatus() {
  amapStatusLoading.value = true;
  try {
    amapStatus.value = await fetchAmapStatus();
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '高德服务状态加载失败');
  } finally {
    amapStatusLoading.value = false;
  }
}

async function handleExportAudit() {
  auditExporting.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const csv = await exportOperationLogs({
      targetType: selectedShop.value ? 'shop' : undefined,
      targetId: selectedShop.value?.id,
    });
    const blob = new Blob([`\uFEFF${csv || ''}`], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = selectedShop.value ? `shop-${selectedShop.value.id}-audit.csv` : 'operation-audit.csv';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    successMessage.value = '审计日志已导出';
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '审计日志导出失败');
  } finally {
    auditExporting.value = false;
  }
}

async function loadAuditStatus() {
  try {
    auditStatus.value = await fetchAuditStatus();
  } catch {
    auditStatus.value = null;
  }
}

async function loadOpsSummary() {
  try {
    opsSummary.value = await fetchOpsSummary();
  } catch {
    opsSummary.value = null;
  }
}

async function handleCleanupAudit() {
  auditCleaning.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const count = await cleanupAuditLogs();
    successMessage.value = `已清理 ${count || 0} 条过期审计日志`;
    cleanupAuditOpen.value = false;
    await Promise.all([loadAuditLogs(selectedShop.value?.id), loadAuditStatus()]);
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '审计日志清理失败');
  } finally {
    auditCleaning.value = false;
  }
}

async function handleStartBatch() {
  batchLoading.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    batchJob.value = await startGeoBatchGeocode({
      city: city.value,
      keyword: keyword.value.trim() || undefined,
      verified: batchVerified.value,
      onlyMissing: batchOnlyMissing.value,
      limit: batchLimit.value,
    });
    await loadAmapStatus();
    await loadBatchItems();
    await loadBatchJobs();
    successMessage.value = batchJob.value.total ? '批量校准任务已进入队列' : '没有需要批量处理的店铺';
    startBatchPolling();
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '批量校准任务创建失败');
  } finally {
    batchLoading.value = false;
  }
}

async function refreshBatchJob() {
  if (!batchJob.value?.jobId) {
    return;
  }
  try {
    batchJob.value = await fetchGeoBatchJob(batchJob.value.jobId);
    await loadBatchItems();
    if (!batchRunning.value) {
      stopBatchPolling();
      await loadFirstPage();
      await loadAuditLogs(selectedShop.value?.id);
      await loadAmapStatus();
      await loadBatchJobs();
    }
  } catch (error) {
    stopBatchPolling();
    errorMessage.value = getErrorMessage(error, '批量任务状态获取失败');
  }
}

async function loadBatchItems() {
  if (!batchJob.value?.jobId) {
    batchItems.value = [];
    return;
  }
  batchItemsLoading.value = true;
  try {
    batchItems.value = await fetchGeoBatchJobItems(batchJob.value.jobId);
  } catch {
    batchItems.value = [];
  } finally {
    batchItemsLoading.value = false;
  }
}

async function loadBatchJobs(focusFailed = false) {
  batchJobsLoading.value = true;
  try {
    batchJobs.value = await fetchGeoBatchJobs(1);
    if (focusFailed) {
      const failedJob = batchJobs.value.find((job) => Number(job.failed) > 0);
      if (failedJob) {
        await selectBatchJob(failedJob);
      }
    }
  } catch {
    batchJobs.value = [];
  } finally {
    batchJobsLoading.value = false;
  }
}

async function selectBatchJob(job: GeoBatchJob) {
  batchJob.value = job;
  await loadBatchItems();
  if (batchRunning.value) {
    startBatchPolling();
  } else {
    stopBatchPolling();
  }
}

async function handleExternalBatchUpdate(event: Event) {
  const job = (event as CustomEvent<GeoBatchJob>).detail;
  if (!job?.jobId) {
    return;
  }
  batchJob.value = job;
  successMessage.value = `AI 助手已更新批量任务：${job.processed}/${job.total}，当前状态 ${job.status}`;
  await loadBatchItems();
  await loadBatchJobs();
  await loadAmapStatus();
  if (batchRunning.value) {
    startBatchPolling();
  } else {
    stopBatchPolling();
    await loadFirstPage();
    await loadAuditLogs(selectedShop.value?.id);
  }
}

async function handleExternalGeoIndexSynced(event: Event) {
  const count = Number((event as CustomEvent<number>).detail || 0);
  successMessage.value = `AI 助手已重建 Redis GEO 索引，共同步 ${count} 家店`;
  await loadFirstPage();
  await loadAuditLogs(selectedShop.value?.id);
  await loadAmapStatus();
}

async function handleCancelBatch(job = batchJob.value) {
  if (!job) {
    return;
  }
  errorMessage.value = '';
  successMessage.value = '';
  try {
    batchJob.value = await cancelGeoBatchJob(job.jobId);
    await loadBatchItems();
    await loadBatchJobs();
    stopBatchPolling();
    successMessage.value = '批量任务已取消';
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '取消批量任务失败');
  }
}

async function handleRetryBatch(job = batchJob.value) {
  if (!job) {
    return;
  }
  batchLoading.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    batchJob.value = await retryGeoBatchJob(job.jobId, batchVerified.value, batchLimit.value);
    await loadAmapStatus();
    await loadBatchItems();
    await loadBatchJobs();
    startBatchPolling();
    successMessage.value = '失败明细已创建重试任务';
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '重试批量任务失败');
  } finally {
    batchLoading.value = false;
  }
}

function startBatchPolling() {
  stopBatchPolling();
  if (!batchRunning.value) {
    return;
  }
  batchTimer = window.setInterval(refreshBatchJob, 2000);
}

function stopBatchPolling() {
  if (batchTimer) {
    window.clearInterval(batchTimer);
    batchTimer = undefined;
  }
}

async function loadAuditLogs(targetId = selectedShop.value?.id) {
  auditLoading.value = true;
  try {
    auditLogs.value = await fetchOperationLogs({
      current: 1,
      targetType: targetId ? 'shop' : undefined,
      targetId,
    });
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '审计日志加载失败');
  } finally {
    auditLoading.value = false;
  }
}

onMounted(() => {
  window.addEventListener('citycopilot:geo-batch-updated', handleExternalBatchUpdate);
  window.addEventListener('citycopilot:geo-index-synced', handleExternalGeoIndexSynced);
  loadFirstPage();
  loadAuditLogs();
  loadBatchJobs(route.query.focus === 'failed-jobs');
  loadAmapStatus();
  loadAuditStatus();
  loadOpsSummary();
});

watch(
  () => route.query.focus,
  (focus) => {
    if (focus === 'failed-jobs') {
      void loadBatchJobs(true);
    }
  },
);

onBeforeUnmount(() => {
  window.removeEventListener('citycopilot:geo-batch-updated', handleExternalBatchUpdate);
  window.removeEventListener('citycopilot:geo-index-synced', handleExternalGeoIndexSynced);
  stopBatchPolling();
});
</script>

<template>
  <section class="geo-admin-page">
    <header class="geo-admin-header">
      <div>
        <p class="eyebrow">Geo operations</p>
        <h1>店铺地理校准台</h1>
        <p>把店铺地址、坐标、POI 和 Redis GEO 索引放在一条运营流水线上处理。</p>
      </div>
      <div class="geo-stats" aria-label="当前页地理状态">
        <span>
          <strong>{{ stats.total }}</strong>
          当前结果
        </span>
        <span>
          <strong>{{ stats.verified }}</strong>
          已验证
        </span>
        <span>
          <strong>{{ stats.pending }}</strong>
          待确认
        </span>
        <span>
          <strong>{{ stats.missing }}</strong>
          缺坐标
        </span>
      </div>
    </header>

    <section class="geo-pipeline" aria-label="地理校准流水线">
      <article v-for="stage in geoPipeline" :key="stage.label" :class="`geo-pipeline-${stage.tone}`">
        <span>{{ stage.label }}</span>
        <strong>{{ stage.value }}</strong>
        <small>{{ stage.detail }}</small>
      </article>
    </section>

    <section v-if="opsCards.length" class="ops-summary" aria-label="运营概览">
      <article v-for="card in opsCards" :key="card.label">
        <strong>{{ card.value }}</strong>
        <span>{{ card.label }}</span>
        <small>{{ card.hint }}</small>
      </article>
    </section>

    <section v-if="successMessage" class="notice geo-notice">
      <CheckCircle2 :size="18" aria-hidden="true" />
      {{ successMessage }}
    </section>

    <section v-if="errorMessage" class="notice notice-error geo-notice">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
      <button type="button" @click="loadFirstPage">
        <RefreshCw :size="16" aria-hidden="true" />
        重试
      </button>
    </section>

    <section class="geo-command-bar">
      <form class="geo-search" role="search" @submit.prevent="loadFirstPage">
        <label class="compact-field">
          <span>城市</span>
          <input v-model="city" type="text" autocomplete="off" />
        </label>
        <label class="compact-field">
          <span>店铺</span>
          <input v-model="keyword" type="search" placeholder="店名关键词" />
        </label>
        <button class="geo-icon-button primary" type="submit" :disabled="loading">
          <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
          <Search v-else :size="17" aria-hidden="true" />
          查询
        </button>
      </form>
      <button class="geo-icon-button" type="button" :disabled="syncing" @click="handleSyncIndex">
        <Loader2 v-if="syncing" class="spin" :size="17" aria-hidden="true" />
        <Database v-else :size="17" aria-hidden="true" />
        重建 GEO
      </button>
    </section>

    <section class="geo-amap-health" :class="{ degraded: amapStatus?.circuitOpen }" aria-label="高德服务状态">
      <div class="geo-amap-title">
        <Activity :size="19" aria-hidden="true" />
        <div>
          <strong>高德 Web 服务</strong>
          <span>{{ amapHealthText }}</span>
        </div>
      </div>
      <div class="geo-amap-metrics">
        <span>
          <small>本分钟</small>
          {{ amapStatus?.currentMinuteCalls || 0 }}/{{ amapStatus?.rateLimitPerMinute || 0 }}
        </span>
        <span>
          <small>成功/失败</small>
          {{ amapMetricSummary.success }}/{{ amapMetricSummary.failure }}
        </span>
        <span>
          <small>缓存命中</small>
          {{ amapMetricSummary.cacheHit }}
        </span>
        <span>
          <small>限流</small>
          {{ amapMetricSummary.limited }}
        </span>
      </div>
      <button class="geo-mini-button" type="button" :disabled="amapStatusLoading" title="刷新高德服务状态" @click="loadAmapStatus">
        <Loader2 v-if="amapStatusLoading" class="spin" :size="15" aria-hidden="true" />
        <RefreshCw v-else :size="15" aria-hidden="true" />
      </button>
    </section>

    <section class="geo-batch-panel" aria-label="批量校准任务">
      <div class="geo-batch-copy">
        <Database :size="20" aria-hidden="true" />
        <div>
          <strong>批量地址解析</strong>
          <span>按当前店铺关键词创建后台任务，默认只处理缺坐标或未验证店铺。</span>
        </div>
      </div>
      <div class="geo-batch-controls">
        <label class="compact-field batch-limit">
          <span>上限</span>
          <input v-model.number="batchLimit" type="number" min="1" max="100" />
        </label>
        <label class="geo-check">
          <input v-model="batchOnlyMissing" type="checkbox" />
          只补未验证
        </label>
        <label class="geo-check">
          <input v-model="batchVerified" type="checkbox" />
          直接验证
        </label>
        <button class="geo-icon-button primary" type="button" :disabled="batchLoading || batchRunning" @click="handleStartBatch">
          <Loader2 v-if="batchLoading || batchRunning" class="spin" :size="17" aria-hidden="true" />
          <Database v-else :size="17" aria-hidden="true" />
          批量补坐标
        </button>
      </div>
      <div v-if="batchJob" class="geo-batch-status">
        <div>
          <span>{{ batchJob.status }}</span>
          <strong>{{ batchJob.processed }}/{{ batchJob.total }}</strong>
          <small>成功 {{ batchJob.success }} · 失败 {{ batchJob.failed }}</small>
        </div>
        <div class="geo-progress-track" aria-label="批量任务进度">
          <span :style="{ width: `${batchProgress}%` }" />
        </div>
        <p>{{ batchJob.message || '等待状态更新' }}</p>
        <div class="geo-batch-actions">
          <button class="geo-mini-text-button" type="button" :disabled="!batchRunning" @click="handleCancelBatch(batchJob)">
            <Ban :size="15" aria-hidden="true" />
            取消
          </button>
          <button class="geo-mini-text-button" type="button" :disabled="!batchJob.failed" @click="handleRetryBatch(batchJob)">
            <RefreshCw :size="15" aria-hidden="true" />
            重试失败
          </button>
        </div>
      </div>
      <div v-if="batchJob && visibleBatchItems.length" class="geo-batch-items" aria-label="批量任务明细">
        <article v-for="item in visibleBatchItems" :key="item.id" class="geo-batch-item">
          <div>
            <strong>{{ item.shopName }}</strong>
            <span>{{ item.message || '等待处理' }}</span>
          </div>
          <small :class="`batch-${item.status}`">{{ item.status }}</small>
        </article>
      </div>
      <div v-else-if="batchItemsLoading" class="geo-batch-items">
        <div class="loading-row voucher-empty">
          <Loader2 class="spin" :size="18" aria-hidden="true" />
          正在加载任务明细
        </div>
      </div>
      <div v-if="recentBatchJobs.length" class="geo-job-history" aria-label="最近批量任务">
        <article v-for="job in recentBatchJobs" :key="job.jobId" class="geo-job-row" :class="{ active: batchJob?.jobId === job.jobId }">
          <button type="button" @click="selectBatchJob(job)">
            <strong>{{ job.keyword || job.city || '批量任务' }}</strong>
            <span>{{ job.status }} · {{ job.processed }}/{{ job.total }} · 失败 {{ job.failed }}</span>
          </button>
        </article>
      </div>
      <div v-else-if="batchJobsLoading" class="geo-job-history">
        <div class="loading-row voucher-empty">
          <Loader2 class="spin" :size="18" aria-hidden="true" />
          正在加载历史任务
        </div>
      </div>
    </section>

    <section class="geo-workbench">
      <section class="geo-shop-panel" aria-label="店铺列表">
        <div class="geo-panel-head">
          <div class="section-heading">
            <Store :size="19" aria-hidden="true" />
            <h2>店铺池</h2>
          </div>
          <div class="geo-segmented" aria-label="地理状态筛选">
            <button
              v-for="option in statusOptions"
              :key="option.value"
              type="button"
              :class="{ active: statusFilter === option.value }"
              @click="statusFilter = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <div v-if="loading" class="feed-skeleton shop-skeleton">
          <article v-for="item in 4" :key="item" class="skeleton-card" />
        </div>

        <div v-else-if="filteredShops.length" class="geo-shop-list">
          <article
            v-for="shop in filteredShops"
            :key="shop.id"
            class="geo-shop-row"
            :class="{ selected: selectedShop?.id === shop.id }"
            @click="selectShop(shop)"
          >
            <div class="geo-shop-main">
              <strong>{{ shop.name }}</strong>
              <span>{{ formatAddress(shop) }}</span>
            </div>
            <span class="geo-status-pill" :class="statusClass(shop)">{{ statusLabel(shop) }}</span>
            <span class="geo-coordinate">{{ formatCoordinates(shop) }}</span>
            <div class="geo-row-actions">
              <button type="button" :disabled="candidatesLoading" title="查找高德 POI" @click.stop="selectShop(shop); loadCandidates(shop)">
                <Search :size="16" aria-hidden="true" />
              </button>
              <button type="button" :disabled="geocodingShopId === shop.id" title="按地址解析坐标" @click.stop="selectShop(shop); handleGeocode(shop, false)">
                <MapPin :size="16" aria-hidden="true" />
              </button>
              <RouterLink :to="{ name: 'shop-detail', params: { id: shop.id } }" title="查看店铺">
                <ExternalLink :size="16" aria-hidden="true" />
              </RouterLink>
            </div>
          </article>
        </div>

        <div v-else class="empty-state">
          <Search :size="18" aria-hidden="true" />
          当前筛选没有店铺
        </div>

        <button class="load-more" type="button" :disabled="loading || loadingMore || !hasMore" @click="loadMore">
          <Loader2 v-if="loadingMore" class="spin" :size="17" aria-hidden="true" />
          {{ getPaginationLabel(loadingMore, hasMore) }}
        </button>
      </section>

      <aside class="geo-inspector" aria-label="校准工作区">
        <template v-if="selectedShop">
          <div class="geo-inspector-head">
            <div>
              <p class="eyebrow">Selected shop</p>
              <h2>{{ selectedShop.name }}</h2>
              <span>{{ selectedCoordinates }}</span>
            </div>
            <span class="geo-status-pill" :class="statusClass(selectedShop)">{{ statusLabel(selectedShop) }}</span>
          </div>

          <div class="geo-meta-grid">
            <span>
              <small>POI</small>
              {{ selectedShop.poiId || '未绑定' }}
            </span>
            <span>
              <small>ADCODE</small>
              {{ selectedShop.adcode || '未设置' }}
            </span>
            <span>
              <small>来源</small>
              {{ selectedShop.geoSource || '未标记' }}
            </span>
            <span>
              <small>更新</small>
              {{ formatDate(selectedShop.geoUpdatedAt) }}
            </span>
          </div>

          <div class="geo-tool-band">
            <label class="compact-field">
              <span>地址解析</span>
              <input v-model="addressDraft" type="text" autocomplete="off" />
            </label>
            <div class="geo-inline-actions">
              <button class="geo-icon-button" type="button" :disabled="geocodingShopId === selectedShop.id" @click="handleGeocode(selectedShop, false)">
                <Loader2 v-if="geocodingShopId === selectedShop.id" class="spin" :size="17" aria-hidden="true" />
                <MapPin v-else :size="17" aria-hidden="true" />
                待确认写入
              </button>
              <button class="geo-icon-button primary" type="button" :disabled="geocodingShopId === selectedShop.id" @click="handleGeocode(selectedShop, true)">
                <BadgeCheck :size="17" aria-hidden="true" />
                解析并验证
              </button>
            </div>
          </div>

          <div class="geo-tool-band">
            <form class="geo-poi-search" @submit.prevent="loadCandidates(selectedShop)">
              <label class="compact-field">
                <span>POI 关键词</span>
                <input v-model="poiKeyword" type="search" autocomplete="off" />
              </label>
              <button class="geo-icon-button primary" type="submit" :disabled="candidatesLoading">
                <Loader2 v-if="candidatesLoading" class="spin" :size="17" aria-hidden="true" />
                <Search v-else :size="17" aria-hidden="true" />
                查 POI
              </button>
            </form>

            <p v-if="candidateError" class="geo-inline-error">
              <AlertCircle :size="16" aria-hidden="true" />
              {{ candidateError }}
            </p>

            <div v-if="candidates.length" class="geo-candidate-list">
              <article v-for="poi in candidates" :key="poi.id" class="geo-candidate-row" @click="fillManualFromPoi(poi)">
                <div>
                  <strong>{{ poi.name }}</strong>
                  <span>{{ poi.adName || poi.cityName || city }} · {{ poi.address || '地址未返回' }}</span>
                  <small>{{ poi.type || 'POI' }} · {{ formatCoordinates(poi) }}</small>
                </div>
                <button type="button" :disabled="savingShopId === selectedShop.id" @click.stop="confirmCandidate(poi)">
                  <Loader2 v-if="savingShopId === selectedShop.id" class="spin" :size="16" aria-hidden="true" />
                  确认
                </button>
              </article>
            </div>
          </div>

          <form class="geo-manual-form" @submit.prevent="saveManualGeo">
            <div class="geo-panel-head">
              <div class="section-heading">
                <SlidersHorizontal :size="19" aria-hidden="true" />
                <h2>手动校准</h2>
              </div>
            </div>
            <label class="compact-field">
              <span>经度 X（GCJ-02）</span>
              <input v-model="manualForm.x" inputmode="decimal" required />
            </label>
            <label class="compact-field">
              <span>纬度 Y（GCJ-02）</span>
              <input v-model="manualForm.y" inputmode="decimal" required />
            </label>
            <label class="compact-field">
              <span>POI ID</span>
              <input v-model="manualForm.poiId" />
            </label>
            <label class="compact-field">
              <span>ADCODE</span>
              <input v-model="manualForm.adcode" />
            </label>
            <label class="compact-field">
              <span>来源</span>
              <input v-model="manualForm.geoSource" />
            </label>
            <label class="compact-field">
              <span>状态</span>
              <select v-model="manualForm.geoStatus">
                <option value="verified">已验证</option>
                <option value="pending">待确认</option>
              </select>
            </label>
            <button class="geo-icon-button primary wide" type="submit" :disabled="savingShopId === selectedShop.id">
              <Loader2 v-if="savingShopId === selectedShop.id" class="spin" :size="17" aria-hidden="true" />
              <CheckCircle2 v-else :size="17" aria-hidden="true" />
              保存坐标
            </button>
          </form>
        </template>

        <div v-else class="geo-inspector-empty">
          <MapPin :size="30" aria-hidden="true" />
          <strong>选择一家店开始校准</strong>
          <span>左侧查询店铺后，可以查高德 POI、解析地址、手动写回坐标。</span>
        </div>

        <section class="geo-audit-panel" aria-label="操作审计">
          <div class="geo-panel-head">
            <div class="section-heading">
              <Database :size="19" aria-hidden="true" />
              <div>
                <h2>{{ auditTitle }}</h2>
                <p v-if="auditPolicyText" class="geo-panel-subtitle">{{ auditPolicyText }}</p>
              </div>
            </div>
            <div class="geo-mini-actions">
              <button v-if="canCleanupAudit" class="geo-mini-button" type="button" :disabled="auditCleaning" title="清理过期审计日志" @click="cleanupAuditOpen = true">
                <Loader2 v-if="auditCleaning" class="spin" :size="15" aria-hidden="true" />
                <Ban v-else :size="15" aria-hidden="true" />
              </button>
              <button class="geo-mini-button" type="button" :disabled="auditExporting" title="导出审计日志" @click="handleExportAudit">
                <Loader2 v-if="auditExporting" class="spin" :size="15" aria-hidden="true" />
                <Download v-else :size="15" aria-hidden="true" />
              </button>
              <button class="geo-mini-button" type="button" :disabled="auditLoading" title="刷新审计日志" @click="loadAuditLogs(selectedShop?.id)">
                <Loader2 v-if="auditLoading" class="spin" :size="15" aria-hidden="true" />
                <RefreshCw v-else :size="15" aria-hidden="true" />
              </button>
            </div>
          </div>

          <div v-if="auditLoading" class="loading-row voucher-empty">
            <Loader2 class="spin" :size="18" aria-hidden="true" />
            正在加载
          </div>

          <div v-else-if="auditLogs.length" class="geo-audit-list">
            <article v-for="log in auditLogs" :key="log.id" class="geo-audit-row">
              <div>
                <strong>{{ formatAuditAction(log.action) }}</strong>
                <span>{{ log.actorName || `用户 ${log.actorId || '-'}` }} · {{ formatDate(log.createTime) }}</span>
              </div>
              <small :class="{ failed: !log.success }">{{ log.success ? '成功' : log.errorMessage || '失败' }}</small>
            </article>
          </div>

          <div v-else class="empty-state voucher-empty">暂无审计记录</div>
        </section>
      </aside>
    </section>

    <ConfirmDialog
      :open="cleanupAuditOpen"
      title="确认清理过期审计"
      message="这会按留存策略删除过期审计日志。建议先导出需要留档的记录，再执行清理。"
      confirm-text="清理审计"
      tone="danger"
      :busy="auditCleaning"
      @cancel="cleanupAuditOpen = false"
      @confirm="handleCleanupAudit"
    />
  </section>
</template>
