<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { AlertCircle, ArrowLeft, Loader2, MapPin, RefreshCw, Search, Star } from '@lucide/vue';
import { fetchNearbyShops, fetchShopsByName, fetchShopsByType } from '../api/hmdp';
import { clearCurrentLocation, locationState, requestCurrentLocation } from '../composables/location';
import { formatDistance, formatPrice, formatScore, shopCover } from '../utils/assets';
import type { Shop } from '../types';

const route = useRoute();

const shops = ref<Shop[]>([]);
const currentPage = ref(1);
const loading = ref(false);
const loadingMore = ref(false);
const hasMore = ref(true);
const errorMessage = ref('');
let requestId = 0;

const typeId = computed(() => {
  const value = Number(route.query.typeId);
  return Number.isFinite(value) && value > 0 ? value : undefined;
});
const typeName = computed(() => (typeof route.query.typeName === 'string' ? route.query.typeName : '全部店铺'));
const keyword = computed(() => (typeof route.query.name === 'string' ? route.query.name.trim() : ''));
const title = computed(() => keyword.value || typeName.value || '全部店铺');
const canUseNearby = computed(() => !keyword.value);
const nearbyActive = computed(() => Boolean(canUseNearby.value && locationState.point));
const subtitle = computed(() => (keyword.value ? '搜索结果' : nearbyActive.value ? '附近优先' : '分类店铺'));
const hasShops = computed(() => shops.value.length > 0);

async function queryShops(page: number) {
  const point = canUseNearby.value ? locationState.point || undefined : undefined;
  if (typeId.value) {
    return fetchShopsByType(typeId.value, page, point);
  }
  if (point) {
    return fetchNearbyShops(page, point);
  }
  return fetchShopsByName(keyword.value || undefined, page);
}

async function handleUseLocation() {
  const point = await requestCurrentLocation();
  if (!point) {
    errorMessage.value = locationState.errorMessage || '定位失败';
    return;
  }
  await loadFirstPage();
}

async function handleClearLocation() {
  clearCurrentLocation();
  await loadFirstPage();
}

async function loadFirstPage() {
  const currentRequest = ++requestId;
  loading.value = true;
  errorMessage.value = '';
  hasMore.value = true;
  try {
    const result = await queryShops(1);
    if (currentRequest !== requestId) {
      return;
    }
    shops.value = result;
    currentPage.value = 1;
    hasMore.value = result.length > 0;
  } catch (error) {
    if (currentRequest === requestId) {
      errorMessage.value = error instanceof Error ? error.message : '店铺加载失败';
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
  const currentRequest = ++requestId;
  loadingMore.value = true;
  errorMessage.value = '';
  try {
    const nextPage = currentPage.value + 1;
    const result = await queryShops(nextPage);
    if (currentRequest !== requestId) {
      return;
    }
    if (!result.length) {
      hasMore.value = false;
      return;
    }
    shops.value = shops.value.concat(result);
    currentPage.value = nextPage;
  } catch (error) {
    if (currentRequest === requestId) {
      errorMessage.value = error instanceof Error ? error.message : '加载更多失败';
    }
  } finally {
    loadingMore.value = false;
  }
}

onMounted(loadFirstPage);

watch(
  () => route.fullPath,
  () => {
    loadFirstPage();
  },
);
</script>

<template>
  <section class="list-hero">
    <RouterLink class="back-link" :to="{ name: 'home' }">
      <ArrowLeft :size="17" aria-hidden="true" />
      首页
    </RouterLink>
    <p class="eyebrow">{{ subtitle }}</p>
    <h1>{{ title }}</h1>
    <div class="list-hero-actions">
      <button v-if="canUseNearby" class="location-toggle" type="button" :disabled="locationState.status === 'loading'" @click="nearbyActive ? handleClearLocation() : handleUseLocation()">
        <MapPin :size="17" aria-hidden="true" />
        {{ locationState.status === 'loading' ? '定位中' : nearbyActive ? '关闭附近优先' : '附近优先' }}
      </button>
    </div>
  </section>

  <section v-if="errorMessage" class="notice notice-error">
    <AlertCircle :size="18" aria-hidden="true" />
    {{ errorMessage }}
    <button type="button" @click="loadFirstPage">
      <RefreshCw :size="16" aria-hidden="true" />
      重试
    </button>
  </section>

  <section class="shop-list-panel">
    <div v-if="loading" class="feed-skeleton shop-skeleton">
      <article v-for="item in 4" :key="item" class="skeleton-card" />
    </div>

    <div v-else-if="hasShops" class="shop-list">
      <RouterLink v-for="shop in shops" :key="shop.id" class="shop-row" :to="{ name: 'shop-detail', params: { id: shop.id } }">
        <img class="shop-row-cover" :src="shopCover(shop)" :alt="shop.name" />
        <div class="shop-row-main">
          <div class="shop-row-head">
            <h2>{{ shop.name }}</h2>
            <span>{{ formatPrice(shop.avgPrice) }}</span>
          </div>
          <div class="shop-score">
            <Star :size="16" aria-hidden="true" />
            {{ formatScore(shop.score) }}
            <span>{{ shop.comments || 0 }} 条评价</span>
          </div>
          <p class="shop-address">
            <MapPin :size="16" aria-hidden="true" />
            <span>{{ shop.area || '附近' }} · {{ shop.address || '地址待补充' }}</span>
          </p>
        </div>
        <span v-if="formatDistance(shop.distance)" class="shop-distance">{{ formatDistance(shop.distance) }}</span>
      </RouterLink>
    </div>

    <div v-else class="empty-state">
      <Search :size="18" aria-hidden="true" />
      暂时没有匹配店铺
    </div>

    <button class="load-more" type="button" :disabled="loading || loadingMore || !hasMore" @click="loadMore">
      <Loader2 v-if="loadingMore" class="spin" :size="17" aria-hidden="true" />
      {{ loadingMore ? '加载中' : hasMore ? '加载更多' : '没有更多了' }}
    </button>
  </section>
</template>
