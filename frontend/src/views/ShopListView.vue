<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { AlertCircle, ArrowLeft, BadgeCheck, Loader2, MapPin, Phone, RefreshCw, Search, SlidersHorizontal, Star, Ticket } from '@lucide/vue';
import { fetchShopDiscovery, fetchShopTypes } from '../api/citycopilot';
import ConsumerLocationSelector from '../components/ConsumerLocationSelector.vue';
import { clearSelectedLocation, ensureDefaultCurrentLocation, locationState } from '../composables/location';
import { assetUrl, formatDistance, formatPrice, formatScore, shopCover } from '../utils/assets';
import { getErrorMessage } from '../utils/errors';
import { getPaginationLabel } from '../utils/labels';
import type { ShopDiscoveryItem, ShopType } from '../types';

const route = useRoute();
const router = useRouter();

const shopTypes = ref<ShopType[]>([]);
const shops = ref<ShopDiscoveryItem[]>([]);
const currentPage = ref(1);
const loadingTypes = ref(false);
const loading = ref(false);
const loadingMore = ref(false);
const hasMore = ref(true);
const errorMessage = ref('');
let requestId = 0;

const typeId = computed(() => {
  const value = Number(route.query.typeId);
  return Number.isFinite(value) && value > 0 ? value : undefined;
});
const sortedShopTypes = computed(() =>
  [...shopTypes.value].sort((left, right) => (left.sort ?? 999) - (right.sort ?? 999) || left.id - right.id),
);
const typeName = computed(() => {
  if (typeof route.query.typeName === 'string' && route.query.typeName.trim()) {
    return route.query.typeName.trim();
  }
  const selectedType = sortedShopTypes.value.find((type) => type.id === typeId.value);
  return selectedType?.name || '全部店铺';
});
const keyword = computed(() => (typeof route.query.name === 'string' ? route.query.name.trim() : ''));
const title = computed(() => keyword.value || typeName.value || '全部店铺');
const nearbyActive = computed(() => Boolean(locationState.point));
const subtitle = computed(() => {
  if (keyword.value) {
    return '搜索结果';
  }
  return nearbyActive.value ? '消费位置附近' : '分类店铺';
});
const hasShops = computed(() => shops.value.length > 0);
const orderableShopCount = computed(() => shops.value.filter((shop) => shop.orderable && shop.id).length);
const hasOnlyExternalShops = computed(() => nearbyActive.value && hasShops.value && orderableShopCount.value === 0);
const listMode = computed(() => {
  if (keyword.value) {
    return '关键词';
  }
  if (nearbyActive.value) {
    return '附近';
  }
  return typeId.value ? '分类' : '全城';
});
const emptyText = computed(() => {
  if (locationState.point) {
    return `${locationState.label || '当前消费位置'} 附近暂时没有匹配店铺`;
  }
  return '暂时没有匹配店铺，选择消费位置后可查看附近推荐';
});

async function queryShops(page: number) {
  return fetchShopDiscovery({
    typeId: typeId.value,
    keyword: keyword.value || undefined,
    current: page,
    point: locationState.point || undefined,
  });
}

async function loadShopTypes() {
  loadingTypes.value = true;
  try {
    shopTypes.value = await fetchShopTypes();
  } catch {
    shopTypes.value = [];
  } finally {
    loadingTypes.value = false;
  }
}

function isTypeActive(type: ShopType) {
  return typeId.value === type.id;
}

async function applyTypeFilter(type?: ShopType) {
  const query: Record<string, string> = {};
  if (type) {
    query.typeId = String(type.id);
    query.typeName = type.name;
  }
  Object.entries(route.query).forEach(([key, value]) => {
    if (key === 'typeId' || key === 'typeName') {
      return;
    }
    if (typeof value === 'string') {
      query[key] = value;
    }
  });
  await router.push({ name: 'shops', query });
}

function reviewText(shop: ShopDiscoveryItem) {
  if (typeof shop.comments === 'number' && shop.comments > 0) {
    return `${shop.comments} 条评价`;
  }
  return shop.source === 'amap' ? '暂无评价数' : '0 条评价';
}

function hasCover(shop: ShopDiscoveryItem) {
  return Boolean(shop.images?.split(',').find((image) => image.trim() && image.trim() !== '[]' && image.trim() !== '{}'));
}

function coverImage(shop: ShopDiscoveryItem) {
  return hasCover(shop) ? shopCover(shop) : '';
}

function categoryLabel(shop: ShopDiscoveryItem) {
  const value = shop.typeName || '';
  const parts = value.split(/[;；,，/]/).map((part) => part.trim()).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : '';
}

function coverLabel(shop: ShopDiscoveryItem) {
  return categoryLabel(shop) || shop.name.slice(0, 2);
}

function splitTags(value?: string) {
  return (value || '')
    .split(/[;,，、]/)
    .map((item) => item.trim())
    .filter((item) => Boolean(item) && item !== '[]' && item !== '{}');
}

function visibleTags(shop: ShopDiscoveryItem) {
  const tags = [
    categoryLabel(shop),
    shop.businessArea,
    ...splitTags(shop.tag).slice(0, 2),
    shop.orderable ? '可线上下单' : '外部地点 · 暂不支持下单',
    shop.sold ? `已售 ${shop.sold}` : '',
  ].filter(Boolean) as string[];
  return Array.from(new Set(tags)).slice(0, 5);
}

function showOrderableShops() {
  clearSelectedLocation();
}

function primaryTel(value?: string) {
  const values = splitTags(value);
  return values.length ? values[0] : value || '';
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
      errorMessage.value = getErrorMessage(error, '店铺加载失败');
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
      errorMessage.value = getErrorMessage(error, '加载更多失败');
    }
  } finally {
    loadingMore.value = false;
  }
}

onMounted(() => {
  void loadShopTypes();
  void loadFirstPage();
  void ensureDefaultCurrentLocation();
});

watch(
  () => route.fullPath,
  () => {
    loadFirstPage();
  },
);

watch(
  () => [locationState.point?.x, locationState.point?.y, locationState.updatedAt],
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
    <div class="list-signal-bar" aria-label="当前筛选信号">
      <span>
        <SlidersHorizontal :size="15" aria-hidden="true" />
        {{ listMode }}
      </span>
      <span>
        <BadgeCheck :size="15" aria-hidden="true" />
        评分 / 距离 / 券包优先
      </span>
      <span>
        <Ticket :size="15" aria-hidden="true" />
        可交易店铺进入详情完成下单
      </span>
    </div>
    <div class="shop-type-filter" aria-label="店铺类型筛选">
      <button type="button" :class="{ active: !typeId }" :aria-pressed="!typeId" @click="applyTypeFilter()">
        全部
      </button>
      <button
        v-for="type in sortedShopTypes"
        :key="type.id"
        type="button"
        :class="{ active: isTypeActive(type) }"
        :aria-pressed="isTypeActive(type)"
        @click="applyTypeFilter(type)"
      >
        <img :src="assetUrl(type.icon)" alt="" aria-hidden="true" />
        {{ type.name }}
      </button>
      <span v-if="loadingTypes" class="shop-type-loading">分类加载中</span>
    </div>
    <div class="list-hero-actions">
      <ConsumerLocationSelector />
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
    <section v-if="hasOnlyExternalShops" class="notice">
      <Ticket :size="18" aria-hidden="true" />
      当前地址附近只有外部地点，尚未入驻，不能进入详情或抢券。
      <button type="button" @click="showOrderableShops">
        查看可下单店铺
      </button>
    </section>

    <div v-if="loading" class="feed-skeleton shop-skeleton">
      <article v-for="item in 4" :key="item" class="skeleton-card" />
    </div>

    <div v-else-if="hasShops" class="shop-list">
      <component
        :is="shop.orderable && shop.id ? RouterLink : 'article'"
        v-for="shop in shops"
        :key="`${shop.source}-${shop.sourceId || shop.id || shop.name}`"
        class="shop-row"
        :class="{ 'shop-row-readonly': !shop.orderable || !shop.id }"
        :to="shop.orderable && shop.id ? { name: 'shop-detail', params: { id: shop.id } } : undefined"
        :title="shop.orderable && shop.id ? `查看 ${shop.name} 详情` : `${shop.name} 尚未入驻，暂不支持下单`"
      >
        <img v-if="coverImage(shop)" class="shop-row-cover" :src="coverImage(shop)" :alt="shop.name" />
        <div v-else class="shop-row-cover shop-row-cover-empty" aria-hidden="true">
          <span>{{ coverLabel(shop) }}</span>
        </div>
        <div class="shop-row-main">
          <div class="shop-row-head">
            <h2>{{ shop.name }}</h2>
            <span>{{ formatPrice(shop.avgPrice) }}</span>
          </div>
          <div class="shop-score">
            <Star :size="16" aria-hidden="true" />
            {{ formatScore(shop.score) }}
            <span>{{ reviewText(shop) }}</span>
          </div>
          <p class="shop-address">
            <MapPin :size="16" aria-hidden="true" />
            <span>{{ shop.area || '附近' }} · {{ shop.address || '地址待补充' }}</span>
          </p>
          <p v-if="primaryTel(shop.tel) || shop.openHours" class="shop-row-meta">
            <Phone v-if="primaryTel(shop.tel)" :size="15" aria-hidden="true" />
            <span v-if="primaryTel(shop.tel)">{{ primaryTel(shop.tel) }}</span>
            <span v-if="shop.openHours">{{ shop.openHours }}</span>
          </p>
          <div v-if="visibleTags(shop).length" class="shop-row-tags">
            <span v-for="tag in visibleTags(shop)" :key="tag">{{ tag }}</span>
          </div>
        </div>
        <span v-if="formatDistance(shop.distance)" class="shop-distance">{{ formatDistance(shop.distance) }}</span>
      </component>
    </div>

    <div v-else class="empty-state">
      <Search :size="18" aria-hidden="true" />
      {{ emptyText }}
    </div>

    <button class="load-more" type="button" :disabled="loading || loadingMore || !hasMore" @click="loadMore">
      <Loader2 v-if="loadingMore" class="spin" :size="17" aria-hidden="true" />
      {{ getPaginationLabel(loadingMore, hasMore) }}
    </button>
  </section>
</template>
