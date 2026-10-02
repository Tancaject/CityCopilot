<script setup lang="ts">
import { useRoute } from 'vue-router';
import BookingConfirmationCard from '../components/BookingConfirmationCard.vue';
const route = useRoute();
</script>
<template><main class="booking-page"><h1>核对预约操作</h1><p>打开页面只查询确认状态。请核对具体方案后，再点击批准。</p><BookingConfirmationCard :operation-id="String(route.params.id)" /><RouterLink to="/bookings">查看我的预约</RouterLink></main></template>
