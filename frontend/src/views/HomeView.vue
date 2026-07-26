<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { AlertCircle, BadgeCheck, Heart, Loader2, MapPin, Navigation, RefreshCw, Sparkles, Store, Ticket } from '@lucide/vue';
import { fetchBlog, fetchHotBlogs, fetchShopTypes, likeBlog } from '../api/citycopilot';
import ConsumerLocationSelector from '../components/ConsumerLocationSelector.vue';
import { authState } from '../composables/auth';
import { ensureDefaultCurrentLocation } from '../composables/location';
import { assetUrl, blogCover } from '../utils/assets';
import type { Blog, ShopType } from '../types';

const router = useRouter();
const route = useRoute();

const shopTypes = ref<ShopType[]>([]);
const blogs = ref<Blog[]>([]);
const currentPage = ref(1);
const loading = ref(false);
const loadingMore = ref(false);
const hasMore = ref(true);
const errorMessage = ref('');
const authMessage = ref('');

const hasBlogs = computed(() => blogs.value.length > 0);
const leadBlog = computed(() => blogs.value[0]);
const featuredBlogs = computed(() => blogs.value.slice(0, 4));
const totalLikes = computed(() => blogs.value.reduce((sum, blog) => sum + (blog.liked || 0), 0));
const topShopTypes = computed(() => shopTypes.value.slice(0, 8));
const cityName = computed(() => import.meta.env.VITE_DEFAULT_CITY || '杭州');
const insightCards = computed(() => [
  {
    label: '今日可行动',
    value: `${shopTypes.value.length || '--'} 类`,
    detail: '从分类、距离和券包切入',
    icon: Navigation,
  },
  {
    label: '内容热度',
    value: `${blogs.value.length || '--'} 条`,
    detail: `${totalLikes.value || 0} 次点赞信号`,
    icon: Heart,
  },
  {
    label: '交易入口',
    value: '券包',
    detail: '详情页内完成秒杀和订单追踪',
    icon: Ticket,
  },
]);

async function loadHome() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const [types, hotBlogs] = await Promise.all([fetchShopTypes(), fetchHotBlogs(1)]);
    shopTypes.value = types;
    blogs.value = hotBlogs;
    currentPage.value = 1;
    hasMore.value = hotBlogs.length > 0;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '首页数据加载失败';
  } finally {
    loading.value = false;
  }
}

async function openType(type: ShopType) {
  await router.push({
    name: 'shops',
    query: {
      typeId: String(type.id),
      typeName: type.name,
    },
  });
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value) {
    return;
  }
  loadingMore.value = true;
  errorMessage.value = '';
  try {
    const nextPage = currentPage.value + 1;
    const nextBlogs = await fetchHotBlogs(nextPage);
    if (!nextBlogs.length) {
      hasMore.value = false;
      return;
    }
    blogs.value = blogs.value.concat(nextBlogs);
    currentPage.value = nextPage;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载更多失败';
  } finally {
    loadingMore.value = false;
  }
}

async function toggleLike(blog: Blog) {
  authMessage.value = '';
  if (!authState.isLoggedIn) {
    authMessage.value = '登录后可以点赞、发布和查看关注流';
    await router.push({ name: 'login', query: { redirect: route.fullPath } });
    return;
  }
  try {
    await likeBlog(blog.id);
    const fresh = await fetchBlog(blog.id);
    blog.liked = fresh.liked;
    blog.isLike = fresh.isLike;
  } catch (error) {
    authMessage.value = error instanceof Error ? error.message : '点赞失败';
  }
}

onMounted(() => {
  void loadHome();
  void ensureDefaultCurrentLocation();
});
</script>

<template>
  <section class="home-command">
    <div class="home-command-copy">
      <p class="eyebrow">City operating layer · {{ cityName }}</p>
      <h1>
        <span>今晚去哪，别靠刷。</span>
        <span>让 CityCopilot 把附近选择压成一张行动图。</span>
      </h1>
      <p>
        同一屏看分类、热门笔记、优惠券和位置线索。目标不是展示更多卡片，而是帮用户更快决定一家现在值得去的店。
      </p>
      <ConsumerLocationSelector />
      <div class="home-command-actions">
        <RouterLink :to="{ name: 'shops' }">
          <Navigation :size="18" aria-hidden="true" />
          浏览附近店铺
        </RouterLink>
        <RouterLink :to="{ name: 'blog-create' }">
          <Sparkles :size="18" aria-hidden="true" />
          发布探店线索
        </RouterLink>
      </div>
    </div>

    <aside class="home-live-panel" aria-label="城市决策概览">
      <div class="home-live-head">
        <span>Live city signals</span>
        <strong>{{ cityName }}</strong>
      </div>
      <div class="home-signal-grid">
        <article v-for="card in insightCards" :key="card.label">
          <component :is="card.icon" :size="18" aria-hidden="true" />
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small>{{ card.detail }}</small>
        </article>
      </div>
      <RouterLink v-if="leadBlog" class="home-lead-note" :to="{ name: 'blog-detail', params: { id: leadBlog.id } }">
        <img :src="blogCover(leadBlog)" :alt="leadBlog.title" />
        <span>
          <small>当前热笔记</small>
          <strong>{{ leadBlog.title }}</strong>
        </span>
      </RouterLink>
    </aside>
  </section>

  <section v-if="authMessage" class="notice">
    <AlertCircle :size="18" aria-hidden="true" />
    {{ authMessage }}
  </section>

  <section v-if="errorMessage" class="notice notice-error">
    <AlertCircle :size="18" aria-hidden="true" />
    {{ errorMessage }}
    <button type="button" @click="loadHome">
      <RefreshCw :size="16" aria-hidden="true" />
      重试
    </button>
  </section>

  <section class="home-flow">
    <aside class="category-panel city-filter-panel">
      <div class="section-heading">
        <Store :size="19" aria-hidden="true" />
        <h2>城市入口</h2>
      </div>
      <p class="section-copy">先按场景缩小选择，再用距离、评分和券包完成决策。</p>

      <div v-if="loading" class="loading-row">
        <Loader2 class="spin" :size="18" aria-hidden="true" />
        正在加载
      </div>

      <div v-else class="category-grid">
        <button v-for="type in topShopTypes" :key="type.id" class="category-item" type="button" @click="openType(type)">
          <img :src="assetUrl(type.icon)" :alt="type.name" />
          <span>{{ type.name }}</span>
        </button>
      </div>
    </aside>

    <section class="feed-panel city-feed-panel">
      <div class="section-heading">
        <Heart :size="19" aria-hidden="true" />
        <h2>正在影响选择的笔记</h2>
      </div>
      <p class="section-copy">把内容从瀑布流改成决策信号：谁推荐、为什么值得去、热度是否可信。</p>

      <div v-if="loading" class="feed-skeleton">
        <article v-for="item in 4" :key="item" class="skeleton-card" />
      </div>

      <div v-else-if="hasBlogs" class="blog-grid">
        <article v-for="blog in featuredBlogs" :key="blog.id" class="blog-card city-note-card">
          <RouterLink :to="{ name: 'blog-detail', params: { id: blog.id } }">
            <img class="blog-cover" :src="blogCover(blog)" :alt="blog.title" />
          </RouterLink>
          <div class="blog-body">
            <span class="note-signal">
              <BadgeCheck :size="14" aria-hidden="true" />
              真实探店
            </span>
            <RouterLink :to="{ name: 'blog-detail', params: { id: blog.id } }">
              <h3>{{ blog.title }}</h3>
            </RouterLink>
            <div class="blog-meta">
              <span class="author">
                <img :src="assetUrl(blog.icon)" :alt="blog.name || '用户头像'" />
                {{ blog.name || '匿名用户' }}
              </span>
              <button
                class="like-button"
                :class="{ active: blog.isLike }"
                type="button"
                :aria-label="`点赞 ${blog.title}`"
                @click="toggleLike(blog)"
              >
                <Heart :size="16" aria-hidden="true" />
                {{ blog.liked || 0 }}
              </button>
            </div>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">暂时没有探店笔记</div>

      <button class="load-more" type="button" :disabled="loadingMore || loading || !hasMore" @click="loadMore">
        <Loader2 v-if="loadingMore" class="spin" :size="17" aria-hidden="true" />
        {{ loadingMore ? '加载中' : hasMore ? '加载更多' : '没有更多了' }}
      </button>
    </section>
  </section>

  <section class="home-ops-strip" aria-label="平台能力">
    <article>
      <MapPin :size="19" aria-hidden="true" />
      <strong>位置不是装饰</strong>
      <span>店铺列表、详情地图和地理校准台共用坐标数据，减少“看得到但找不到”的体验断点。</span>
    </article>
    <article>
      <Ticket :size="19" aria-hidden="true" />
      <strong>从发现到交易</strong>
      <span>优惠券、秒杀、订单轮询和核销形成闭环，项目不再只是点评式内容页。</span>
    </article>
    <article>
      <Sparkles :size="19" aria-hidden="true" />
      <strong>AI 应该缩短路径</strong>
      <span>助手会保留页面上下文，后续会继续升级成右侧任务面板而不是普通聊天窗。</span>
    </article>
  </section>
</template>
