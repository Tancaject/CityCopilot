<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watchEffect } from 'vue';
import { Activity, Braces, ClipboardCheck, Database, FileText, LayoutDashboard, LogOut, MapPin, ReceiptText, Rss, Search, ShieldCheck, SquarePen, Store, Ticket, UserRound } from '@lucide/vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import AiAssistantPanel from './components/AiAssistantPanel.vue';
import { authState, clearAuth, initAuth, logout } from './composables/auth';
import { hasPermission } from './utils/permissions';

const route = useRoute();
const router = useRouter();
const searchTerm = ref('');

const isLoginRoute = computed(() => route.name === 'login');
const isOpsRoute = computed(() => typeof route.path === 'string' && route.path.startsWith('/admin'));
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
const visibleAdminLinks = computed(() =>
  [
    { enabled: canAccessAdmin.value, name: 'admin-dashboard', label: '概览', detail: '实时指标', icon: LayoutDashboard },
    { enabled: canAccessShopAdmin.value, name: 'admin-shops', label: '店铺', detail: '资料与坐标', icon: Store },
    { enabled: canAccessGeoAdmin.value, name: 'geo-shops-admin', label: '校准', detail: 'GEO 队列', icon: MapPin },
    { enabled: canAccessVoucherAdmin.value, name: 'admin-vouchers', label: '券包', detail: '活动配置', icon: Ticket },
    { enabled: canAccessOrderAdmin.value, name: 'admin-orders', label: '核销', detail: '订单动作', icon: ClipboardCheck },
    { enabled: canAccessUserAdmin.value, name: 'admin-users', label: '用户', detail: '角色权限', icon: ShieldCheck },
    { enabled: canAccessContentAdmin.value, name: 'admin-blogs', label: '内容', detail: '笔记治理', icon: FileText },
    { enabled: canAccessAudit.value, name: 'admin-audit', label: '审计', detail: '操作日志', icon: Database },
    { enabled: canAccessAudit.value, name: 'admin-api', label: '契约', detail: 'API 自检', icon: Braces },
    { enabled: canAccessAudit.value, name: 'admin-agent-traces', label: 'Traces', detail: 'Agent 轨迹', icon: Activity },
    { enabled: canAccessAudit.value, name: 'admin-ops-cases', label: 'Agent', detail: '诊断与审批', icon: ShieldCheck },
  ].filter((link) => link.enabled),
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

watchEffect(() => {
  if (typeof document === 'undefined') return;
  document.body.dataset.scene = isOpsRoute.value ? 'admin' : 'user';
});

onMounted(() => {
  window.addEventListener('citycopilot:auth-expired', handleAuthExpired);
  initAuth();
});

onUnmounted(() => {
  window.removeEventListener('citycopilot:auth-expired', handleAuthExpired);
});
</script>

<template>
  <main class="app-shell" :class="{ 'app-shell-ops': isOpsRoute, 'app-shell-auth': isLoginRoute }">
    <template v-if="isOpsRoute">
      <aside class="ops-rail" aria-label="运营控制台导航">
        <RouterLink class="brand-lockup ops-brand" :to="{ name: 'admin-dashboard' }" aria-label="CityCopilot 运营后台">
          <span class="brand-mark">C</span>
          <div>
            <strong>CityCopilot</strong>
            <span>Ops Command</span>
          </div>
        </RouterLink>

        <nav class="ops-nav">
          <RouterLink v-for="link in visibleAdminLinks" :key="link.name" :to="{ name: link.name }">
            <component :is="link.icon" :size="18" aria-hidden="true" />
            <span>
              <strong>{{ link.label }}</strong>
              <small>{{ link.detail }}</small>
            </span>
          </RouterLink>
        </nav>

        <div class="ops-rail-footer">
          <RouterLink :to="{ name: 'home' }">回到城市端</RouterLink>
          <button type="button" @click="handleLogout">
            <LogOut :size="17" aria-hidden="true" />
            {{ displayName }}
          </button>
        </div>
      </aside>

      <section class="ops-main">
        <header class="ops-topbar">
          <div>
            <span>Operations cockpit</span>
            <strong>本地生活交易与履约控制台</strong>
          </div>
          <div v-if="canAccessAudit" class="ops-topbar-actions">
            <RouterLink :to="{ name: 'admin-api' }">
              <Braces :size="17" aria-hidden="true" />
              API 自检
            </RouterLink>
            <RouterLink :to="{ name: 'admin-agent-traces' }">
              <Activity :size="17" aria-hidden="true" />
              Agent Trace
            </RouterLink>
          </div>
        </header>

        <RouterView />
      </section>
    </template>

    <template v-else>
      <header v-if="!isLoginRoute" class="city-topbar">
        <RouterLink class="brand-lockup" :to="{ name: 'home' }" aria-label="CityCopilot 首页">
          <span class="brand-mark">C</span>
          <div>
            <strong>CityCopilot</strong>
            <span>Urban decision layer</span>
          </div>
        </RouterLink>

        <form class="search-box" role="search" @submit.prevent="handleSearch">
          <input v-model="searchTerm" type="search" placeholder="找店、找券、找一条今晚能执行的路线" />
          <button class="search-submit" type="submit" aria-label="搜索">
            <Search :size="18" aria-hidden="true" />
          </button>
        </form>

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
          <RouterLink v-if="hasAdminLinks" :to="{ name: 'admin-dashboard' }">
            <LayoutDashboard :size="17" aria-hidden="true" />
            运营
          </RouterLink>
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
    </template>
  </main>
</template>
