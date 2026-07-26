<script setup lang="ts">
import { computed, ref } from 'vue';
import { MapPin } from '@lucide/vue';
import AddressPickerModal from './AddressPickerModal.vue';
import { locationState } from '../composables/location';

const props = withDefaults(
  defineProps<{
    tone?: 'light' | 'dark';
  }>(),
  {
    tone: 'light',
  },
);

const pickerOpen = ref(false);
const isLocating = computed(() => locationState.status === 'loading');
const locationLabel = computed(() => {
  if (isLocating.value) {
    return '正在定位当前位置';
  }
  return locationState.label || '选择消费位置';
});
const helperText = computed(() => {
  if (isLocating.value) {
    return '授权后会按当前位置推荐附近店铺';
  }
  if (!locationState.point && locationState.errorMessage) {
    return locationState.errorMessage;
  }
  if (locationState.address) {
    return locationState.address;
  }
  if (locationState.point) {
    return '已按此位置推荐附近店铺';
  }
  return '可手动搜索地址或在地图上选点';
});
const actionLabel = computed(() => {
  if (isLocating.value) {
    return '定位中';
  }
  return locationState.point ? '切换地址' : '选择位置';
});
</script>

<template>
  <div
    class="consumer-location-selector"
    :class="[`consumer-location-selector-${props.tone}`, { active: locationState.point, loading: isLocating }]"
  >
    <button class="consumer-location-main" type="button" @click="pickerOpen = true">
      <MapPin :size="17" aria-hidden="true" />
      <span>
        <strong>{{ locationLabel }}</strong>
        <small>{{ helperText }}</small>
      </span>
      <em>{{ actionLabel }}</em>
    </button>
    <AddressPickerModal :open="pickerOpen" @close="pickerOpen = false" />
  </div>
</template>
