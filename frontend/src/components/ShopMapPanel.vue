<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { MapPinned, Navigation } from '@lucide/vue';
import { hasAmapKey, loadAmap } from '../utils/amap';
import { getErrorMessage } from '../utils/errors';

const props = defineProps<{
  x?: number;
  y?: number;
  name: string;
  address?: string;
}>();

const mapEl = ref<HTMLElement | null>(null);
const mapReady = ref(false);
const mapError = ref('');
let mapInstance: unknown = null;

const hasCoordinate = computed(() => Number.isFinite(props.x) && Number.isFinite(props.y));
const coordinateText = computed(() => {
  if (!hasCoordinate.value) {
    return '';
  }
  return `${props.x?.toFixed(6)}, ${props.y?.toFixed(6)}`;
});

async function renderMap() {
  mapReady.value = false;
  mapError.value = '';
  mapInstance = null;
  if (!hasCoordinate.value || !hasAmapKey()) {
    return;
  }
  await nextTick();
  if (!mapEl.value) {
    return;
  }
  try {
    const AMap = await loadAmap();
    if (!AMap || !mapEl.value) {
      return;
    }
    const center = [props.x as number, props.y as number];
    mapInstance = new AMap.Map(mapEl.value, {
      center,
      zoom: 16,
      viewMode: '2D',
      resizeEnable: true,
    });
    new AMap.Marker({
      map: mapInstance,
      position: center,
      title: props.name,
    });
    mapReady.value = true;
  } catch (error) {
    mapError.value = getErrorMessage(error, '地图加载失败');
  }
}

onMounted(renderMap);
onBeforeUnmount(() => {
  mapInstance = null;
});

watch(
  () => [props.x, props.y],
  () => {
    renderMap();
  },
);
</script>

<template>
  <section v-if="hasCoordinate" class="shop-map-panel">
    <div class="section-heading map-heading">
      <MapPinned :size="19" aria-hidden="true" />
      <h2>门店位置</h2>
    </div>

    <div class="map-stage" :class="{ ready: mapReady }">
      <div ref="mapEl" class="amap-canvas" aria-hidden="true" />
      <div v-if="!mapReady" class="map-fallback">
        <Navigation :size="22" aria-hidden="true" />
        <strong>{{ props.name }}</strong>
        <span>{{ props.address || '地址待补充' }}</span>
        <small>{{ coordinateText }}</small>
      </div>
    </div>

    <p v-if="mapError" class="form-hint">{{ mapError }}</p>
  </section>
</template>
