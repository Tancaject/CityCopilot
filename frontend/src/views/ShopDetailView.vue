<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { AlertCircle, ArrowLeft, BadgeCheck, CheckCircle2, Clock, Loader2, MapPin, RefreshCw, Star, Ticket } from '@lucide/vue';
import { fetchShop, fetchVoucherOrder, fetchVouchers, seckillVoucher } from '../api/citycopilot';
import { authState } from '../composables/auth';
import ShopMapPanel from '../components/ShopMapPanel.vue';
import { firstImage, formatCents, formatPrice, formatScore } from '../utils/assets';
import type { Shop, Voucher } from '../types';

const props = defineProps<{
  id: number;
}>();

const route = useRoute();
const router = useRouter();

const shop = ref<Shop | null>(null);
const vouchers = ref<Voucher[]>([]);
const loading = ref(false);
const voucherLoading = ref(false);
const orderingId = ref<number | null>(null);
const errorMessage = ref('');
const voucherError = ref('');
const orderMessage = ref('');
const latestOrderId = ref<string | null>(null);
const orderPolling = ref(false);
const feedbackVoucherId = ref<number | null>(null);
const feedbackState = ref<'idle' | 'submitting' | 'processing' | 'success' | 'error'>('idle');
const claimedVoucherId = ref<number | null>(null);
let orderPollTimer: number | undefined;

const gallery = computed(() => {
  if (!shop.value?.images) {
    return [];
  }
  return shop.value.images.split(',').filter(Boolean).slice(0, 4).map(firstImage);
});
const decisionSignals = computed(() => {
  const current = shop.value;
  if (!current) {
    return [];
  }
  return [
    { label: '评分', value: formatScore(current.score), icon: Star },
    { label: '人均', value: formatPrice(current.avgPrice), icon: Ticket },
    { label: '区域', value: current.area || '附近', icon: MapPin },
  ];
});

async function loadShop() {
  loading.value = true;
  errorMessage.value = '';
  voucherError.value = '';
  orderMessage.value = '';
  feedbackVoucherId.value = null;
  feedbackState.value = 'idle';
  claimedVoucherId.value = null;
  try {
    shop.value = null;
    shop.value = await fetchShop(props.id);
    await loadVouchers();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '店铺详情加载失败';
  } finally {
    loading.value = false;
  }
}

function clearOrderPolling() {
  if (orderPollTimer !== undefined) {
    window.clearTimeout(orderPollTimer);
    orderPollTimer = undefined;
  }
  orderPolling.value = false;
}

async function loadVouchers() {
  voucherLoading.value = true;
  voucherError.value = '';
  try {
    vouchers.value = (await fetchVouchers(props.id)) || [];
  } catch (error) {
    vouchers.value = [];
    voucherError.value = error instanceof Error ? error.message : '优惠活动加载失败';
  } finally {
    voucherLoading.value = false;
  }
}

function parseTime(value?: string) {
  if (!value) {
    return null;
  }
  const timestamp = new Date(value.replace(' ', 'T')).getTime();
  return Number.isFinite(timestamp) ? timestamp : null;
}

function voucherState(voucher: Voucher) {
  if (!voucher.type) {
    return 'normal';
  }
  const now = Date.now();
  const begin = parseTime(voucher.beginTime);
  const end = parseTime(voucher.endTime);
  if (begin === null || end === null) {
    return 'unknown';
  }
  if (begin > now) {
    return 'pending';
  }
  if (end < now) {
    return 'ended';
  }
  if ((voucher.stock || 0) < 1) {
    return 'soldout';
  }
  return 'available';
}

function voucherButtonText(voucher: Voucher) {
  if (!voucher.type) {
    return '暂未开放';
  }
  if (claimedVoucherId.value === voucher.id) {
    return '已抢到';
  }
  if (
    feedbackVoucherId.value === voucher.id
    && (feedbackState.value === 'submitting' || feedbackState.value === 'processing')
  ) {
    return '处理中';
  }
  const state = voucherState(voucher);
  if (state === 'pending') {
    return '未开始';
  }
  if (state === 'ended') {
    return '已结束';
  }
  if (state === 'soldout') {
    return '已抢光';
  }
  if (state === 'unknown') {
    return '时间待确认';
  }
  return '限时抢购';
}

function isVoucherPending(voucher: Voucher) {
  return (
    feedbackVoucherId.value === voucher.id
    && (feedbackState.value === 'submitting' || feedbackState.value === 'processing')
  );
}

function isVoucherBusy(voucher: Voucher) {
  return (
    feedbackVoucherId.value === voucher.id
    && (
      feedbackState.value === 'submitting'
      || (feedbackState.value === 'processing' && orderPolling.value)
    )
  );
}

function isVoucherDisabled(voucher: Voucher) {
  if (!voucher.type) {
    return true;
  }
  return (
    voucherState(voucher) !== 'available'
    || orderingId.value === voucher.id
    || isVoucherPending(voucher)
    || claimedVoucherId.value === voucher.id
  );
}

async function handleVoucher(voucher: Voucher) {
  clearOrderPolling();
  orderMessage.value = '';
  latestOrderId.value = null;
  if (!authState.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } });
    return;
  }
  if (isVoucherDisabled(voucher)) {
    return;
  }
  feedbackVoucherId.value = voucher.id;
  feedbackState.value = 'submitting';
  orderMessage.value = '正在提交抢购请求，请稍候…';
  orderingId.value = voucher.id;
  try {
    const orderId = await seckillVoucher(voucher.id);
    latestOrderId.value = orderId;
    feedbackState.value = 'processing';
    orderMessage.value = `请求已提交，订单 ${orderId} 正在确认`;
    pollOrder(orderId, voucher.id);
  } catch (error) {
    feedbackState.value = 'error';
    orderMessage.value = error instanceof Error ? error.message : '抢购失败';
  } finally {
    orderingId.value = null;
  }
}

async function pollOrder(orderId: string, voucherId: number, attempt = 0) {
  orderPolling.value = true;
  try {
    const order = await fetchVoucherOrder(orderId);
    if (order.state === 'processing') {
      if (attempt >= 8) {
        orderMessage.value = `订单 ${orderId} 正在排队处理中，可稍后到订单中心查看`;
        clearOrderPolling();
        return;
      }
      orderPollTimer = window.setTimeout(() => pollOrder(orderId, voucherId, attempt + 1), 1000);
      return;
    }
    orderPollTimer = undefined;
    orderPolling.value = false;
    claimedVoucherId.value = voucherId;
    feedbackState.value = 'success';
    orderMessage.value = `领取成功，订单 ${order.id} 已生成（${order.statusText || '状态已确认'}）`;
    void loadVouchers();
  } catch (error) {
    orderPollTimer = undefined;
    orderPolling.value = false;
    feedbackState.value = 'error';
    orderMessage.value = error instanceof Error ? error.message : '订单状态确认失败';
  }
}

onMounted(loadShop);

onUnmounted(clearOrderPolling);

watch(
  () => props.id,
  () => {
    loadShop();
  },
);
</script>

<template>
  <section class="detail-shell">
    <RouterLink class="back-link" :to="{ name: 'shops' }">
      <ArrowLeft :size="17" aria-hidden="true" />
      店铺列表
    </RouterLink>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
      <button type="button" @click="loadShop">
        <RefreshCw :size="16" aria-hidden="true" />
        重试
      </button>
    </section>

    <div v-if="loading" class="loading-row detail-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在加载
    </div>

    <article v-else-if="shop" class="shop-detail">
      <img class="detail-cover" :src="firstImage(shop.images)" :alt="shop.name" />

      <div class="detail-body">
        <p class="eyebrow">{{ shop.area || 'Local shop' }}</p>
        <h1>{{ shop.name }}</h1>

        <div class="detail-score">
          <span><Star :size="17" aria-hidden="true" /> {{ formatScore(shop.score) }}</span>
          <span>{{ shop.comments || 0 }} 条评价</span>
          <span>{{ formatPrice(shop.avgPrice) }}</span>
        </div>

        <div class="detail-decision-grid" aria-label="到店决策信号">
          <article v-for="signal in decisionSignals" :key="signal.label">
            <component :is="signal.icon" :size="17" aria-hidden="true" />
            <small>{{ signal.label }}</small>
            <strong>{{ signal.value }}</strong>
          </article>
        </div>

        <div class="detail-action-row">
          <a href="#voucher-section">
            <Ticket :size="17" aria-hidden="true" />
            查看可用优惠
          </a>
          <span>
            <BadgeCheck :size="17" aria-hidden="true" />
            {{ shop.geoStatus === 'verified' ? '位置已确认' : '位置待确认' }}
          </span>
        </div>

        <p class="detail-line">
          <MapPin :size="18" aria-hidden="true" />
          {{ shop.address || '地址待补充' }}
        </p>

        <p class="detail-line">
          <Clock :size="18" aria-hidden="true" />
          {{ shop.openHours || '营业时间待补充' }}
        </p>

        <ShopMapPanel :x="shop.x" :y="shop.y" :name="shop.name" :address="shop.address" />

        <div v-if="gallery.length > 1" class="detail-gallery">
          <img v-for="image in gallery" :key="image" :src="image" :alt="shop.name" />
        </div>

        <section id="voucher-section" class="voucher-section">
          <div class="section-heading">
            <Ticket :size="19" aria-hidden="true" />
            <h2>优惠活动</h2>
          </div>

          <section v-if="voucherError" class="notice notice-error voucher-notice">
            <AlertCircle :size="18" aria-hidden="true" />
            {{ voucherError }}
            <button type="button" @click="loadVouchers">
              <RefreshCw :size="16" aria-hidden="true" />
              重试
            </button>
          </section>

          <div v-if="voucherLoading" class="loading-row voucher-empty">
            <Loader2 class="spin" :size="18" aria-hidden="true" />
            正在加载
          </div>

          <div v-else-if="vouchers.length" class="voucher-list">
            <article
              v-for="voucher in vouchers"
              :key="voucher.id"
              class="voucher-card"
              :class="{ 'voucher-card-success': claimedVoucherId === voucher.id }"
            >
              <div>
                <strong>{{ voucher.title }}</strong>
                <span>{{ voucher.subTitle || voucher.rules || '到店使用' }}</span>
                <small v-if="voucher.type">库存 {{ voucher.stock || 0 }}</small>
              </div>
              <div class="voucher-price">
                <b>{{ formatCents(voucher.payValue) }}</b>
                <small>{{ formatCents(voucher.actualValue) }}</small>
              </div>
              <button
                type="button"
                :class="{ 'voucher-claimed': claimedVoucherId === voucher.id }"
                :disabled="isVoucherDisabled(voucher)"
                @click="handleVoucher(voucher)"
              >
                <Loader2 v-if="isVoucherBusy(voucher)" class="spin" :size="16" aria-hidden="true" />
                <CheckCircle2 v-else-if="claimedVoucherId === voucher.id" :size="16" aria-hidden="true" />
                {{ voucherButtonText(voucher) }}
              </button>
              <div
                v-if="feedbackVoucherId === voucher.id && orderMessage"
                class="voucher-card-feedback"
                :class="`voucher-card-feedback-${feedbackState}`"
                :role="feedbackState === 'error' ? 'alert' : 'status'"
                aria-live="polite"
              >
                <Loader2 v-if="isVoucherBusy(voucher)" class="spin" :size="17" aria-hidden="true" />
                <CheckCircle2 v-else-if="feedbackState === 'success'" :size="17" aria-hidden="true" />
                <AlertCircle v-else-if="feedbackState === 'error'" :size="17" aria-hidden="true" />
                <Ticket v-else :size="17" aria-hidden="true" />
                <span>{{ orderMessage }}</span>
                <RouterLink
                  v-if="latestOrderId"
                  class="voucher-feedback-link"
                  :to="{ name: 'orders', query: { highlight: latestOrderId } }"
                >
                  查看订单
                </RouterLink>
              </div>
            </article>
          </div>

          <div v-else class="empty-state voucher-empty">暂无优惠活动</div>
        </section>
      </div>
    </article>

    <div v-else class="empty-state">没有找到这家店</div>
  </section>
</template>
