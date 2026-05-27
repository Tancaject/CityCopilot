<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { AlertCircle, CheckCircle2, Loader2, Plus, Save, Search, Ticket, ToggleLeft, ToggleRight } from '@lucide/vue';
import { createAdminVoucher, fetchAdminVouchers, updateAdminVoucher, updateAdminVoucherStatus } from '../api/citycopilot';
import { formatCents } from '../utils/assets';
import type { Voucher } from '../types';

interface VoucherForm {
  id?: number;
  shopId?: number;
  title: string;
  subTitle: string;
  rules: string;
  payValue?: number;
  actualValue?: number;
  type: number;
  status: number;
  stock?: number;
  beginTime: string;
  endTime: string;
}

const vouchers = ref<Voucher[]>([]);
const keyword = ref('');
const filterShopId = ref<number | undefined>();
const filterStatus = ref<number | undefined>();
const selectedId = ref<number | null>(null);
const loading = ref(false);
const saving = ref(false);
const statusChangingId = ref<number | null>(null);
const errorMessage = ref('');
const successMessage = ref('');

const form = reactive<VoucherForm>(emptyForm());
const voucherStats = computed(() => {
  const total = vouchers.value.length;
  const active = vouchers.value.filter((voucher) => (voucher.status || 1) === 1).length;
  const seckill = vouchers.value.filter((voucher) => voucher.type === 1).length;
  const stock = vouchers.value.reduce((sum, voucher) => sum + (voucher.type === 1 ? voucher.stock || 0 : 0), 0);
  return [
    { label: '当前结果', value: total, detail: '张券' },
    { label: '上架中', value: active, detail: '可被用户看到' },
    { label: '秒杀券', value: seckill, detail: `库存 ${stock}` },
  ];
});
const statusFilters = [
  { label: '全部', value: undefined },
  { label: '上架', value: 1 },
  { label: '下架', value: 2 },
  { label: '过期', value: 3 },
];
const formPreview = computed(() => ({
  title: form.title || '未命名券包',
  price: formatCents(optionalNumber(form.payValue)),
  value: formatCents(optionalNumber(form.actualValue)),
  type: form.type === 1 ? '限时秒杀' : '普通券',
  status: statusText(form.status),
}));

function emptyForm(): VoucherForm {
  return {
    id: undefined,
    shopId: undefined,
    title: '',
    subTitle: '',
    rules: '',
    payValue: undefined,
    actualValue: undefined,
    type: 0,
    status: 1,
    stock: undefined,
    beginTime: '',
    endTime: '',
  };
}

function resetForm() {
  Object.assign(form, emptyForm());
  selectedId.value = null;
  successMessage.value = '';
}

function fillForm(voucher: Voucher) {
  selectedId.value = voucher.id;
  Object.assign(form, {
    id: voucher.id,
    shopId: voucher.shopId,
    title: voucher.title || '',
    subTitle: voucher.subTitle || '',
    rules: voucher.rules || '',
    payValue: voucher.payValue,
    actualValue: voucher.actualValue,
    type: voucher.type || 0,
    status: voucher.status || 1,
    stock: voucher.stock,
    beginTime: toInputDateTime(voucher.beginTime),
    endTime: toInputDateTime(voucher.endTime),
  });
  successMessage.value = '';
}

function statusText(status?: number) {
  if (status === 2) {
    return '下架';
  }
  if (status === 3) {
    return '过期';
  }
  return '上架';
}

function statusClass(status?: number) {
  return `voucher-status-${status || 1}`;
}

function toInputDateTime(value?: string) {
  return value ? value.replace(' ', 'T').slice(0, 16) : '';
}

function toApiDateTime(value: string) {
  if (!value) {
    return undefined;
  }
  const normalized = value.replace('T', ' ');
  return normalized.length === 16 ? `${normalized}:00` : normalized;
}

function optionalNumber(value: unknown) {
  if (value === '' || value === null || value === undefined) {
    return undefined;
  }
  const numberValue = Number(value);
  return Number.isFinite(numberValue) ? numberValue : undefined;
}

function payload() {
  const type = optionalNumber(form.type) || 0;
  const base: Partial<Voucher> = {
    shopId: optionalNumber(form.shopId),
    title: form.title.trim(),
    subTitle: form.subTitle.trim(),
    rules: form.rules.trim(),
    payValue: optionalNumber(form.payValue),
    actualValue: optionalNumber(form.actualValue),
    type,
    status: optionalNumber(form.status) || 1,
  };
  if (type === 1) {
    base.stock = optionalNumber(form.stock);
    base.beginTime = toApiDateTime(form.beginTime);
    base.endTime = toApiDateTime(form.endTime);
  }
  return base;
}

async function loadVouchers() {
  loading.value = true;
  errorMessage.value = '';
  try {
    vouchers.value = await fetchAdminVouchers({
      current: 1,
      shopId: optionalNumber(filterShopId.value),
      keyword: keyword.value.trim() || undefined,
      status: optionalNumber(filterStatus.value),
    });
    if (!selectedId.value && vouchers.value.length) {
      fillForm(vouchers.value[0]);
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '优惠券加载失败';
  } finally {
    loading.value = false;
  }
}

async function saveVoucher() {
  if (!form.title.trim()) {
    errorMessage.value = '优惠券标题不能为空';
    return;
  }
  saving.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    if (form.id) {
      const updated = await updateAdminVoucher(form.id, payload());
      vouchers.value = vouchers.value.map((voucher) => (voucher.id === updated.id ? updated : voucher));
      fillForm(updated);
    } else {
      const id = await createAdminVoucher(payload());
      await loadVouchers();
      const created = vouchers.value.find((voucher) => voucher.id === id);
      if (created) {
        fillForm(created);
      }
    }
    successMessage.value = '优惠券已保存';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '优惠券保存失败';
  } finally {
    saving.value = false;
  }
}

async function toggleVoucherStatus(voucher: Voucher) {
  const nextStatus = voucher.status === 1 ? 2 : 1;
  statusChangingId.value = voucher.id;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const updated = await updateAdminVoucherStatus(voucher.id, nextStatus);
    vouchers.value = vouchers.value.map((item) => (item.id === updated.id ? updated : item));
    if (selectedId.value === updated.id) {
      fillForm(updated);
    }
    successMessage.value = nextStatus === 1 ? '优惠券已上架' : '优惠券已下架';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '状态更新失败';
  } finally {
    statusChangingId.value = null;
  }
}

async function toggleCurrentVoucherStatus() {
  if (!form.id) {
    return;
  }
  await toggleVoucherStatus({
    id: form.id,
    shopId: optionalNumber(form.shopId) || 0,
    title: form.title,
    status: optionalNumber(form.status) || 1,
  });
}

onMounted(loadVouchers);
</script>

<template>
  <section class="admin-shops-page">
    <header class="orders-header">
      <div>
        <p class="eyebrow">运营券包</p>
        <h1>优惠券配置</h1>
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

    <section class="admin-voucher-command" aria-label="优惠券活动摘要">
      <article v-for="stat in voucherStats" :key="stat.label">
        <span>{{ stat.label }}</span>
        <strong>{{ stat.value }}</strong>
        <small>{{ stat.detail }}</small>
      </article>
      <div class="admin-voucher-filter" aria-label="状态筛选">
        <button
          v-for="filter in statusFilters"
          :key="filter.label"
          type="button"
          :class="{ active: filterStatus === filter.value }"
          @click="filterStatus = filter.value; loadVouchers()"
        >
          {{ filter.label }}
        </button>
      </div>
    </section>

    <section class="admin-shops-layout">
      <aside class="admin-shop-list" aria-label="优惠券列表">
        <form class="admin-voucher-search" @submit.prevent="loadVouchers">
          <input v-model="keyword" type="search" placeholder="搜索券标题" />
          <input v-model.number="filterShopId" type="number" min="1" placeholder="店铺ID" />
          <select v-model.number="filterStatus">
            <option :value="undefined">全部状态</option>
            <option :value="1">上架</option>
            <option :value="2">下架</option>
            <option :value="3">过期</option>
          </select>
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
            v-for="voucher in vouchers"
            :key="voucher.id"
            class="admin-shop-row admin-voucher-row"
            :class="{ active: selectedId === voucher.id }"
            type="button"
            @click="fillForm(voucher)"
          >
            <Ticket :size="17" aria-hidden="true" />
            <span>{{ voucher.title }}</span>
            <small>
              店铺 {{ voucher.shopId }} · {{ voucher.type === 1 ? `秒杀库存 ${voucher.stock ?? 0}` : formatCents(voucher.payValue) }}
            </small>
            <em :class="statusClass(voucher.status)">{{ statusText(voucher.status) }}</em>
          </button>
        </template>
      </aside>

      <form class="admin-shop-form" @submit.prevent="saveVoucher">
        <div class="admin-shop-form-head">
          <div>
            <p class="eyebrow">{{ form.id ? `#${form.id}` : '新优惠券' }}</p>
            <h2>{{ form.title || '填写优惠券资料' }}</h2>
          </div>
          <div class="admin-voucher-actions">
            <button v-if="form.id" type="button" :disabled="statusChangingId === form.id" @click="toggleCurrentVoucherStatus">
              <Loader2 v-if="statusChangingId === form.id" class="spin" :size="17" aria-hidden="true" />
              <ToggleRight v-else-if="form.status === 1" :size="17" aria-hidden="true" />
              <ToggleLeft v-else :size="17" aria-hidden="true" />
              {{ form.status === 1 ? '下架' : '上架' }}
            </button>
            <button type="submit" :disabled="saving">
              <Loader2 v-if="saving" class="spin" :size="17" aria-hidden="true" />
              <Save v-else :size="17" aria-hidden="true" />
              保存
            </button>
          </div>
        </div>

        <section class="admin-voucher-preview" aria-label="当前券预览">
          <span>{{ formPreview.type }}</span>
          <strong>{{ formPreview.title }}</strong>
          <div>
            <b>{{ formPreview.price }}</b>
            <small>抵扣 {{ formPreview.value }} · {{ formPreview.status }}</small>
          </div>
        </section>

        <div class="admin-shop-grid">
          <label>
            <span>店铺ID</span>
            <input v-model.number="form.shopId" type="number" min="1" required />
          </label>
          <label>
            <span>类型</span>
            <select v-model.number="form.type" :disabled="Boolean(form.id)">
              <option :value="0">普通券</option>
              <option :value="1">秒杀券</option>
            </select>
          </label>
          <label>
            <span>标题</span>
            <input v-model="form.title" required />
          </label>
          <label>
            <span>状态</span>
            <select v-model.number="form.status">
              <option :value="1">上架</option>
              <option :value="2">下架</option>
              <option :value="3">过期</option>
            </select>
          </label>
          <label>
            <span>支付金额（分）</span>
            <input v-model.number="form.payValue" type="number" min="0" required />
          </label>
          <label>
            <span>抵扣金额（分）</span>
            <input v-model.number="form.actualValue" type="number" min="1" required />
          </label>
          <label class="wide">
            <span>副标题</span>
            <input v-model="form.subTitle" />
          </label>
          <label class="wide">
            <span>使用规则</span>
            <textarea v-model="form.rules" rows="4" />
          </label>
          <template v-if="form.type === 1">
            <label>
              <span>秒杀库存</span>
              <input v-model.number="form.stock" type="number" min="0" required />
            </label>
            <label>
              <span>开始时间</span>
              <input v-model="form.beginTime" type="datetime-local" required />
            </label>
            <label>
              <span>结束时间</span>
              <input v-model="form.endTime" type="datetime-local" required />
            </label>
          </template>
        </div>
      </form>
    </section>
  </section>
</template>
