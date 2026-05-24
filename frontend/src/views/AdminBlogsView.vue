<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { AlertCircle, Loader2, RefreshCw, Search, Trash2 } from '@lucide/vue';
import { deleteAdminBlog, fetchAdminBlogs } from '../api/hmdp';
import { blogContentText, blogCover } from '../utils/assets';
import type { Blog } from '../types';

const blogs = ref<Blog[]>([]);
const keyword = ref('');
const loading = ref(false);
const deletingId = ref<number | null>(null);
const errorMessage = ref('');

function formatTime(value?: string) {
  if (!value) {
    return '未记录';
  }
  return value.replace('T', ' ').slice(0, 16);
}

async function loadBlogs() {
  loading.value = true;
  errorMessage.value = '';
  try {
    blogs.value = await fetchAdminBlogs(1, keyword.value.trim() || undefined);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '笔记加载失败';
  } finally {
    loading.value = false;
  }
}

async function handleDelete(blog: Blog) {
  if (!window.confirm(`确认删除「${blog.title || blog.id}」？`)) {
    return;
  }
  deletingId.value = blog.id;
  errorMessage.value = '';
  try {
    await deleteAdminBlog(blog.id);
    blogs.value = blogs.value.filter((item) => item.id !== blog.id);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '删除失败';
  } finally {
    deletingId.value = null;
  }
}

onMounted(loadBlogs);
</script>

<template>
  <section class="admin-blogs-page">
    <header class="orders-header">
      <div>
        <p class="eyebrow">内容治理</p>
        <h1>探店笔记</h1>
      </div>
      <button type="button" :disabled="loading" @click="loadBlogs">
        <RefreshCw :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <form class="admin-user-search" @submit.prevent="loadBlogs">
      <input v-model="keyword" type="search" placeholder="搜索标题或正文" />
      <button type="submit" :disabled="loading">
        <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
        <Search v-else :size="17" aria-hidden="true" />
        查询
      </button>
    </form>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <div v-if="loading" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在加载笔记
    </div>

    <section v-else-if="blogs.length" class="admin-blog-list">
      <article v-for="blog in blogs" :key="blog.id" class="admin-blog-row">
        <img :src="blogCover(blog)" :alt="blog.title" />
        <div class="admin-blog-main">
          <strong>{{ blog.title || `笔记 ${blog.id}` }}</strong>
          <p>{{ blogContentText(blog.content).slice(0, 120) || '没有正文摘要' }}</p>
          <span>#{{ blog.id }} · 用户 {{ blog.userId }} · {{ formatTime(blog.createTime) }}</span>
        </div>
        <button type="button" :disabled="deletingId === blog.id" @click="handleDelete(blog)">
          <Loader2 v-if="deletingId === blog.id" class="spin" :size="15" aria-hidden="true" />
          <Trash2 v-else :size="15" aria-hidden="true" />
          删除
        </button>
      </article>
    </section>

    <div v-else class="empty-state orders-empty">
      没有匹配笔记
    </div>
  </section>
</template>
