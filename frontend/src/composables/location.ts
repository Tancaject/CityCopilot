import { computed, reactive } from 'vue';
import type { GeoPoint } from '../types';

const LOCATION_KEY = 'citycopilot:user-location';

type LocationStatus = 'idle' | 'loading' | 'ready' | 'denied' | 'unsupported' | 'error';

interface LocationState {
  point: GeoPoint | null;
  status: LocationStatus;
  errorMessage: string;
}

function readStoredLocation(): GeoPoint | null {
  const raw = sessionStorage.getItem(LOCATION_KEY);
  if (!raw) {
    return null;
  }
  try {
    const point = JSON.parse(raw) as GeoPoint;
    return Number.isFinite(point.x) && Number.isFinite(point.y) ? point : null;
  } catch {
    return null;
  }
}

export const locationState = reactive<LocationState>({
  point: readStoredLocation(),
  status: readStoredLocation() ? 'ready' : 'idle',
  errorMessage: '',
});

export const hasLocation = computed(() => Boolean(locationState.point));

export async function requestCurrentLocation() {
  locationState.errorMessage = '';
  if (!navigator.geolocation) {
    locationState.status = 'unsupported';
    locationState.errorMessage = '当前浏览器不支持定位';
    return null;
  }

  locationState.status = 'loading';
  return new Promise<GeoPoint | null>((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        const point = {
          x: position.coords.longitude,
          y: position.coords.latitude,
        };
        locationState.point = point;
        locationState.status = 'ready';
        sessionStorage.setItem(LOCATION_KEY, JSON.stringify(point));
        resolve(point);
      },
      (error) => {
        locationState.point = null;
        locationState.status = error.code === error.PERMISSION_DENIED ? 'denied' : 'error';
        locationState.errorMessage = error.code === error.PERMISSION_DENIED ? '定位权限未开启' : '定位失败，请稍后重试';
        sessionStorage.removeItem(LOCATION_KEY);
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

export function clearCurrentLocation() {
  locationState.point = null;
  locationState.status = 'idle';
  locationState.errorMessage = '';
  sessionStorage.removeItem(LOCATION_KEY);
}
