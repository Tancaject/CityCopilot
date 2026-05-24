<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { AlertCircle, ArrowLeft, Heart, Loader2, RefreshCw, Store, UserPlus } from '@lucide/vue';
import { checkFollow, fetchBlog, fetchBlogLikes, followUser, likeBlog } from '../api/hmdp';
import { authState, initAuth } from '../composables/auth';
import { assetUrl, blogContentText, firstImage } from '../utils/assets';
import type { Blog, UserDTO } from '../types';

const props = defineProps<{
  id: number;
}>();

const route = useRoute();
const router = useRouter();

const blog = ref<Blog | null>(null);
const likedUsers = ref<UserDTO[]>([]);
const following = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const actionMessage = ref('');
const secondaryMessage = ref('');

const content = computed(() => blogContentText(blog.value?.content));
const images = computed(() => blog.value?.images.split(',').filter(Boolean).map(firstImage) || []);
const canFollow = computed(() => Boolean(authState.isLoggedIn && blog.value?.userId && blog.value.userId !== authState.user?.id));

async function loadBlog() {
  loading.value = true;
  errorMessage.value = '';
  actionMessage.value = '';
  secondaryMessage.value = '';
  try {
    blog.value = await fetchBlog(props.id);
    await loadBlogMeta();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '笔记详情加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadBlogMeta() {
  const failures: string[] = [];
  try {
    likedUsers.value = await fetchBlogLikes(props.id);
  } catch {
    likedUsers.value = [];
    failures.push('点赞用户');
  }

  try {
    await initAuth();
    if (canFollow.value && blog.value?.userId) {
      following.value = await checkFollow(blog.value.userId);
    } else {
      following.value = false;
    }
  } catch {
    following.value = false;
    failures.push('关注状态');
  }

  secondaryMessage.value = failures.length ? `${failures.join('、')}暂时不可用` : '';
}

async function toggleLike() {
  actionMessage.value = '';
  if (!authState.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } });
    return;
  }
  if (!blog.value) {
    return;
  }
  try {
    await likeBlog(blog.value.id);
    blog.value = await fetchBlog(blog.value.id);
    try {
      likedUsers.value = await fetchBlogLikes(blog.value.id);
    } catch {
      secondaryMessage.value = '点赞用户暂时不可用';
    }
  } catch (error) {
    actionMessage.value = error instanceof Error ? error.message : '点赞失败';
  }
}

async function toggleFollow() {
  actionMessage.value = '';
  if (!authState.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } });
    return;
  }
  if (!blog.value?.userId) {
    return;
  }
  try {
    await followUser(blog.value.userId, !following.value);
    following.value = !following.value;
  } catch (error) {
    actionMessage.value = error instanceof Error ? error.message : '关注失败';
  }
}

onMounted(loadBlog);

watch(
  () => props.id,
  () => {
    loadBlog();
  },
);
</script>

<template>
  <section class="detail-shell">
    <RouterLink class="back-link" :to="{ name: 'home' }">
      <ArrowLeft :size="17" aria-hidden="true" />
      首页
    </RouterLink>

    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
      <button type="button" @click="loadBlog">
        <RefreshCw :size="16" aria-hidden="true" />
        重试
      </button>
    </section>

    <section v-if="actionMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ actionMessage }}
    </section>

    <section v-if="secondaryMessage" class="notice">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ secondaryMessage }}
    </section>

    <div v-if="loading" class="loading-row detail-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在加载
    </div>

    <article v-else-if="blog" class="blog-detail">
      <div class="blog-detail-gallery">
        <img v-for="image in images" :key="image" :src="image" :alt="blog.title" />
      </div>

      <section class="blog-detail-body">
        <p class="eyebrow">Review note</p>
        <h1>{{ blog.title }}</h1>

        <div class="blog-author">
          <img :src="assetUrl(blog.icon)" :alt="blog.name || '用户头像'" />
          <div>
            <strong>{{ blog.name || '匿名用户' }}</strong>
            <span>{{ blog.createTime || '刚刚发布' }}</span>
          </div>
          <button v-if="canFollow" type="button" @click="toggleFollow">
            <UserPlus :size="17" aria-hidden="true" />
            {{ following ? '已关注' : '关注' }}
          </button>
        </div>

        <p class="blog-content">{{ content || '这篇笔记暂时没有正文。' }}</p>

        <div class="detail-actions">
          <button class="like-button" :class="{ active: blog.isLike }" type="button" @click="toggleLike">
            <Heart :size="17" aria-hidden="true" />
            {{ blog.liked || 0 }}
          </button>
          <RouterLink v-if="blog.shopId" class="shop-pill" :to="{ name: 'shop-detail', params: { id: blog.shopId } }">
            <Store :size="17" aria-hidden="true" />
            关联店铺
          </RouterLink>
        </div>

        <div v-if="likedUsers.length" class="liked-users">
          <img v-for="user in likedUsers" :key="user.id" :src="assetUrl(user.icon)" :alt="user.nickName || user.name || '用户头像'" />
        </div>
      </section>
    </article>

    <div v-else class="empty-state">没有找到这篇笔记</div>
  </section>
</template>
