<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { AlertCircle, CheckCircle2, Loader2, RefreshCw, Search, ShieldCheck, UserRound } from '@lucide/vue';
import { fetchAdminUsers, fetchPermissionMatrix, updateAdminUserRole } from '../api/hmdp';
import type { UserAdmin } from '../types';

const users = ref<UserAdmin[]>([]);
const roleMatrix = ref<Record<string, string[]>>({});
const keyword = ref('');
const loading = ref(false);
const savingId = ref<number | null>(null);
const errorMessage = ref('');
const successMessage = ref('');

const roleOptions = computed(() => Array.from(new Set(['user', ...Object.keys(roleMatrix.value)])));

function formatTime(value?: string) {
  if (!value) {
    return '未记录';
  }
  return value.replace('T', ' ').slice(0, 16);
}

function roleLabel(role?: string) {
  const labels: Record<string, string> = {
    user: '普通用户',
    admin: '管理员',
    geo_operator: '地理运营',
    auditor: '审计员',
    merchant: '商户运营',
  };
  return labels[role || ''] || role || '未设置';
}

async function loadUsers() {
  loading.value = true;
  errorMessage.value = '';
  try {
    users.value = await fetchAdminUsers(1, keyword.value.trim() || undefined);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '用户加载失败';
  } finally {
    loading.value = false;
  }
}

async function saveRole(user: UserAdmin, role: string) {
  savingId.value = user.id;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const updated = await updateAdminUserRole(user.id, role);
    users.value = users.value.map((item) => (item.id === updated.id ? updated : item));
    successMessage.value = '用户角色已更新，现有登录态已失效';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '角色更新失败';
  } finally {
    savingId.value = null;
  }
}

function handleRoleChange(user: UserAdmin, event: Event) {
  const target = event.target as HTMLSelectElement | null;
  if (target) {
    saveRole(user, target.value);
  }
}

onMounted(async () => {
  try {
    roleMatrix.value = await fetchPermissionMatrix();
  } catch {
    roleMatrix.value = {};
  }
  await loadUsers();
});
</script>

<template>
  <section class="admin-users-page">
    <header class="orders-header">
      <div>
        <p class="eyebrow">运营用户</p>
        <h1>角色管理</h1>
      </div>
      <button type="button" :disabled="loading" @click="loadUsers">
        <RefreshCw :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <form class="admin-user-search" @submit.prevent="loadUsers">
      <input v-model="keyword" type="search" placeholder="搜索手机号、昵称或角色" />
      <button type="submit" :disabled="loading">
        <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
        <Search v-else :size="17" aria-hidden="true" />
        查询
      </button>
    </form>

    <section v-if="successMessage" class="notice">
      <CheckCircle2 :size="18" aria-hidden="true" />
      {{ successMessage }}
    </section>
    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <div v-if="loading" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在加载用户
    </div>

    <section v-else-if="users.length" class="admin-user-list">
      <article v-for="user in users" :key="user.id" class="admin-user-row">
        <div class="admin-user-avatar">
          <img v-if="user.icon" :src="user.icon" :alt="user.nickName || user.phone || String(user.id)" />
          <UserRound v-else :size="20" aria-hidden="true" />
        </div>
        <div class="admin-user-main">
          <strong>{{ user.nickName || user.phone || `用户 ${user.id}` }}</strong>
          <span>{{ user.phone || '未绑定手机号' }} · 注册 {{ formatTime(user.createTime) }}</span>
        </div>
        <div class="admin-user-role">
          <ShieldCheck :size="16" aria-hidden="true" />
          <select :value="user.role || 'user'" :disabled="savingId === user.id" @change="handleRoleChange(user, $event)">
            <option v-for="role in roleOptions" :key="role" :value="role">{{ roleLabel(role) }}</option>
          </select>
          <Loader2 v-if="savingId === user.id" class="spin" :size="16" aria-hidden="true" />
        </div>
      </article>
    </section>

    <div v-else class="empty-state orders-empty">
      没有匹配用户
    </div>
  </section>
</template>
