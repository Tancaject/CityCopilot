<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { deleteData, postData } from '../api/client';
import { getErrorMessage } from '../utils/errors';
const props = defineProps<{ shopId: string; impressionId: string }>();
const element = ref<HTMLElement | null>(null);
const message = ref('');
const busy = ref(false);
let observer: IntersectionObserver;
async function feedback(eventType: string) {
  if (eventType !== 'VIEW') busy.value = true;
  try {
    await postData('/booking-feedback', { shopId: props.shopId, impressionId: props.impressionId, eventType });
    if (eventType === 'FAVORITE') message.value = '已记录喜欢这家店，后续排序会参考。';
    if (eventType === 'NOT_INTERESTED') message.value = '已记录不感兴趣；可以随时恢复推荐。';
  } catch (error) { message.value = getErrorMessage(error, '反馈未保存，请稍后重试。'); }
  finally { busy.value = false; }
}
async function clearNegative() {
  busy.value = true;
  try { await deleteData(`/booking-feedback/negative/${props.shopId}`); message.value = '已清除对这家店的不感兴趣记录。'; }
  catch (error) { message.value = getErrorMessage(error, '清除失败，请稍后重试。'); }
  finally { busy.value = false; }
}
onMounted(() => {
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) { observer.disconnect(); void feedback('VIEW'); }
  }, { threshold: .5 });
  if (element.value) observer.observe(element.value);
});
onUnmounted(() => observer?.disconnect());
</script>
<template>
  <div ref="element" class="booking-feedback">
    <div class="booking-actions">
      <RouterLink :to="`/shops/${shopId}`" @click="feedback('CLICK')">查看商家</RouterLink>
      <button class="btn btn-ghost" :disabled="busy" @click="feedback('FAVORITE')">喜欢</button>
      <button class="btn btn-ghost" :disabled="busy" @click="feedback('NOT_INTERESTED')">不感兴趣</button>
      <button class="btn btn-ghost" :disabled="busy" @click="clearNegative">恢复推荐</button>
    </div>
    <small v-if="message" role="status">{{ message }}</small>
  </div>
</template>
