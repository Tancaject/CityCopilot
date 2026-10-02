<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { AlertCircle, Heart, Loader2, RefreshCw, Rss } from '@lucide/vue';
import { fetchFollowBlogs } from '../api/citycopilot';
import { assetUrl, blogCover } from '../utils/assets';
import { getErrorMessage } from '../utils/errors';
import { getPaginationLabel } from '../utils/labels';
import type { Blog } from '../types';

const blogs = ref<Blog[]>([]);
const lastId = ref(Date.now());
const offset = ref(0);
const loading = ref(false);
const loadingMore = ref(false);
const hasMore = ref(true);
const errorMessage = ref('');

async function loadFirstPage() {
  loading.value = true;
  errorMessage.value = '';
  hasMore.value = true;
  try {
    const result = await fetchFollowBlogs(Date.now(), 0);
    blogs.value = result.list;
    lastId.value = result.minTime;
    offset.value = result.offset;
    hasMore.value = result.list.length > 0;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '关注流加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value) {
    return;
  }
  loadingMore.value = true;
  errorMessage.value = '';
  try {
    const result = await fetchFollowBlogs(lastId.value, offset.value);
    if (!result.list.length) {
      hasMore.value = false;
      return;
    }
    blogs.value = blogs.value.concat(result.list);
    lastId.value = result.minTime;
    offset.value = result.offset;
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '加载更多失败');
  } finally {
    loadingMore.value = false;
  }
}

onMounted(loadFirstPage);
</script>

<template>
  <section class="list-hero">
    <p class="eyebrow">Following</p>
    <h1>关注流</h1>
    <p>按发布时间浏览当前关注作者的笔记，包含历史内容。</p>
  </section>

  <section v-if="errorMessage" class="notice notice-error">
    <AlertCircle :size="18" aria-hidden="true" />
    {{ errorMessage }}
    <button type="button" @click="loadFirstPage">
      <RefreshCw :size="16" aria-hidden="true" />
      重试
    </button>
  </section>

  <section class="feed-panel">
    <div class="section-heading">
      <Rss :size="19" aria-hidden="true" />
      <h2>最新动态</h2>
    </div>

    <div v-if="loading" class="feed-skeleton">
      <article v-for="item in 4" :key="item" class="skeleton-card" />
    </div>

    <div v-else-if="blogs.length" class="blog-grid">
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
            <span class="like-button">
              <Heart :size="16" aria-hidden="true" />
              {{ blog.liked || 0 }}
            </span>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="empty-state">关注的人还没有新笔记</div>

    <button class="load-more" type="button" :disabled="loading || loadingMore || !hasMore" @click="loadMore">
      <Loader2 v-if="loadingMore" class="spin" :size="17" aria-hidden="true" />
      {{ getPaginationLabel(loadingMore, hasMore) }}
    </button>
  </section>
</template>
