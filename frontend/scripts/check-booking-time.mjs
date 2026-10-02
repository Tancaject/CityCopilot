import assert from 'node:assert/strict';
import { bookingInstant, bookingDayRange, bookingMoneyCents } from '../src/utils/booking.ts';

assert.equal(bookingInstant('2026-10-01T19:00', 'Asia/Shanghai'), '2026-10-01T11:00:00.000Z');
assert.equal(bookingInstant('2026-10-01T19:00', 'America/New_York'), '2026-10-01T23:00:00.000Z');
assert.throws(() => bookingInstant('2026-03-08T02:30', 'America/New_York'));
const [from, to] = bookingDayRange('2026-03-08', 'America/New_York');
assert.equal(Date.parse(to) - Date.parse(from), 23 * 60 * 60 * 1000);
assert.equal(bookingMoneyCents('19.99'), 1999);
assert.equal(bookingMoneyCents('0.01'), 1);
assert.throws(() => bookingMoneyCents('0.001'));
console.log('Booking local-time and DST checks passed.');
