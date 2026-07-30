<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { Crosshair, Loader2, MapPin, Search, X } from '@lucide/vue';
import {
  hasAmapKey,
  loadAmap,
  type AmapApi,
  type AmapGeocoder,
  type AmapLngLat,
  type AmapMap,
  type AmapMarker,
  type AmapPlaceSearch,
  type AmapPoiSearchItem,
} from '../utils/amap';
import {
  locationState,
  requestBrowserLocationPoint,
  setSelectedLocation,
  type LocationSource,
  type SelectedLocationInput,
} from '../composables/location';
import type { GeoPoint } from '../types';

interface LocationCandidate extends SelectedLocationInput {
  id?: string;
}

const props = defineProps<{
  open: boolean;
}>();
const emit = defineEmits<{
  close: [];
}>();

const DEFAULT_CENTER: GeoPoint = { x: 120.155, y: 30.274 };

const defaultCity = computed(() => import.meta.env.VITE_DEFAULT_CITY || '杭州');
const mapEl = ref<HTMLElement | null>(null);
const searchKeyword = ref('');
const searchResults = ref<LocationCandidate[]>([]);
const selected = ref<LocationCandidate | null>(null);
const mapError = ref('');
const searchError = ref('');
const loadingMap = ref(false);
const searching = ref(false);
const locating = ref(false);

let amap: AmapApi | null = null;
let mapInstance: AmapMap | null = null;
let marker: AmapMarker | null = null;
let geocoder: AmapGeocoder | null = null;
let placeSearch: AmapPlaceSearch | null = null;
let reverseRequestId = 0;

const selectedAddress = computed(() => selected.value?.address || selected.value?.label || '请搜索地址或在地图上点选');
const canConfirm = computed(() => Boolean(selected.value?.point));

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      destroyMap();
      return;
    }
    resetModal();
    await nextTick();
    await initializeMap();
  },
);

onBeforeUnmount(destroyMap);

function resetModal() {
  const current = locationState.selectedLocation;
  selected.value = current
    ? {
        point: current.point,
        label: current.label,
        address: current.address,
        source: current.source,
        city: current.city,
        adcode: current.adcode,
      }
    : null;
  searchKeyword.value = current?.label || '';
  searchResults.value = [];
  mapError.value = '';
  searchError.value = '';
}

async function initializeMap() {
  if (!hasAmapKey()) {
    mapError.value = '地图服务未配置：请在前端环境变量中配置 VITE_AMAP_JS_KEY';
    return;
  }
  if (!mapEl.value) {
    return;
  }
  loadingMap.value = true;
  try {
    amap = await loadAmap();
    if (!amap) {
      mapError.value = '地图服务未配置';
      return;
    }
    mapInstance = new amap.Map(mapEl.value, {
      zoom: 14,
      center: pointToPosition(selected.value?.point || DEFAULT_CENTER),
      viewMode: '2D',
    });
    if (amap.Geocoder) {
      geocoder = new amap.Geocoder({ city: defaultCity.value });
    }
    if (amap.PlaceSearch) {
      placeSearch = new amap.PlaceSearch({
        city: defaultCity.value,
        citylimit: false,
        pageSize: 8,
        pageIndex: 1,
        extensions: 'base',
      });
    }
    mapInstance.on('click', (event) => {
      const point = pointFromLngLat(event.lnglat);
      if (point) {
        void choosePoint(point, 'map');
      }
    });
    if (selected.value?.point) {
      ensureMarker(selected.value.point);
    }
  } catch (error) {
    mapError.value = error instanceof Error ? error.message : '地图加载失败';
  } finally {
    loadingMap.value = false;
  }
}

function destroyMap() {
  marker?.setMap?.(null);
  marker = null;
  mapInstance?.destroy();
  mapInstance = null;
  geocoder = null;
  placeSearch = null;
}

async function ensureMapServices() {
  if (!hasAmapKey()) {
    searchError.value = '地图服务未配置，暂时不能搜索地址';
    return false;
  }
  if (!amap) {
    amap = await loadAmap();
  }
  if (!amap) {
    searchError.value = '地图服务未配置';
    return false;
  }
  if (!placeSearch && amap.PlaceSearch) {
    placeSearch = new amap.PlaceSearch({
      city: defaultCity.value,
      citylimit: false,
      pageSize: 8,
      pageIndex: 1,
      extensions: 'base',
    });
  }
  if (!geocoder && amap.Geocoder) {
    geocoder = new amap.Geocoder({ city: defaultCity.value });
  }
  return true;
}

async function searchPlaces() {
  const keyword = searchKeyword.value.trim();
  if (!keyword || searching.value) {
    return;
  }
  searchError.value = '';
  searching.value = true;
  try {
    const ready = await ensureMapServices();
    if (!ready || !placeSearch) {
      if (!searchError.value) {
        searchError.value = '地图搜索插件不可用';
      }
      return;
    }
    const results = await new Promise<LocationCandidate[]>((resolve) => {
      placeSearch?.search(keyword, (status, result) => {
        if (status !== 'complete' || typeof result === 'string') {
          resolve([]);
          return;
        }
        resolve((result.poiList?.pois || []).map(candidateFromPoi).filter(Boolean) as LocationCandidate[]);
      });
    });
    searchResults.value = results;
    if (!results.length) {
      searchError.value = '没有找到匹配地址';
    }
  } finally {
    searching.value = false;
  }
}

function candidateFromPoi(poi: AmapPoiSearchItem): LocationCandidate | null {
  const point = pointFromLngLat(poi.location);
  if (!point || !poi.name) {
    return null;
  }
  const addressParts = [poi.pname, poi.cityname, poi.adname, poi.address].filter(Boolean);
  return {
    id: poi.id,
    point,
    label: poi.name,
    address: addressParts.join(''),
    source: 'search',
    city: typeof poi.cityname === 'string' ? poi.cityname : undefined,
    adcode: poi.adcode,
  };
}

async function chooseCandidate(candidate: LocationCandidate) {
  selected.value = candidate;
  ensureMarker(candidate.point);
  centerMap(candidate.point);
  if (!candidate.address) {
    await enrichAddress(candidate.point, candidate.label, candidate.source);
  }
}

async function choosePoint(point: GeoPoint, source: LocationSource) {
  selected.value = {
    point,
    label: source === 'browser' ? '当前位置' : '地图选点',
    source,
  };
  ensureMarker(point);
  centerMap(point);
  await enrichAddress(point, selected.value.label, source);
}

async function enrichAddress(point: GeoPoint, fallbackLabel: string, source: LocationSource) {
  const currentRequest = ++reverseRequestId;
  const address = await reverseGeocode(point);
  if (currentRequest !== reverseRequestId || !selected.value) {
    return;
  }
  selected.value = {
    ...selected.value,
    point,
    source,
    label: address.label || fallbackLabel,
    address: address.address,
    city: address.city,
    adcode: address.adcode,
  };
}

async function reverseGeocode(point: GeoPoint) {
  await ensureMapServices();
  if (!geocoder) {
    return {
      label: '',
      address: '',
      city: '',
      adcode: '',
    };
  }
  return new Promise<{ label: string; address: string; city: string; adcode: string }>((resolve) => {
    geocoder?.getAddress(pointToPosition(point), (status, result) => {
      if (status !== 'complete' || typeof result === 'string') {
        resolve({ label: '', address: '', city: '', adcode: '' });
        return;
      }
      const regeocode = result.regeocode;
      const component = regeocode?.addressComponent;
      const city = Array.isArray(component?.city) ? '' : component?.city || defaultCity.value;
      resolve({
        label: component?.district || regeocode?.formattedAddress || '地图选点',
        address: regeocode?.formattedAddress || '',
        city,
        adcode: component?.adcode || '',
      });
    });
  });
}

async function useBrowserLocation() {
  locating.value = true;
  searchError.value = '';
  try {
    const point = await requestBrowserLocationPoint();
    if (!point) {
      searchError.value = locationState.errorMessage || '定位失败';
      return;
    }
    await choosePoint(point, 'browser');
  } finally {
    locating.value = false;
  }
}

function confirmSelection() {
  if (!selected.value) {
    return;
  }
  setSelectedLocation({
    point: selected.value.point,
    label: selected.value.label,
    address: selected.value.address,
    source: selected.value.source,
    coordinateSystem: 'gcj02',
    city: selected.value.city,
    adcode: selected.value.adcode,
  });
  emit('close');
}

function ensureMarker(point: GeoPoint) {
  if (!amap || !mapInstance) {
    return;
  }
  if (!marker) {
    marker = new amap.Marker({
      map: mapInstance,
      position: pointToPosition(point),
      draggable: true,
    });
    marker.on('dragend', (event) => {
      const pointFromEvent = pointFromLngLat(event.lnglat) || pointFromLngLat(marker?.getPosition());
      if (pointFromEvent) {
        void choosePoint(pointFromEvent, 'map');
      }
    });
    return;
  }
  marker.setPosition(pointToPosition(point));
}

function centerMap(point: GeoPoint) {
  if (!mapInstance) {
    return;
  }
  if (mapInstance.setZoomAndCenter) {
    mapInstance.setZoomAndCenter(15, pointToPosition(point));
    return;
  }
  mapInstance.setCenter(pointToPosition(point));
}

function pointToPosition(point: GeoPoint): [number, number] {
  return [point.x, point.y];
}

function pointFromLngLat(value?: AmapLngLat | null): GeoPoint | null {
  if (!value) {
    return null;
  }
  const x = typeof value.getLng === 'function' ? value.getLng() : value.lng;
  const y = typeof value.getLat === 'function' ? value.getLat() : value.lat;
  return Number.isFinite(x) && Number.isFinite(y) ? { x: Number(x), y: Number(y) } : null;
}
</script>

<template>
  <Teleport to="body">
    <div v-if="props.open" class="address-picker-backdrop">
      <section class="address-picker" role="dialog" aria-modal="true" aria-labelledby="address-picker-title">
        <header>
          <div>
            <p class="eyebrow">消费位置</p>
            <h2 id="address-picker-title">切换推荐地址</h2>
          </div>
          <button type="button" aria-label="关闭地址选择器" @click="emit('close')">
            <X :size="18" aria-hidden="true" />
          </button>
        </header>

        <form class="address-search" @submit.prevent="searchPlaces">
          <Search :size="17" aria-hidden="true" />
          <input v-model="searchKeyword" type="search" placeholder="搜索小区、商圈、地铁站" />
          <button type="submit" :disabled="searching || !searchKeyword.trim()">
            <Loader2 v-if="searching" class="spin" :size="15" aria-hidden="true" />
            搜索
          </button>
        </form>

        <div class="address-picker-body">
          <aside class="address-results">
            <button class="use-current-location" type="button" :disabled="locating" @click="useBrowserLocation">
              <Crosshair v-if="!locating" :size="17" aria-hidden="true" />
              <Loader2 v-else class="spin" :size="17" aria-hidden="true" />
              使用当前位置
            </button>
            <p v-if="searchError" class="form-error">{{ searchError }}</p>
            <button
              v-for="candidate in searchResults"
              :key="candidate.id || `${candidate.point.x}-${candidate.point.y}`"
              class="address-result"
              :class="{ active: selected?.point.x === candidate.point.x && selected?.point.y === candidate.point.y }"
              type="button"
              @click="chooseCandidate(candidate)"
            >
              <strong>{{ candidate.label }}</strong>
              <span>{{ candidate.address || '地址待补充' }}</span>
            </button>
            <p v-if="!searchResults.length && !searchError" class="form-hint">可以搜索地址，也可以直接在地图上点选。</p>
          </aside>

          <div class="address-map-shell">
            <div ref="mapEl" class="address-map-canvas" />
            <div v-if="loadingMap || mapError" class="address-map-state">
              <Loader2 v-if="loadingMap" class="spin" :size="18" aria-hidden="true" />
              <MapPin v-else :size="18" aria-hidden="true" />
              <span>{{ loadingMap ? '地图加载中' : mapError }}</span>
            </div>
          </div>
        </div>

        <footer>
          <span>
            <strong>{{ selected?.label || '尚未选择位置' }}</strong>
            <small>{{ selectedAddress }}</small>
          </span>
          <div>
            <button type="button" @click="emit('close')">取消</button>
            <button class="address-confirm" type="button" :disabled="!canConfirm" @click="confirmSelection">
              确认使用此位置
            </button>
          </div>
        </footer>
      </section>
    </div>
  </Teleport>
</template>
