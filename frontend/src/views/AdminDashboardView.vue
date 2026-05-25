<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  AlertCircle,
  Braces,
  ClipboardCheck,
  Database,
  FileText,
  Loader2,
  MapPin,
  ReceiptText,
  RefreshCw,
  ShieldCheck,
  Store,
  Ticket,
  Users,
} from '@lucide/vue';
import { RouterLink } from 'vue-router';
import { fetchOpsSummary } from '../api/citycopilot';
import { authState } from '../composables/auth';
import { hasPermission } from '../utils/permissions';
import type { OpsSummary } from '../types';

const summary = ref<OpsSummary | null>(null);
const loading = ref(false);
const errorMessage = ref('');

const quickLinks = computed(() =>
  [
    {
      name: 'admin-shops',
      label: '店铺资料',
      metric: summary.value?.shops,
      note: `${summary.value?.missingGeo ?? 0} 家缺坐标`,
      permission: 'shop:write',
      icon: Store,
    },
    {
      name: 'admin-vouchers',
      label: '优惠券配置',
      metric: summary.value?.vouchers,
      note: `${summary.value?.activeVouchers ?? 0} 张上架`,
      permission: 'voucher:write',
      icon: Ticket,
    },
    {
      name: 'admin-orders',
      label: '券核销',
      metric: summary.value?.unpaidOrders,
      note: `${summary.value?.todayOrders ?? 0} 个今日订单`,
      permission: 'order:write',
      icon: ClipboardCheck,
    },
    {
      name: 'geo-shops-admin',
      label: '地理校准',
      metric: summary.value?.pendingGeo,
      note: `${summary.value?.verifiedGeo ?? 0} 家已确认`,
      permission: 'geo:read',
      icon: MapPin,
    },
    {
      name: 'admin-users',
      label: '用户角色',
      metric: summary.value?.users,
      note: '角色和权限',
      permission: 'user:write',
      icon: ShieldCheck,
    },
    {
      name: 'admin-blogs',
      label: '内容治理',
      metric: summary.value?.blogs,
      note: `${summary.value?.todayBlogs ?? 0} 篇今日新增`,
      permission: 'content:write',
      icon: FileText,
    },
    {
      name: 'admin-audit',
      label: '操作审计',
      metric: summary.value?.failedAuditsToday,
      note: '日志查询与导出',
      permission: 'audit:read',
      icon: Database,
    },
    {
      name: 'admin-api',
      label: 'API 契约',
      metric: 'API',
      note: '路由、错误码、权限',
      permission: 'audit:read',
      icon: Braces,
    },
  ].filter((link) => hasPermission(authState.user, link.permission)),
);

const riskItems = computed(() => [
  {
    label: '缺坐标店铺',
    value: summary.value?.missingGeo ?? 0,
    tone: (summary.value?.missingGeo ?? 0) > 0 ? 'warn' : 'ok',
  },
  {
    label: '待确认坐标',
    value: summary.value?.pendingGeo ?? 0,
    tone: (summary.value?.pendingGeo ?? 0) > 0 ? 'warn' : 'ok',
  },
  {
    label: '今日失败审计',
    value: summary.value?.failedAuditsToday ?? 0,
    tone: (summary.value?.failedAuditsToday ?? 0) > 0 ? 'bad' : 'ok',
  },
  {
    label: '已取消订单',
    value: summary.value?.cancelledOrders ?? 0,
    tone: 'muted',
  },
]);

async function loadSummary() {
  loading.value = true;
  errorMessage.value = '';
  try {
    summary.value = await fetchOpsSummary();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '运营概览加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadSummary);
</script>

<template>
  <section class="admin-dashboard">
    <header class="orders-header">
      <div>
        <p class="eyebrow">运营后台</p>
        <h1>业务概览</h1>
      </div>
      <button type="button" :disabled="loading" @click="loadSummary">
        <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
        <RefreshCw v-else :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <div v-if="loading && !summary" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在读取运营数据
    </div>

    <template v-else>
      <section class="admin-dashboard-kpis" aria-label="核心指标">
        <article>
          <Users :size="20" aria-hidden="true" />
          <span>用户</span>
          <strong>{{ summary?.users ?? 0 }}</strong>
        </article>
        <article>
          <Store :size="20" aria-hidden="true" />
          <span>店铺</span>
          <strong>{{ summary?.shops ?? 0 }}</strong>
        </article>
        <article>
          <ReceiptText :size="20" aria-hidden="true" />
          <span>订单</span>
          <strong>{{ summary?.orders ?? 0 }}</strong>
        </article>
        <article>
          <Ticket :size="20" aria-hidden="true" />
          <span>券包</span>
          <strong>{{ summary?.vouchers ?? 0 }}</strong>
        </article>
        <article>
          <FileText :size="20" aria-hidden="true" />
          <span>笔记</span>
          <strong>{{ summary?.blogs ?? 0 }}</strong>
        </article>
      </section>

      <section class="admin-dashboard-grid">
        <RouterLink v-for="link in quickLinks" :key="link.name" class="admin-dashboard-link" :to="{ name: link.name }">
          <component :is="link.icon" :size="20" aria-hidden="true" />
          <div>
            <strong>{{ link.label }}</strong>
            <span>{{ link.note }}</span>
          </div>
          <em>{{ link.metric ?? 0 }}</em>
        </RouterLink>
      </section>

      <section class="admin-dashboard-risks" aria-label="待处理事项">
        <article v-for="item in riskItems" :key="item.label" :class="`risk-${item.tone}`">
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </article>
      </section>
    </template>
  </section>
</template>
