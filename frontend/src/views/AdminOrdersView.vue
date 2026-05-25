<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { AlertCircle, BadgeCheck, Clock, Database, Loader2, ReceiptText, RefreshCw, Search, Store, Ticket } from '@lucide/vue';
import { fetchAdminVoucherOrder, fetchOperationLogs, useVoucherOrder } from '../api/citycopilot';
import { authState } from '../composables/auth';
import { formatCents } from '../utils/assets';
import { hasPermission } from '../utils/permissions';
import type { OperationLog, VoucherOrder } from '../types';

const route = useRoute();
const router = useRouter();

const orderIdInput = ref('');
const order = ref<VoucherOrder | null>(null);
const auditLogs = ref<OperationLog[]>([]);
const loading = ref(false);
const usingOrder = ref(false);
const auditLoading = ref(false);
const errorMessage = ref('');

const canUseOrder = computed(() => order.value?.state === 'paid' || order.value?.status === 2);
const canReadAudit = computed(() => hasPermission(authState.user, 'audit:read'));

function formatTime(value?: string) {
  if (!value) {
    return '未记录';
  }
  return value.replace('T', ' ').slice(0, 16);
}

function statusClass(value: VoucherOrder) {
  return `order-status-${value.state || 'unknown'}`;
}

function parseOrderId() {
  const value = Number(orderIdInput.value.trim());
  return Number.isFinite(value) && value > 0 ? value : null;
}

async function loadOrder() {
  const orderId = parseOrderId();
  if (!orderId) {
    errorMessage.value = '请输入有效订单号';
    return;
  }
  loading.value = true;
  errorMessage.value = '';
  try {
    order.value = await fetchAdminVoucherOrder(orderId);
    await router.replace({ name: 'admin-orders', query: { id: String(orderId) } });
    await loadOrderAudit(orderId);
  } catch (error) {
    order.value = null;
    auditLogs.value = [];
    errorMessage.value = error instanceof Error ? error.message : '订单查询失败';
  } finally {
    loading.value = false;
  }
}

async function loadOrderAudit(orderId: number) {
  if (!canReadAudit.value) {
    auditLogs.value = [];
    return;
  }
  auditLoading.value = true;
  try {
    auditLogs.value = await fetchOperationLogs({ current: 1, module: 'order', targetType: 'voucher_order', targetId: orderId });
  } catch {
    auditLogs.value = [];
  } finally {
    auditLoading.value = false;
  }
}

async function handleUseOrder() {
  if (!order.value || !canUseOrder.value || !window.confirm('确认核销这张券？')) {
    return;
  }
  usingOrder.value = true;
  errorMessage.value = '';
  try {
    order.value = await useVoucherOrder(order.value.id);
    await loadOrderAudit(order.value.id);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '核销失败';
  } finally {
    usingOrder.value = false;
  }
}

onMounted(() => {
  const id = typeof route.query.id === 'string' ? route.query.id : '';
  if (id) {
    orderIdInput.value = id;
    loadOrder();
  }
});
</script>

<template>
  <section class="orders-shell admin-orders-shell">
    <header class="orders-header">
      <div>
        <p class="eyebrow">运营订单</p>
        <h1>券核销</h1>
      </div>
      <button type="button" :disabled="loading" @click="loadOrder">
        <RefreshCw :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <form class="admin-order-search" @submit.prevent="loadOrder">
      <label>
        <span>订单号</span>
        <input v-model="orderIdInput" inputmode="numeric" autocomplete="off" placeholder="输入券订单号" />
      </label>
      <button type="submit" :disabled="loading">
        <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
        <Search v-else :size="17" aria-hidden="true" />
        查询
      </button>
    </form>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <div v-if="loading" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在查询订单
    </div>

    <template v-else-if="order">
    <article class="order-card admin-order-card">
      <div class="order-main">
        <div class="order-icon">
          <ReceiptText :size="21" aria-hidden="true" />
        </div>
        <div>
          <div class="order-title-row">
            <h2>{{ order.voucherTitle || '券订单' }}</h2>
            <span class="order-status" :class="statusClass(order)">
              {{ order.statusText || '状态待确认' }}
            </span>
          </div>
          <p>{{ order.voucherSubTitle || order.voucherRules || '没有更多券说明' }}</p>
          <div class="order-meta">
            <span>
              <Ticket :size="15" aria-hidden="true" />
              {{ order.id }}
            </span>
            <span v-if="order.shopName">
              <Store :size="15" aria-hidden="true" />
              {{ order.shopName }}
            </span>
            <span>
              <Clock :size="15" aria-hidden="true" />
              下单 {{ formatTime(order.createTime) }}
            </span>
          </div>
          <div class="admin-order-timeline">
            <span>支付 {{ formatTime(order.payTime) }}</span>
            <span>核销 {{ formatTime(order.useTime) }}</span>
            <span>退款 {{ formatTime(order.refundTime) }}</span>
          </div>
        </div>
      </div>

      <div class="order-side">
        <strong>{{ formatCents(order.payValue) }}</strong>
        <small>抵扣 {{ formatCents(order.actualValue) }}</small>
        <button type="button" :disabled="!canUseOrder || usingOrder" @click="handleUseOrder">
          <Loader2 v-if="usingOrder" class="spin" :size="14" aria-hidden="true" />
          <BadgeCheck v-else :size="14" aria-hidden="true" />
          核销
        </button>
      </div>
    </article>

    <section v-if="canReadAudit" class="admin-order-audit" aria-label="订单操作日志">
      <div class="admin-order-audit-head">
        <div>
          <Database :size="18" aria-hidden="true" />
          <strong>操作日志</strong>
        </div>
        <button type="button" :disabled="auditLoading" @click="loadOrderAudit(order.id)">
          <Loader2 v-if="auditLoading" class="spin" :size="14" aria-hidden="true" />
          <RefreshCw v-else :size="14" aria-hidden="true" />
        </button>
      </div>
      <div v-if="auditLogs.length" class="admin-order-audit-list">
        <article v-for="log in auditLogs" :key="log.id">
          <span>{{ log.action }}</span>
          <strong>{{ log.success ? '成功' : '失败' }}</strong>
          <small>{{ formatTime(log.createTime) }}</small>
        </article>
      </div>
      <div v-else class="admin-order-audit-empty">
        {{ auditLoading ? '正在读取日志' : '暂无订单操作日志' }}
      </div>
    </section>
    </template>

    <div v-else class="empty-state orders-empty">
      输入订单号后查询
    </div>
  </section>
</template>
