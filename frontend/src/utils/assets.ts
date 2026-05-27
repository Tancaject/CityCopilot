import type { Blog, Shop } from '../types';

export function assetUrl(path?: string) {
  if (!path) {
    return '/icons/default-icon.png';
  }
  if (/^https?:\/\//.test(path)) {
    return path;
  }
  if (path.startsWith('/imgs/')) {
    return path.replace('/imgs', '');
  }
  return path.startsWith('/') ? path : `/${path}`;
}

export function firstImage(value?: string) {
  return assetUrl(value?.split(',').find(Boolean));
}

export function blogCover(blog: Blog) {
  return firstImage(blog.images);
}

export function shopCover(shop: Pick<Shop, 'images'> | { images?: string }) {
  return firstImage(shop.images);
}

export function blogContentText(value?: string) {
  if (!value) {
    return '';
  }
  return value
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

export function formatPrice(value?: number) {
  if (!value) {
    return '人均 --';
  }
  return `人均 ¥${value}`;
}

export function formatCents(value?: number) {
  if (!value) {
    return '¥--';
  }
  return `¥${(value / 100).toFixed(2)}`;
}

export function formatScore(value?: number) {
  if (!value) {
    return '暂无评分';
  }
  return `${(value / 10).toFixed(1)} 分`;
}

export function formatDistance(value?: number) {
  if (!value) {
    return '';
  }
  return value < 1000 ? `${value.toFixed(0)} m` : `${(value / 1000).toFixed(1)} km`;
}
