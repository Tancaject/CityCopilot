<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { Braces, ClipboardCheck, Database, FileText, LayoutDashboard, LogOut, MapPin, ReceiptText, Rss, Search, ShieldCheck, SlidersHorizontal, SquarePen, Store, Ticket, UserRound } from '@lucide/vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import AiAssistantPanel from './components/AiAssistantPanel.vue';
import { authState, clearAuth, initAuth, logout } from './composables/auth';
import { hasPermission } from './utils/permissions';

const route = useRoute();
const router = useRouter();
const searchTerm = ref('');
const cityName = import.meta.env.VITE_DEFAULT_CITY || '杭州';

const isLoginRoute = computed(() => route.name === 'login');
const displayName = computed(() => authState.user?.nickName || authState.user?.name || '我的');
const canAccessAdmin = computed(() => hasPermission(authState.user, 'admin:access'));
const canAccessAudit = computed(() => hasPermission(authState.user, 'audit:read'));
const canAccessGeoAdmin = computed(() => hasPermission(authState.user, 'geo:read'));
const canAccessOrderAdmin = computed(() => hasPermission(authState.user, 'order:write'));
const canAccessShopAdmin = computed(() => hasPermission(authState.user, 'shop:write'));
const canAccessVoucherAdmin = computed(() => hasPermission(authState.user, 'voucher:write'));
const canAccessUserAdmin = computed(() => hasPermission(authState.user, 'user:write'));
const canAccessContentAdmin = computed(() => hasPermission(authState.user, 'content:write'));
const hasAdminLinks = computed(
  () =>
    canAccessAdmin.value ||
    canAccessAudit.value ||
    canAccessGeoAdmin.value ||
    canAccessShopAdmin.value ||
    canAccessVoucherAdmin.value ||
    canAccessOrderAdmin.value ||
    canAccessUserAdmin.value ||
    canAccessContentAdmin.value,
);

async function handleLogout() {
  await logout();
  if (route.meta.requiresAuth) {
    await router.push({ name: 'home' });
  }
}

function handleAuthExpired() {
  clearAuth();
  if (!isLoginRoute.value) {
    router.push({
      name: 'login',
      query: { redirect: route.fullPath },
    });
  }
}

async function handleSearch() {
  const name = searchTerm.value.trim();
  await router.push({
    name: 'shops',
    query: name ? { name } : {},
  });
}

onMounted(() => {
  window.addEventListener('hmdp:auth-expired', handleAuthExpired);
  initAuth();
});

onUnmounted(() => {
  window.removeEventListener('hmdp:auth-expired', handleAuthExpired);
});
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <RouterLink class="brand-lockup" :to="{ name: 'home' }" aria-label="黑马点评首页">
        <span class="brand-mark">H</span>
        <div>
          <strong>黑马点评</strong>
          <span>城市生活精选</span>
        </div>
      </RouterLink>

      <form class="search-box" role="search" @submit.prevent="handleSearch">
        <input v-model="searchTerm" type="search" placeholder="搜索店铺、分类或探店笔记" />
        <button class="search-submit" type="submit" aria-label="搜索">
          <Search :size="18" aria-hidden="true" />
        </button>
      </form>

      <button class="city-button" type="button">
        <MapPin :size="17" aria-hidden="true" />
        {{ cityName }}
      </button>

      <nav class="top-actions" aria-label="内容入口">
        <RouterLink :to="{ name: 'feed' }">
          <Rss :size="17" aria-hidden="true" />
          关注
        </RouterLink>
        <RouterLink :to="{ name: 'blog-create' }">
          <SquarePen :size="17" aria-hidden="true" />
          发布
        </RouterLink>
        <RouterLink v-if="authState.isLoggedIn" :to="{ name: 'orders' }">
          <ReceiptText :size="17" aria-hidden="true" />
          订单
        </RouterLink>
        <details v-if="hasAdminLinks" class="admin-menu">
          <summary>
            <SlidersHorizontal :size="17" aria-hidden="true" />
            后台
          </summary>
          <div class="admin-menu-popover">
            <RouterLink v-if="canAccessAdmin" :to="{ name: 'admin-dashboard' }">
              <LayoutDashboard :size="17" aria-hidden="true" />
              概览
            </RouterLink>
            <RouterLink v-if="canAccessShopAdmin" :to="{ name: 'admin-shops' }">
              <Store :size="17" aria-hidden="true" />
              店铺
            </RouterLink>
            <RouterLink v-if="canAccessGeoAdmin" :to="{ name: 'geo-shops-admin' }">
              <MapPin :size="17" aria-hidden="true" />
              校准
            </RouterLink>
            <RouterLink v-if="canAccessVoucherAdmin" :to="{ name: 'admin-vouchers' }">
              <Ticket :size="17" aria-hidden="true" />
              券包
            </RouterLink>
            <RouterLink v-if="canAccessOrderAdmin" :to="{ name: 'admin-orders' }">
              <ClipboardCheck :size="17" aria-hidden="true" />
              核销
            </RouterLink>
            <RouterLink v-if="canAccessUserAdmin" :to="{ name: 'admin-users' }">
              <ShieldCheck :size="17" aria-hidden="true" />
              用户
            </RouterLink>
            <RouterLink v-if="canAccessContentAdmin" :to="{ name: 'admin-blogs' }">
              <FileText :size="17" aria-hidden="true" />
              内容
            </RouterLink>
            <RouterLink v-if="canAccessAudit" :to="{ name: 'admin-audit' }">
              <Database :size="17" aria-hidden="true" />
              审计
            </RouterLink>
            <RouterLink v-if="canAccessAudit" :to="{ name: 'admin-api' }">
              <Braces :size="17" aria-hidden="true" />
              契约
            </RouterLink>
          </div>
        </details>
      </nav>

      <RouterLink v-if="!authState.isLoggedIn" class="auth-button" :to="{ name: 'login', query: { redirect: route.fullPath } }">
        <UserRound :size="18" aria-hidden="true" />
        登录
      </RouterLink>

      <button v-else class="auth-button" type="button" @click="handleLogout">
        <LogOut :size="18" aria-hidden="true" />
        {{ displayName }}
      </button>
    </header>

    <RouterView />
    <AiAssistantPanel v-if="!isLoginRoute" />
  </main>
</template>
