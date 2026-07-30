import { computed, reactive } from 'vue';
import type { GeoPoint } from '../types';
import { convertGpsToAmapPoint } from '../utils/amap';

const SELECTED_LOCATION_KEY = 'citycopilot:selected-location';
const LEGACY_LOCATION_KEY = 'citycopilot:user-location';
const AUTO_LOCATION_ATTEMPT_KEY = 'citycopilot:auto-location-attempted';

type LocationStatus = 'idle' | 'loading' | 'ready' | 'denied' | 'unsupported' | 'error';
export type LocationSource = 'browser' | 'map' | 'search';
type BrowserLocationPermissionState = PermissionState | 'unknown';

export interface SelectedLocation {
  point: GeoPoint;
  label: string;
  address?: string;
  source: LocationSource;
  coordinateSystem: 'gcj02';
  city?: string;
  adcode?: string;
  updatedAt: number;
}

export type SelectedLocationInput = Omit<SelectedLocation, 'updatedAt' | 'coordinateSystem'> & {
  coordinateSystem?: 'gcj02';
  updatedAt?: number;
};

interface LocationState {
  point: GeoPoint | null;
  selectedLocation: SelectedLocation | null;
  label: string;
  address: string;
  source: LocationSource | '';
  city: string;
  adcode: string;
  updatedAt: number | null;
  status: LocationStatus;
  errorMessage: string;
}

function isValidPoint(point?: GeoPoint | null) {
  return Boolean(point)
    && Number.isFinite(point?.x)
    && Number.isFinite(point?.y)
    && Number(point?.x) >= -180
    && Number(point?.x) <= 180
    && Number(point?.y) >= -85.05112878
    && Number(point?.y) <= 85.05112878;
}

function normalizeStoredLocation(value: unknown): SelectedLocation | null {
  if (!value || typeof value !== 'object') {
    return null;
  }
  const location = value as Partial<SelectedLocation> & Partial<GeoPoint>;
  const normalizedSource: LocationSource =
    location.source === 'map' || location.source === 'search' || location.source === 'browser'
      ? location.source
      : 'browser';
  if (normalizedSource === 'browser' && location.coordinateSystem !== 'gcj02') {
    return null;
  }
  const point = location.point || {
    x: location.x,
    y: location.y,
  };
  if (!isValidPoint(point as GeoPoint)) {
    return null;
  }
  return {
    point: {
      x: Number((point as GeoPoint).x),
      y: Number((point as GeoPoint).y),
    },
    label: typeof location.label === 'string' && location.label.trim() ? location.label.trim() : '当前位置',
    address: typeof location.address === 'string' ? location.address : undefined,
    source: normalizedSource,
    coordinateSystem: 'gcj02',
    city: typeof location.city === 'string' ? location.city : undefined,
    adcode: typeof location.adcode === 'string' ? location.adcode : undefined,
    updatedAt: typeof location.updatedAt === 'number' ? location.updatedAt : Date.now(),
  };
}

function readStoredLocation(): SelectedLocation | null {
  const raw = sessionStorage.getItem(SELECTED_LOCATION_KEY) || sessionStorage.getItem(LEGACY_LOCATION_KEY);
  if (!raw) {
    return null;
  }
  try {
    return normalizeStoredLocation(JSON.parse(raw));
  } catch {
    return null;
  }
}

const storedLocation = readStoredLocation();

export const locationState = reactive<LocationState>({
  point: storedLocation?.point || null,
  selectedLocation: storedLocation,
  label: storedLocation?.label || '',
  address: storedLocation?.address || '',
  source: storedLocation?.source || '',
  city: storedLocation?.city || '',
  adcode: storedLocation?.adcode || '',
  updatedAt: storedLocation?.updatedAt || null,
  status: storedLocation ? 'ready' : 'idle',
  errorMessage: '',
});

export const hasLocation = computed(() => Boolean(locationState.point));
export const selectedLocationLabel = computed(() => locationState.label || '选择消费位置');

export function setSelectedLocation(input: SelectedLocationInput) {
  const location = normalizeStoredLocation({
    ...input,
    updatedAt: input.updatedAt || Date.now(),
  });
  if (!location) {
    locationState.status = 'error';
    locationState.errorMessage = '位置坐标无效';
    return null;
  }
  locationState.point = location.point;
  locationState.selectedLocation = location;
  locationState.label = location.label;
  locationState.address = location.address || '';
  locationState.source = location.source;
  locationState.city = location.city || '';
  locationState.adcode = location.adcode || '';
  locationState.updatedAt = location.updatedAt;
  locationState.status = 'ready';
  locationState.errorMessage = '';
  sessionStorage.setItem(SELECTED_LOCATION_KEY, JSON.stringify(location));
  sessionStorage.removeItem(LEGACY_LOCATION_KEY);
  return location;
}

export function clearSelectedLocation() {
  locationState.point = null;
  locationState.selectedLocation = null;
  locationState.label = '';
  locationState.address = '';
  locationState.source = '';
  locationState.city = '';
  locationState.adcode = '';
  locationState.updatedAt = null;
  locationState.status = 'idle';
  locationState.errorMessage = '';
  sessionStorage.removeItem(SELECTED_LOCATION_KEY);
  sessionStorage.removeItem(LEGACY_LOCATION_KEY);
}

async function readBrowserLocationPermission(): Promise<BrowserLocationPermissionState> {
  if (!navigator.permissions?.query) {
    return 'unknown';
  }
  try {
    const permission = await navigator.permissions.query({ name: 'geolocation' });
    return permission.state;
  } catch {
    return 'unknown';
  }
}

export async function requestBrowserLocationPoint() {
  if (!navigator.geolocation) {
    locationState.status = 'unsupported';
    locationState.errorMessage = '当前浏览器不支持定位';
    return null;
  }

  if (!window.isSecureContext) {
    locationState.status = 'denied';
    locationState.errorMessage = '浏览器定位需要 localhost 或 HTTPS。请用本机 Local 地址访问，或给局域网地址配置 HTTPS';
    return null;
  }

  locationState.status = 'loading';
  locationState.errorMessage = '';
  const permissionState = await readBrowserLocationPermission();
  if (permissionState === 'denied') {
    locationState.status = 'denied';
    locationState.errorMessage = '浏览器已拒绝定位权限，请在地址栏权限设置中允许定位后重试';
    return null;
  }

  return new Promise<GeoPoint | null>((resolve) => {
    navigator.geolocation.getCurrentPosition(
      async (position) => {
        const gpsPoint = {
          x: position.coords.longitude,
          y: position.coords.latitude,
        };
        try {
          const amapPoint = await convertGpsToAmapPoint(gpsPoint);
          if (!amapPoint) {
            locationState.status = 'error';
            locationState.errorMessage = '浏览器定位无法转换为高德坐标，请检查地图 Key 后重试';
            resolve(null);
            return;
          }
          locationState.status = locationState.point ? 'ready' : 'idle';
          locationState.errorMessage = '';
          resolve(amapPoint);
        } catch {
          locationState.status = 'error';
          locationState.errorMessage = '浏览器定位无法转换为高德坐标，请稍后重试';
          resolve(null);
        }
      },
      (error) => {
        locationState.status = error.code === error.PERMISSION_DENIED ? 'denied' : 'error';
        locationState.errorMessage = error.code === error.PERMISSION_DENIED ? '定位权限未开启' : '定位失败，请稍后重试';
        resolve(null);
      },
      {
        enableHighAccuracy: true,
        timeout: 8000,
        maximumAge: 5 * 60 * 1000,
      },
    );
  });
}

export async function requestCurrentLocation() {
  locationState.errorMessage = '';
  const point = await requestBrowserLocationPoint();
  if (!point) {
    return null;
  }
  setSelectedLocation({
    point,
    label: '当前位置',
    source: 'browser',
    coordinateSystem: 'gcj02',
  });
  return point;
}

export async function ensureDefaultCurrentLocation() {
  if (locationState.point || sessionStorage.getItem(AUTO_LOCATION_ATTEMPT_KEY) === '1') {
    return null;
  }
  sessionStorage.setItem(AUTO_LOCATION_ATTEMPT_KEY, '1');
  return requestCurrentLocation();
}

export function clearCurrentLocation() {
  clearSelectedLocation();
}
