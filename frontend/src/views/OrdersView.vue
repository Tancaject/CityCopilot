<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { AlertCircle, Clock, CreditCard, Loader2, ReceiptText, RefreshCw, Store, Ticket, Undo2 } from '@lucide/vue';
import { cancelVoucherOrder, fetchMyVoucherOrders, fetchVoucherOrder, payVoucherOrder, refundVoucherOrder } from '../api/citycopilot';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import { formatCents } from '../utils/assets';
import { getErrorMessage } from '../utils/errors';
import { getPaginationLabel } from '../utils/labels';
import type { VoucherOrder } from '../types';

const route = useRoute();
type OrderAction = 'cancel' | 'refund';

const orders = ref<VoucherOrder[]>([]);
const currentPage = ref(1);
const loading = ref(false);
const loadingMore = ref(false);
const cancelingId = ref<string | null>(null);
const payingId = ref<string | null>(null);
const refundingId = ref<string | null>(null);
const hasMore = ref(true);
const errorMessage = ref('');
const paymentMessage = ref('');
let paymentPollTimer: number | undefined;
const pendingOrderAction = ref<{ type: OrderAction; order: VoucherOrder } | null>(null);

const highlightedOrderId = computed(() => {
  const value = typeof route.query.highlight === 'string' ? route.query.highlight.trim() : '';
  return /^[1-9]\d*$/.test(value) ? value : null;
});
const pendingOrderDialog = computed(() => {
  const pending = pendingOrderAction.value;
  if (!pending) {
    return {
      title: '',
      message: '',
      confirmText: '确认',
      tone: 'warning' as const,
      busy: false,
    };
  }
  const name = pending.order.voucherTitle || `订单 #${pending.order.id}`;
  if (pending.type === 'refund') {
    return {
      title: '确认申请退款',
      message: `将为「${name}」发起退款，订单会从可使用状态切换为退款流程。`,
      confirmText: '申请退款',
      tone: 'danger' as const,
      busy: refundingId.value === pending.order.id,
    };
  }
  return {
    title: '确认取消订单',
    message: `将取消「${name}」，取消后需要重新下单才能使用这张券。`,
    confirmText: '取消订单',
    tone: 'warning' as const,
    busy: cancelingId.value === pending.order.id,
  };
});

function formatTime(value?: string) {
  if (!value) {
    return '时间待确认';
  }
  return value.replace('T', ' ').slice(0, 16);
}

function statusClass(order: VoucherOrder) {
  return `order-status-${order.state || 'unknown'}`;
}

function canCancel(order: VoucherOrder) {
  return order.state === 'unpaid' || order.status === 1;
}

function canPay(order: VoucherOrder) {
  return order.state === 'unpaid' || order.status === 1;
}

function canRefund(order: VoucherOrder) {
  return order.state === 'paid' || order.status === 2;
}

function updateOrder(updated: VoucherOrder) {
  orders.value = orders.value.map((item) => (item.id === updated.id ? updated : item));
}

function mergeOrders(nextOrders: VoucherOrder[], reset = false) {
  const source = reset ? [] : orders.value;
  const seen = new Set<string>();
  orders.value = source.concat(nextOrders).filter((order) => {
    if (!order.id || seen.has(order.id)) {
      return false;
    }
    seen.add(order.id);
    return true;
  });
}

async function includeHighlightedOrder() {
  const orderId = highlightedOrderId.value;
  if (!orderId || orders.value.some((order) => order.id === orderId)) {
    return;
  }
  const order = await fetchVoucherOrder(orderId);
  orders.value = [order].concat(orders.value);
}

async function loadOrders() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const firstPage = await fetchMyVoucherOrders(1);
    orders.value = firstPage;
    currentPage.value = 1;
    hasMore.value = firstPage.length > 0;
    await includeHighlightedOrder();
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '订单加载失败');
  } finally {
    loading.value = false;
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
    const nextOrders = await fetchMyVoucherOrders(nextPage);
    if (!nextOrders.length) {
      hasMore.value = false;
      return;
    }
    mergeOrders(nextOrders);
    currentPage.value = nextPage;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '加载更多订单失败');
  } finally {
    loadingMore.value = false;
  }
}

async function handleCancel(order: VoucherOrder) {
  if (!canCancel(order)) {
    return;
  }
  pendingOrderAction.value = { type: 'cancel', order };
}

async function confirmOrderAction() {
  const pending = pendingOrderAction.value;
  if (!pending) {
    return;
  }
  if (pending.type === 'cancel') {
    await cancelOrder(pending.order);
    return;
  }
  await refundOrder(pending.order);
}

async function cancelOrder(order: VoucherOrder) {
  cancelingId.value = order.id;
  errorMessage.value = '';
  try {
    const updated = await cancelVoucherOrder(order.id);
    updateOrder(updated);
    pendingOrderAction.value = null;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '取消订单失败');
  } finally {
    cancelingId.value = null;
  }
}

async function handlePay(order: VoucherOrder) {
  if (!canPay(order) || payingId.value) {
    return;
  }
  payingId.value = order.id;
  errorMessage.value = '';
  paymentMessage.value = '正在发起模拟支付';
  try {
    const updated = await payVoucherOrder(order.id);
    if (payingId.value !== order.id) return;
    updateOrder(updated);
    paymentMessage.value = '支付已发起，正在等待渠道回调';
    await pollPayment(order.id);
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '支付失败');
    paymentMessage.value = '';
    payingId.value = null;
  }
}

async function pollPayment(orderId: string, attempt = 0) {
  try {
    const updated = await fetchVoucherOrder(orderId);
    if (payingId.value !== orderId) return;
    updateOrder(updated);
    if (updated.state !== 'unpaid') {
      paymentMessage.value = updated.state === 'paid'
        ? '渠道回调已确认，支付成功'
        : `订单当前状态：${updated.statusText}。已关单的迟到支付会自动退款。`;
      payingId.value = null;
    } else if (attempt >= 10) {
      paymentMessage.value = '支付结果尚未确认，请稍后刷新订单查看';
      payingId.value = null;
    } else {
      paymentPollTimer = window.setTimeout(() => { void pollPayment(orderId, attempt + 1); }, 1000);
    }
  } catch (error) {
    paymentMessage.value = '';
    errorMessage.value = getErrorMessage(error, '支付结果查询失败，请刷新订单查看');
    payingId.value = null;
  }
}

async function handleRefund(order: VoucherOrder) {
  if (!canRefund(order)) {
    return;
  }
  pendingOrderAction.value = { type: 'refund', order };
}

async function refundOrder(order: VoucherOrder) {
  refundingId.value = order.id;
  errorMessage.value = '';
  try {
    const updated = await refundVoucherOrder(order.id);
    updateOrder(updated);
    pendingOrderAction.value = null;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '退款失败');
  } finally {
    refundingId.value = null;
  }
}

onMounted(loadOrders);
onUnmounted(() => {
  window.clearTimeout(paymentPollTimer);
  payingId.value = null;
});

watch(
  () => route.query.highlight,
  () => {
    includeHighlightedOrder().catch((error) => {
      errorMessage.value = getErrorMessage(error, '订单状态同步失败');
    });
  },
);
</script>

<template>
  <section class="orders-shell">
    <header class="orders-header">
      <div>
        <p class="eyebrow">我的订单</p>
        <h1>券订单</h1>
      </div>
      <button type="button" @click="loadOrders">
        <RefreshCw :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
      <button type="button" @click="loadOrders">
        <RefreshCw :size="16" aria-hidden="true" />
        重试
      </button>
    </section>

    <p v-if="paymentMessage" class="notice" role="status">{{ paymentMessage }}</p>

    <div v-if="loading" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在加载订单
    </div>

    <section v-else-if="orders.length" class="order-list">
      <article
        v-for="order in orders"
        :key="order.id"
        class="order-card"
        :class="{ highlighted: highlightedOrderId === order.id }"
      >
        <div class="order-main">
          <div class="order-icon">
            <ReceiptText :size="21" aria-hidden="true" />
          </div>
          <div>
            <div class="order-title-row">
              <h2>{{ order.voucherTitle || '券订单处理中' }}</h2>
              <span class="order-status" :class="statusClass(order)">
                {{ order.statusText || '状态待确认' }}
              </span>
            </div>
            <p v-if="order.voucherSubTitle || order.voucherRules">{{ order.voucherSubTitle || order.voucherRules }}</p>
            <div class="order-meta">
              <span>
                <Ticket :size="15" aria-hidden="true" />
                {{ order.id }}
              </span>
              <span>
                <Clock :size="15" aria-hidden="true" />
                {{ formatTime(order.createTime || order.updateTime) }}
              </span>
              <span v-if="order.shopName">
                <Store :size="15" aria-hidden="true" />
                {{ order.shopName }}
              </span>
            </div>
          </div>
        </div>

        <div class="order-side">
          <strong>{{ formatCents(order.payValue) }}</strong>
          <small>抵扣 {{ formatCents(order.actualValue) }}</small>
          <RouterLink v-if="order.shopId" :to="{ name: 'shop-detail', params: { id: order.shopId } }">
            查看店铺
          </RouterLink>
          <button v-if="canPay(order)" type="button" :disabled="payingId !== null" @click="handlePay(order)">
            <Loader2 v-if="payingId === order.id" class="spin" :size="14" aria-hidden="true" />
            <CreditCard v-else :size="14" aria-hidden="true" />
            {{ payingId === order.id ? '等待支付回调' : '发起模拟支付' }}
          </button>
          <button v-if="canCancel(order)" type="button" :disabled="cancelingId === order.id" @click="handleCancel(order)">
            <Loader2 v-if="cancelingId === order.id" class="spin" :size="14" aria-hidden="true" />
            取消订单
          </button>
          <button v-if="canRefund(order)" type="button" :disabled="refundingId === order.id" @click="handleRefund(order)">
            <Loader2 v-if="refundingId === order.id" class="spin" :size="14" aria-hidden="true" />
            <Undo2 v-else :size="14" aria-hidden="true" />
            申请退款
          </button>
        </div>
      </article>

      <button class="load-more" type="button" :disabled="loadingMore || !hasMore" @click="loadMore">
        <Loader2 v-if="loadingMore" class="spin" :size="17" aria-hidden="true" />
        {{ getPaginationLabel(loadingMore, hasMore, '没有更多订单') }}
      </button>
    </section>

    <div v-else class="empty-state orders-empty">
      暂时没有订单
    </div>

    <ConfirmDialog
      :open="Boolean(pendingOrderAction)"
      :title="pendingOrderDialog.title"
      :message="pendingOrderDialog.message"
      :confirm-text="pendingOrderDialog.confirmText"
      :tone="pendingOrderDialog.tone"
      :busy="pendingOrderDialog.busy"
      @cancel="pendingOrderAction = null"
      @confirm="confirmOrderAction"
    />
  </section>
</template>
