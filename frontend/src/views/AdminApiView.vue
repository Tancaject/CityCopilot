<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { AlertCircle, Braces, KeyRound, Loader2, RefreshCw, Search } from '@lucide/vue';
import { fetchApiEndpoints, fetchApiErrors, fetchPermissionMatrix } from '../api/citycopilot';
import type { ApiEndpoint, ApiErrorDefinition } from '../types';

type ApiTab = 'endpoints' | 'errors' | 'permissions';

const activeTab = ref<ApiTab>('endpoints');
const endpoints = ref<ApiEndpoint[]>([]);
const errors = ref<ApiErrorDefinition[]>([]);
const permissions = ref<Record<string, string[]>>({});
const keyword = ref('');
const loading = ref(false);
const errorMessage = ref('');

const filteredEndpoints = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  if (!term) {
    return endpoints.value;
  }
  return endpoints.value.filter((endpoint) =>
    [
      endpoint.controller,
      endpoint.handler,
      endpoint.paths.join(' '),
      endpoint.methods.join(' '),
      endpoint.permission?.permissions?.join(' ') || '',
    ]
      .join(' ')
      .toLowerCase()
      .includes(term),
  );
});

const filteredErrors = computed(() => {
  const term = keyword.value.trim().toLowerCase();
  if (!term) {
    return errors.value;
  }
  return errors.value.filter((item) => `${item.code} ${item.message}`.toLowerCase().includes(term));
});

const permissionRows = computed(() =>
  Object.entries(permissions.value).map(([role, values]) => ({
    role,
    values,
  })),
);

function permissionLabel(endpoint: ApiEndpoint) {
  const values = endpoint.permission?.permissions || [];
  if (!values.length) {
    return '公开或登录态';
  }
  return values.join(endpoint.permission?.anyOf ? ' / ' : ' + ');
}

async function loadContracts() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const [endpointResult, errorResult, permissionResult] = await Promise.all([
      fetchApiEndpoints(),
      fetchApiErrors(),
      fetchPermissionMatrix(),
    ]);
    endpoints.value = endpointResult || [];
    errors.value = errorResult || [];
    permissions.value = permissionResult || {};
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'API 契约加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(loadContracts);
</script>

<template>
  <section class="admin-api-page">
    <header class="orders-header">
      <div>
        <p class="eyebrow">后台自检</p>
        <h1>API 契约</h1>
      </div>
      <button type="button" :disabled="loading" @click="loadContracts">
        <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
        <RefreshCw v-else :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <section class="admin-api-toolbar">
      <div class="admin-api-tabs" role="tablist" aria-label="API 契约视图">
        <button type="button" :class="{ active: activeTab === 'endpoints' }" @click="activeTab = 'endpoints'">
          <Braces :size="16" aria-hidden="true" />
          路由
        </button>
        <button type="button" :class="{ active: activeTab === 'errors' }" @click="activeTab = 'errors'">
          <AlertCircle :size="16" aria-hidden="true" />
          错误码
        </button>
        <button type="button" :class="{ active: activeTab === 'permissions' }" @click="activeTab = 'permissions'">
          <KeyRound :size="16" aria-hidden="true" />
          权限
        </button>
      </div>
      <label class="admin-api-search">
        <Search :size="16" aria-hidden="true" />
        <input v-model="keyword" type="search" placeholder="搜索路径、控制器、错误码" />
      </label>
    </section>

    <div v-if="loading" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在读取契约
    </div>

    <section v-else-if="activeTab === 'endpoints'" class="admin-api-table">
      <article v-for="endpoint in filteredEndpoints" :key="`${endpoint.controller}.${endpoint.handler}.${endpoint.paths.join(',')}`">
        <div>
          <strong>{{ endpoint.paths.join(', ') }}</strong>
          <span>{{ endpoint.controller }}.{{ endpoint.handler }}</span>
        </div>
        <code>{{ endpoint.methods.join(', ') }}</code>
        <em>{{ permissionLabel(endpoint) }}</em>
      </article>
    </section>

    <section v-else-if="activeTab === 'errors'" class="admin-api-table admin-api-errors">
      <article v-for="item in filteredErrors" :key="item.code">
        <div>
          <strong>{{ item.code }}</strong>
          <span>{{ item.message }}</span>
        </div>
      </article>
    </section>

    <section v-else class="admin-api-permissions">
      <article v-for="row in permissionRows" :key="row.role">
        <strong>{{ row.role }}</strong>
        <div>
          <span v-for="permission in row.values" :key="permission">{{ permission }}</span>
        </div>
      </article>
    </section>
  </section>
</template>
