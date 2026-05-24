<script setup lang="ts">
import { ArrowLeft } from '@lucide/vue';
import { RouterLink } from 'vue-router';
</script>

<template>
  <section class="not-found-page">
    <p class="eyebrow">404</p>
    <h1>页面不存在</h1>
    <p>这个入口已经失效，或者地址里包含了无效的编号。</p>
    <RouterLink class="primary-link" :to="{ name: 'home' }">
      <ArrowLeft :size="17" aria-hidden="true" />
      回到首页
    </RouterLink>
  </section>
</template>
