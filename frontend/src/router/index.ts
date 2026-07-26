import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { authState, initAuth } from '../composables/auth';
import { hasAllPermissions } from '../utils/permissions';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/HomeView.vue'),
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
  },
  {
    path: '/shops',
    name: 'shops',
    component: () => import('../views/ShopListView.vue'),
  },
  {
    path: '/shops/:id(\\d+)',
    name: 'shop-detail',
    component: () => import('../views/ShopDetailView.vue'),
    props: (route) => ({ id: Number(route.params.id) }),
  },
  {
    path: '/blogs/new',
    name: 'blog-create',
    component: () => import('../views/BlogCreateView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/blogs/:id(\\d+)',
    name: 'blog-detail',
    component: () => import('../views/BlogDetailView.vue'),
    props: (route) => ({ id: Number(route.params.id) }),
  },
  {
    path: '/feed',
    name: 'feed',
    component: () => import('../views/FeedView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/OrdersView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/admin',
    name: 'admin-dashboard',
    component: () => import('../views/AdminDashboardView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['admin:access'] },
  },
  {
    path: '/admin/geo-shops',
    name: 'geo-shops-admin',
    component: () => import('../views/GeoShopAdminView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['geo:read'] },
  },
  {
    path: '/admin/orders',
    name: 'admin-orders',
    component: () => import('../views/AdminOrdersView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['order:write'] },
  },
  {
    path: '/admin/vouchers',
    name: 'admin-vouchers',
    component: () => import('../views/AdminVouchersView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['voucher:write'] },
  },
  {
    path: '/admin/shops',
    name: 'admin-shops',
    component: () => import('../views/AdminShopsView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['shop:write'] },
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: () => import('../views/AdminUsersView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['user:write'] },
  },
  {
    path: '/admin/blogs',
    name: 'admin-blogs',
    component: () => import('../views/AdminBlogsView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['content:write'] },
  },
  {
    path: '/admin/audit',
    name: 'admin-audit',
    component: () => import('../views/AdminAuditView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['audit:read'] },
  },
  {
    path: '/admin/api',
    name: 'admin-api',
    component: () => import('../views/AdminApiView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['audit:read'] },
  },
  {
    path: '/admin/agent-traces',
    name: 'admin-agent-traces',
    component: () => import('../views/AdminAgentTracesView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['audit:read'] },
  },
  {
    path: '/admin/ops-cases',
    name: 'admin-ops-cases',
    component: () => import('../views/AdminOpsCasesView.vue'),
    meta: { requiresAuth: true, requiredPermissions: ['audit:read'] },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('../views/ForbiddenView.vue'),
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/NotFoundView.vue'),
  },
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 };
  },
});

router.beforeEach(async (to) => {
  const authed = await initAuth();
  if (to.meta.requiresAuth && !authed) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    };
  }
  const requiredPermissions = Array.isArray(to.meta.requiredPermissions) ? to.meta.requiredPermissions : [];
  if (requiredPermissions.length) {
    if (!hasAllPermissions(authState.user, requiredPermissions as string[])) {
      return { name: 'forbidden' };
    }
  } else if (to.meta.requiresAdmin) {
    if (!hasAllPermissions(authState.user, ['admin:access'])) {
      return { name: 'forbidden' };
    }
  }
  return true;
});
