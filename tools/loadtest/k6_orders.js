// S2 个人订单：我的订单分页 70%（多数看第一页，30% 翻到更深的页，最多 50 页），订单详情 30%。
// 用户按下单活跃度抽样，订单多的老用户才会翻深页。
import http from 'k6/http';
import { BASE, any, auth, fixture, options as base, summary, track } from './k6_common.js';

export const options = base('orders');
const users = fixture('orderUsers');

export default function () {
  const user = any(users);
  const params = auth(user.token);
  if (Math.random() < 0.7 || user.orders.length === 0) {
    const pages = Math.max(1, Math.min(50, Math.ceil(user.orderCount / 10)));
    const page = Math.random() < 0.7 ? 1 : 1 + Math.floor(Math.random() * pages);
    track(http.get(`${BASE}/voucher-order/of/me?current=${page}`, Object.assign({ tags: { name: 'my-orders' } }, params)));
  } else {
    track(http.get(`${BASE}/voucher-order/${any(user.orders)}`, Object.assign({ tags: { name: 'order-detail' } }, params)));
  }
}

export const handleSummary = summary('orders');
