<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { AlertCircle, ArrowLeft, Clock, Loader2, MapPin, RefreshCw, Star, Ticket } from '@lucide/vue';
import { fetchShop, fetchVoucherOrder, fetchVouchers, seckillVoucher } from '../api/hmdp';
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
const latestOrderId = ref<number | null>(null);
const orderPolling = ref(false);
let orderPollTimer: number | undefined;

const gallery = computed(() => {
  if (!shop.value?.images) {
    return [];
  }
  return shop.value.images.split(',').filter(Boolean).slice(0, 4).map(firstImage);
});

async function loadShop() {
  loading.value = true;
  errorMessage.value = '';
  voucherError.value = '';
  orderMessage.value = '';
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
  const state = voucherState(voucher);
  if (state === 'normal') {
    return '领取';
  }
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

function isVoucherDisabled(voucher: Voucher) {
  return voucher.type ? voucherState(voucher) !== 'available' || orderingId.value === voucher.id : false;
}

async function handleVoucher(voucher: Voucher) {
  clearOrderPolling();
  orderMessage.value = '';
  latestOrderId.value = null;
  if (!voucher.type) {
    orderMessage.value = '普通券领取暂未开放';
    return;
  }
  if (!authState.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } });
    return;
  }
  if (isVoucherDisabled(voucher)) {
    return;
  }
  orderingId.value = voucher.id;
  try {
    const orderId = await seckillVoucher(voucher.id);
    latestOrderId.value = orderId;
    orderMessage.value = `抢购已提交，订单号 ${orderId}，正在确认状态`;
    pollOrder(orderId);
  } catch (error) {
    orderMessage.value = error instanceof Error ? error.message : '抢购失败';
  } finally {
    orderingId.value = null;
  }
}

async function pollOrder(orderId: number, attempt = 0) {
  orderPolling.value = true;
  try {
    const order = await fetchVoucherOrder(orderId);
    if (order.state === 'processing') {
      if (attempt >= 8) {
        orderMessage.value = `订单 ${orderId} 正在排队处理中，可稍后到订单中心查看`;
        clearOrderPolling();
        return;
      }
      orderPollTimer = window.setTimeout(() => pollOrder(orderId, attempt + 1), 1000);
      return;
    }
    orderPollTimer = undefined;
    orderPolling.value = false;
    orderMessage.value = `抢购成功，订单号 ${order.id}，${order.statusText || '状态已确认'}`;
  } catch (error) {
    orderPollTimer = undefined;
    orderPolling.value = false;
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

    <section v-if="orderMessage" class="notice">
      <Loader2 v-if="orderPolling" class="spin" :size="18" aria-hidden="true" />
      <Ticket v-else :size="18" aria-hidden="true" />
      {{ orderMessage }}
      <RouterLink v-if="latestOrderId" class="notice-link" :to="{ name: 'orders', query: { highlight: latestOrderId } }">
        查看订单
      </RouterLink>
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

        <section class="voucher-section">
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
            <article v-for="voucher in vouchers" :key="voucher.id" class="voucher-card">
              <div>
                <strong>{{ voucher.title }}</strong>
                <span>{{ voucher.subTitle || voucher.rules || '到店使用' }}</span>
                <small v-if="voucher.type">库存 {{ voucher.stock || 0 }}</small>
              </div>
              <div class="voucher-price">
                <b>{{ formatCents(voucher.payValue) }}</b>
                <small>{{ formatCents(voucher.actualValue) }}</small>
              </div>
              <button type="button" :disabled="isVoucherDisabled(voucher)" @click="handleVoucher(voucher)">
                <Loader2 v-if="orderingId === voucher.id" class="spin" :size="16" aria-hidden="true" />
                {{ voucherButtonText(voucher) }}
              </button>
            </article>
          </div>

          <div v-else class="empty-state voucher-empty">暂无优惠活动</div>
        </section>
      </div>
    </article>

    <div v-else class="empty-state">没有找到这家店</div>
  </section>
</template>
