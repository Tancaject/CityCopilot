// S4 预约：60% 浏览资源未来 7 天的时段，40% 走完整下单链路（报价 → 暂留 → 确认）。
// 名额已满、个人配额等 4xx 业务拒绝属于正常结果，不计为错误。
import http from 'k6/http';
import { BASE, any, auth, fixture, options as base, summary, track } from './k6_common.js';

export const options = base('booking');
const users = fixture('users');
const slots = fixture('slots');

export default function () {
  const slot = any(slots);
  const user = any(users);
  if (Math.random() < 0.6) {
    const from = new Date().toISOString();
    const to = new Date(Date.now() + 7 * 86400000).toISOString();
    track(http.get(`${BASE}/booking-resources/${slot.resourceId}/slots?dateFrom=${from}&dateTo=${to}`,
      Object.assign({ tags: { name: 'booking-slots' } }, auth(user.token))));
    return;
  }
  const party = slot.kind === 'COURT' ? 2 : 2 + Math.floor(Math.random() * 3);
  const quote = track(http.post(`${BASE}/booking-quotes`, JSON.stringify({
    resourceId: String(slot.resourceId), slotId: String(slot.slotId), partySize: party, offerId: String(slot.offerId),
  }), Object.assign({ tags: { name: 'booking-quote' }, responseType: 'text' }, auth(user.token))));
  if (quote.status >= 300) return;
  const key = `${user.userId}-${slot.slotId}-${Date.now()}-${Math.random()}`;
  const hold = track(http.post(`${BASE}/bookings/holds`, JSON.stringify({
    resourceId: String(slot.resourceId), slotId: String(slot.slotId), partySize: party,
    quoteId: JSON.parse(quote.body).id, source: 'LOAD_TEST', requestId: key,
  }), Object.assign({ tags: { name: 'booking-hold' }, responseType: 'text' }, auth(user.token, { 'Idempotency-Key': `${key}:hold` }))));
  if (hold.status >= 300) return;
  const booking = JSON.parse(hold.body).booking;
  track(http.post(`${BASE}/bookings/${booking.id}/confirm`, JSON.stringify({ expectedVersion: booking.version }),
    Object.assign({ tags: { name: 'booking-confirm' } }, auth(user.token, { 'Idempotency-Key': `${key}:confirm` }))));
}

export const handleSummary = summary('booking');
