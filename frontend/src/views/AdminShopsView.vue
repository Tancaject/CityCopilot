<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { AlertCircle, CheckCircle2, Loader2, Plus, RefreshCw, Save, Search, Store } from '@lucide/vue';
import { createShop, fetchShop, fetchShopsByName, fetchShopTypes, updateShop } from '../api/citycopilot';
import { getErrorMessage } from '../utils/errors';
import type { Shop, ShopType } from '../types';

interface ShopForm {
  id?: number;
  name: string;
  typeId?: number;
  images: string;
  area: string;
  address: string;
  avgPrice?: number;
  openHours: string;
  x?: number;
  y?: number;
  geoStatus: string;
  geoSource: string;
}

const shops = ref<Shop[]>([]);
const shopTypes = ref<ShopType[]>([]);
const keyword = ref('');
const loading = ref(false);
const saving = ref(false);
const selectedId = ref<number | null>(null);
const errorMessage = ref('');
const successMessage = ref('');

const form = reactive<ShopForm>(emptyForm());

function emptyForm(): ShopForm {
  return {
    id: undefined,
    name: '',
    typeId: undefined,
    images: '',
    area: '',
    address: '',
    avgPrice: undefined,
    openHours: '',
    x: undefined,
    y: undefined,
    geoStatus: 'pending',
    geoSource: 'manual',
  };
}

function resetForm() {
  Object.assign(form, emptyForm());
  selectedId.value = null;
  successMessage.value = '';
}

function fillForm(shop: Shop) {
  selectedId.value = shop.id;
  Object.assign(form, {
    id: shop.id,
    name: shop.name || '',
    typeId: shop.typeId,
    images: shop.images || '',
    area: shop.area || '',
    address: shop.address || '',
    avgPrice: shop.avgPrice,
    openHours: shop.openHours || '',
    x: shop.x,
    y: shop.y,
    geoStatus: shop.geoStatus || 'pending',
    geoSource: shop.geoSource || 'manual',
  });
  successMessage.value = '';
}

function payload() {
  return {
    id: form.id,
    name: form.name.trim(),
    typeId: form.typeId,
    images: form.images.trim(),
    area: form.area.trim(),
    address: form.address.trim(),
    avgPrice: optionalNumber(form.avgPrice),
    openHours: form.openHours.trim(),
    x: optionalNumber(form.x),
    y: optionalNumber(form.y),
    geoStatus: form.geoStatus,
    geoSource: form.geoSource.trim() || 'manual',
  };
}

function optionalNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

async function loadShops() {
  loading.value = true;
  errorMessage.value = '';
  try {
    shops.value = await fetchShopsByName(keyword.value.trim() || undefined, 1, 20);
    if (!selectedId.value && shops.value.length) {
      fillForm(shops.value[0]);
    }
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '店铺加载失败');
  } finally {
    loading.value = false;
  }
}

async function saveShop() {
  if (!form.name.trim()) {
    errorMessage.value = '店铺名称不能为空';
    return;
  }
  saving.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    if (form.id) {
      await updateShop(payload() as Shop & { id: number });
      const updated = await fetchShop(form.id);
      shops.value = shops.value.map((shop) => (shop.id === updated.id ? updated : shop));
      fillForm(updated);
    } else {
      const id = await createShop(payload());
      const created = await fetchShop(id);
      shops.value = [created].concat(shops.value);
      fillForm(created);
    }
    successMessage.value = '店铺已保存';
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '店铺保存失败');
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  try {
    shopTypes.value = await fetchShopTypes();
    form.typeId = shopTypes.value[0]?.id;
  } finally {
    await loadShops();
  }
});
</script>

<template>
  <section class="admin-shops-page">
    <header class="orders-header">
      <div>
        <p class="eyebrow">运营店铺</p>
        <h1>店铺资料</h1>
      </div>
      <button type="button" @click="resetForm">
        <Plus :size="17" aria-hidden="true" />
        新建
      </button>
    </header>

    <section v-if="successMessage" class="notice">
      <CheckCircle2 :size="18" aria-hidden="true" />
      {{ successMessage }}
    </section>
    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <section class="admin-shops-layout">
      <aside class="admin-shop-list" aria-label="店铺列表">
        <form class="admin-shop-search" @submit.prevent="loadShops">
          <input v-model="keyword" type="search" placeholder="搜索店铺名称" />
          <button type="submit" :disabled="loading">
            <Loader2 v-if="loading" class="spin" :size="16" aria-hidden="true" />
            <Search v-else :size="16" aria-hidden="true" />
          </button>
        </form>
        <div v-if="loading" class="loading-row">
          <Loader2 class="spin" :size="18" aria-hidden="true" />
          正在加载
        </div>
        <template v-else>
          <button
            v-for="shop in shops"
            :key="shop.id"
            class="admin-shop-row"
            :class="{ active: selectedId === shop.id }"
            type="button"
            @click="fillForm(shop)"
          >
            <Store :size="17" aria-hidden="true" />
            <span>{{ shop.name }}</span>
            <small>{{ shop.area || '未标记区域' }}</small>
          </button>
        </template>
      </aside>

      <form class="admin-shop-form" @submit.prevent="saveShop">
        <div class="admin-shop-form-head">
          <div>
            <p class="eyebrow">{{ form.id ? `#${form.id}` : '新店铺' }}</p>
            <h2>{{ form.name || '填写店铺资料' }}</h2>
          </div>
          <button type="submit" :disabled="saving">
            <Loader2 v-if="saving" class="spin" :size="17" aria-hidden="true" />
            <Save v-else :size="17" aria-hidden="true" />
            保存
          </button>
        </div>

        <div class="admin-shop-grid">
          <label>
            <span>店铺名称</span>
            <input v-model="form.name" required />
          </label>
          <label>
            <span>分类</span>
            <select v-model.number="form.typeId">
              <option v-for="type in shopTypes" :key="type.id" :value="type.id">{{ type.name }}</option>
            </select>
          </label>
          <label>
            <span>区域</span>
            <input v-model="form.area" />
          </label>
          <label>
            <span>营业时间</span>
            <input v-model="form.openHours" placeholder="10:00-22:00" />
          </label>
          <label class="wide">
            <span>地址</span>
            <input v-model="form.address" />
          </label>
          <label class="wide">
            <span>图片路径</span>
            <input v-model="form.images" placeholder="/types/ms.png 或多图逗号分隔" />
          </label>
          <label>
            <span>人均价格</span>
            <input v-model.number="form.avgPrice" type="number" min="0" />
          </label>
          <label>
            <span>坐标状态</span>
            <select v-model="form.geoStatus">
              <option value="pending">待确认</option>
              <option value="verified">已确认</option>
              <option value="failed">需复核</option>
            </select>
          </label>
          <label>
            <span>经度（GCJ-02）</span>
            <input v-model.number="form.x" type="number" step="0.000001" />
          </label>
          <label>
            <span>纬度（GCJ-02）</span>
            <input v-model.number="form.y" type="number" step="0.000001" />
          </label>
          <label>
            <span>坐标来源</span>
            <input v-model="form.geoSource" />
          </label>
        </div>
      </form>
    </section>
  </section>
</template>
