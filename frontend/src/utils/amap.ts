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

export interface AmapApi {
  Map: new (element: HTMLElement, options: Record<string, unknown>) => AmapMap;
  Marker: new (options: Record<string, unknown>) => AmapMarker;
  PlaceSearch?: new (options: Record<string, unknown>) => AmapPlaceSearch;
  Geocoder?: new (options: Record<string, unknown>) => AmapGeocoder;
}

let amapPromise: Promise<AmapApi | null> | null = null;

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
    script.onerror = () => reject(new Error('地图加载失败'));
    document.head.appendChild(script);
  });

  return amapPromise;
}

export {};
