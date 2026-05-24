<script setup lang="ts">
import { ArrowLeft, ShieldAlert } from '@lucide/vue';
</script>

<template>
  <section class="not-found-page">
    <ShieldAlert :size="42" aria-hidden="true" />
    <h1>需要管理员权限</h1>
    <p>这个入口会改写店铺坐标和 Redis GEO 索引，需要管理员账号重新登录后访问。</p>
    <RouterLink class="primary-link" :to="{ name: 'home' }">
      <ArrowLeft :size="17" aria-hidden="true" />
      返回首页
    </RouterLink>
  </section>
</template>
