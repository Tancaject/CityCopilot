// S1 店铺浏览：店铺详情 60%（按订单量热度抽取）、附近店铺 20%、店铺券列表 15%、热门笔记 5%。
import http from 'k6/http';
import { BASE, auth, any, fixture, options as base, summary, track, weightedPicker } from './k6_common.js';

export const options = base('browse');
const users = fixture('users');
const shops = fixture('shops');
const pickShop = weightedPicker(shops);

export default function () {
  const params = auth(any(users).token);
  const shop = pickShop();
  const r = Math.random();
  if (r < 0.6) {
    track(http.get(`${BASE}/shop/${shop.id}`, Object.assign({ tags: { name: 'shop-detail' } }, params)));
  } else if (r < 0.8) {
    const x = (shop.x + (Math.random() - 0.5) * 0.02).toFixed(6);
    const y = (shop.y + (Math.random() - 0.5) * 0.02).toFixed(6);
    track(http.get(`${BASE}/shop/discovery?x=${x}&y=${y}&current=1`, Object.assign({ tags: { name: 'shop-nearby' } }, params)));
  } else if (r < 0.95) {
    track(http.get(`${BASE}/voucher/list/${shop.id}`, Object.assign({ tags: { name: 'voucher-list' } }, params)));
  } else {
    track(http.get(`${BASE}/blog/hot?current=1`, Object.assign({ tags: { name: 'note-hot' } }, params)));
  }
}

export const handleSummary = summary('browse');
