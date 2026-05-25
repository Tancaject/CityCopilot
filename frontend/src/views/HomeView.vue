<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { AlertCircle, Heart, Loader2, RefreshCw, Store } from '@lucide/vue';
import { fetchBlog, fetchHotBlogs, fetchShopTypes, likeBlog } from '../api/citycopilot';
import { authState } from '../composables/auth';
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

onMounted(loadHome);
</script>

<template>
  <section class="hero-panel">
    <div class="hero-copy">
      <p class="eyebrow">今日附近灵感</p>
      <h1>把附近值得去的店，收进一个清爽的首页。</h1>
      <p>从热门探店、分类入口到店铺优惠，把真实用户的选择和今日可用的活动放在同一屏里。</p>
    </div>
    <div class="hero-metric" aria-label="首页数据概览">
      <span>{{ shopTypes.length }}</span>
      <small>店铺分类</small>
    </div>
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

  <section class="content-grid">
    <aside class="category-panel">
      <div class="section-heading">
        <Store :size="19" aria-hidden="true" />
        <h2>分类</h2>
      </div>

      <div v-if="loading" class="loading-row">
        <Loader2 class="spin" :size="18" aria-hidden="true" />
        正在加载
      </div>

      <div v-else class="category-grid">
        <button v-for="type in shopTypes" :key="type.id" class="category-item" type="button" @click="openType(type)">
          <img :src="assetUrl(type.icon)" :alt="type.name" />
          <span>{{ type.name }}</span>
        </button>
      </div>
    </aside>

    <section class="feed-panel">
      <div class="section-heading">
        <Heart :size="19" aria-hidden="true" />
        <h2>热门探店</h2>
      </div>

      <div v-if="loading" class="feed-skeleton">
        <article v-for="item in 4" :key="item" class="skeleton-card" />
      </div>

      <div v-else-if="hasBlogs" class="blog-grid">
        <article v-for="blog in blogs" :key="blog.id" class="blog-card">
          <RouterLink :to="{ name: 'blog-detail', params: { id: blog.id } }">
            <img class="blog-cover" :src="blogCover(blog)" :alt="blog.title" />
          </RouterLink>
          <div class="blog-body">
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
</template>
