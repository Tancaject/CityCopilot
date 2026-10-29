// S5 后台运营：商家查本店前后 7 天预约 50%、本店预约资源 20%；管理员看审计日志 20%、运营汇总 10%。
import http from 'k6/http';
import { BASE, any, auth, fixture, options as base, summary, track } from './k6_common.js';

export const options = base('admin');
const merchants = fixture('merchants');
const admin = JSON.parse(open('/data/fixtures.json')).admin;

export default function () {
  const r = Math.random();
  if (r < 0.7) {
    const merchant = any(merchants);
    const params = auth(merchant.token);
    if (r < 0.5) {
      const from = new Date(Date.now() - 7 * 86400000).toISOString();
      const to = new Date(Date.now() + 7 * 86400000).toISOString();
      track(http.get(`${BASE}/admin/shops/${merchant.shopId}/bookings?from=${from}&to=${to}`,
        Object.assign({ tags: { name: 'shop-bookings' } }, params)));
    } else {
      track(http.get(`${BASE}/admin/shops/${merchant.shopId}/booking-resources`,
        Object.assign({ tags: { name: 'shop-resources' } }, params)));
    }
  } else if (r < 0.9) {
    track(http.get(`${BASE}/admin/audit-logs?current=1`, Object.assign({ tags: { name: 'audit-logs' } }, auth(admin.token))));
  } else {
    track(http.get(`${BASE}/admin/ops/summary`, Object.assign({ tags: { name: 'ops-summary' } }, auth(admin.token))));
  }
}

export const handleSummary = summary('admin');
