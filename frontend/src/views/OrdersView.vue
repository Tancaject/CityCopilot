<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { AlertCircle, Clock, CreditCard, Loader2, ReceiptText, RefreshCw, Store, Ticket, Undo2 } from '@lucide/vue';
import { cancelVoucherOrder, fetchMyVoucherOrders, fetchVoucherOrder, payVoucherOrder, refundVoucherOrder } from '../api/citycopilot';
import { formatCents } from '../utils/assets';
import type { VoucherOrder } from '../types';

const route = useRoute();

const orders = ref<VoucherOrder[]>([]);
const currentPage = ref(1);
const loading = ref(false);
const loadingMore = ref(false);
const cancelingId = ref<number | null>(null);
const payingId = ref<number | null>(null);
const refundingId = ref<number | null>(null);
const hasMore = ref(true);
const errorMessage = ref('');

const highlightedOrderId = computed(() => {
  const value = Number(route.query.highlight);
  return Number.isFinite(value) && value > 0 ? value : null;
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
  const seen = new Set<number>();
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
    errorMessage.value = error instanceof Error ? error.message : '订单加载失败';
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
    errorMessage.value = error instanceof Error ? error.message : '加载更多订单失败';
  } finally {
    loadingMore.value = false;
  }
}

async function handleCancel(order: VoucherOrder) {
  if (!canCancel(order) || !window.confirm('确认取消这张券订单？')) {
    return;
  }
  cancelingId.value = order.id;
  errorMessage.value = '';
  try {
    const updated = await cancelVoucherOrder(order.id);
    updateOrder(updated);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '取消订单失败';
  } finally {
    cancelingId.value = null;
  }
}

async function handlePay(order: VoucherOrder) {
  if (!canPay(order)) {
    return;
  }
  payingId.value = order.id;
  errorMessage.value = '';
  try {
    const updated = await payVoucherOrder(order.id);
    updateOrder(updated);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '支付失败';
  } finally {
    payingId.value = null;
  }
}

async function handleRefund(order: VoucherOrder) {
  if (!canRefund(order) || !window.confirm('确认退款这张券订单？')) {
    return;
  }
  refundingId.value = order.id;
  errorMessage.value = '';
  try {
    const updated = await refundVoucherOrder(order.id);
    updateOrder(updated);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '退款失败';
  } finally {
    refundingId.value = null;
  }
}

onMounted(loadOrders);

watch(
  () => route.query.highlight,
  () => {
    includeHighlightedOrder().catch((error) => {
      errorMessage.value = error instanceof Error ? error.message : '订单状态同步失败';
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
            <p>{{ order.voucherSubTitle || order.voucherRules || '系统正在同步订单信息' }}</p>
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
          <button v-if="canPay(order)" type="button" :disabled="payingId === order.id" @click="handlePay(order)">
            <Loader2 v-if="payingId === order.id" class="spin" :size="14" aria-hidden="true" />
            <CreditCard v-else :size="14" aria-hidden="true" />
            模拟支付
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
        {{ loadingMore ? '加载中' : hasMore ? '加载更多' : '没有更多订单' }}
      </button>
    </section>

    <div v-else class="empty-state orders-empty">
      暂时没有订单
    </div>
  </section>
</template>
