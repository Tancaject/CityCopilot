declare global {
  interface Window {
    AMap?: {
      Map: new (element: HTMLElement, options: Record<string, unknown>) => unknown;
      Marker: new (options: Record<string, unknown>) => unknown;
    };
    _AMapSecurityConfig?: {
      securityJsCode?: string;
    };
  }
}

let amapPromise: Promise<typeof window.AMap | null> | null = null;

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
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${import.meta.env.VITE_AMAP_JS_KEY}`;
    script.async = true;
    script.onload = () => resolve(window.AMap || null);
    script.onerror = () => reject(new Error('地图加载失败'));
    document.head.appendChild(script);
  });

  return amapPromise;
}

export {};
