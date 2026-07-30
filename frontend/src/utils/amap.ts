import type { GeoPoint } from '../types';

declare global {
  interface Window {
    AMap?: AmapApi;
    _AMapSecurityConfig?: {
      securityJsCode?: string;
    };
  }
}

export interface AmapLngLat {
  getLng?: () => number;
  getLat?: () => number;
  lng?: number;
  lat?: number;
}

export interface AmapMapEvent {
  lnglat?: AmapLngLat;
}

export interface AmapMap {
  on: (eventName: 'click', handler: (event: AmapMapEvent) => void) => void;
  setCenter: (position: [number, number] | AmapLngLat) => void;
  setZoomAndCenter?: (zoom: number, position: [number, number] | AmapLngLat) => void;
  destroy: () => void;
}

export interface AmapMarker {
  on: (eventName: 'dragend', handler: (event: AmapMapEvent) => void) => void;
  setMap?: (map: AmapMap | null) => void;
  setPosition: (position: [number, number] | AmapLngLat) => void;
  getPosition: () => AmapLngLat;
}

export interface AmapPoiSearchItem {
  id?: string;
  name?: string;
  address?: string;
  pname?: string;
  cityname?: string;
  adname?: string;
  adcode?: string;
  location?: AmapLngLat;
}

export interface AmapPlaceSearchResult {
  poiList?: {
    pois?: AmapPoiSearchItem[];
  };
}

export interface AmapPlaceSearch {
  search: (
    keyword: string,
    callback: (status: string, result: AmapPlaceSearchResult | string) => void,
  ) => void;
}

export interface AmapReGeocodeResult {
  regeocode?: {
    formattedAddress?: string;
    addressComponent?: {
      city?: string | string[];
      district?: string;
      adcode?: string;
    };
  };
}

export interface AmapGeocoder {
  getAddress: (
    location: [number, number] | AmapLngLat,
    callback: (status: string, result: AmapReGeocodeResult | string) => void,
  ) => void;
}

export interface AmapCoordinateConvertResult {
  info?: string;
  locations?: AmapLngLat[];
}

export interface AmapApi {
  Map: new (element: HTMLElement, options: Record<string, unknown>) => AmapMap;
  Marker: new (options: Record<string, unknown>) => AmapMarker;
  PlaceSearch?: new (options: Record<string, unknown>) => AmapPlaceSearch;
  Geocoder?: new (options: Record<string, unknown>) => AmapGeocoder;
  convertFrom?: (
    location: [number, number],
    source: 'gps' | 'baidu' | 'mapbar',
    callback: (status: string, result: AmapCoordinateConvertResult | string) => void,
  ) => void;
}

let amapPromise: Promise<AmapApi | null> | null = null;

const GCJ02_AXIS = 6378245;
const GCJ02_ECCENTRICITY = 0.006693421622965943;

export function hasAmapKey() {
  return Boolean(import.meta.env.VITE_AMAP_JS_KEY);
}

export function loadAmap() {
  if (!hasAmapKey()) {
    return Promise.resolve(null);
  }
  if (window.AMap) {
    return Promise.resolve(window.AMap);
  }
  if (amapPromise) {
    return amapPromise;
  }

  const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE;
  if (securityCode) {
    window._AMapSecurityConfig = { securityJsCode: securityCode };
  }

  amapPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${import.meta.env.VITE_AMAP_JS_KEY}&plugin=AMap.PlaceSearch,AMap.Geocoder`;
    script.async = true;
    script.onload = () => resolve(window.AMap || null);
    script.onerror = () => {
      amapPromise = null;
      reject(new Error('地图加载失败'));
    };
    document.head.appendChild(script);
  });

  return amapPromise;
}

export async function convertGpsToAmapPoint(point: GeoPoint): Promise<GeoPoint | null> {
  const fallbackPoint = convertWgs84ToGcj02(point);
  let amap: AmapApi | null;
  try {
    amap = await loadAmap();
  } catch {
    return fallbackPoint;
  }
  if (!amap?.convertFrom) {
    return fallbackPoint;
  }
  return new Promise((resolve) => {
    amap.convertFrom?.([point.x, point.y], 'gps', (status, result) => {
      if (status !== 'complete' || typeof result === 'string' || result.info !== 'ok') {
        resolve(fallbackPoint);
        return;
      }
      const location = result.locations?.[0];
      const x = location?.getLng?.() ?? location?.lng;
      const y = location?.getLat?.() ?? location?.lat;
      if (!Number.isFinite(x) || !Number.isFinite(y)) {
        resolve(fallbackPoint);
        return;
      }
      resolve({ x: Number(x), y: Number(y) });
    });
  });
}

/**
 * Deterministic fallback for browser WGS84/GPS coordinates when the AMap JS
 * SDK is not configured or temporarily unavailable.
 */
export function convertWgs84ToGcj02(point: GeoPoint): GeoPoint {
  if (isOutsideMainlandChina(point.x, point.y)) {
    return { ...point };
  }

  const latitudeOffset = transformLatitude(point.x - 105, point.y - 35);
  const longitudeOffset = transformLongitude(point.x - 105, point.y - 35);
  const latitudeRadians = point.y / 180 * Math.PI;
  const sinLatitude = Math.sin(latitudeRadians);
  const magic = 1 - GCJ02_ECCENTRICITY * sinLatitude * sinLatitude;
  const sqrtMagic = Math.sqrt(magic);
  const latitudeDelta = latitudeOffset * 180
    / ((GCJ02_AXIS * (1 - GCJ02_ECCENTRICITY)) / (magic * sqrtMagic) * Math.PI);
  const longitudeDelta = longitudeOffset * 180
    / (GCJ02_AXIS / sqrtMagic * Math.cos(latitudeRadians) * Math.PI);

  return {
    x: point.x + longitudeDelta,
    y: point.y + latitudeDelta,
  };
}

function isOutsideMainlandChina(longitude: number, latitude: number) {
  return longitude < 72.004
    || longitude > 137.8347
    || latitude < 0.8293
    || latitude > 55.8271;
}

function transformLatitude(longitude: number, latitude: number) {
  let result = -100 + 2 * longitude + 3 * latitude
    + 0.2 * latitude * latitude
    + 0.1 * longitude * latitude
    + 0.2 * Math.sqrt(Math.abs(longitude));
  result += (20 * Math.sin(6 * longitude * Math.PI)
    + 20 * Math.sin(2 * longitude * Math.PI)) * 2 / 3;
  result += (20 * Math.sin(latitude * Math.PI)
    + 40 * Math.sin(latitude / 3 * Math.PI)) * 2 / 3;
  result += (160 * Math.sin(latitude / 12 * Math.PI)
    + 320 * Math.sin(latitude * Math.PI / 30)) * 2 / 3;
  return result;
}

function transformLongitude(longitude: number, latitude: number) {
  let result = 300 + longitude + 2 * latitude
    + 0.1 * longitude * longitude
    + 0.1 * longitude * latitude
    + 0.1 * Math.sqrt(Math.abs(longitude));
  result += (20 * Math.sin(6 * longitude * Math.PI)
    + 20 * Math.sin(2 * longitude * Math.PI)) * 2 / 3;
  result += (20 * Math.sin(longitude * Math.PI)
    + 40 * Math.sin(longitude / 3 * Math.PI)) * 2 / 3;
  result += (150 * Math.sin(longitude / 12 * Math.PI)
    + 300 * Math.sin(longitude / 30 * Math.PI)) * 2 / 3;
  return result;
}

export {};
