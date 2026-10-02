import { ApiError, getData, postData, putData } from './client';
import { authState } from '../composables/auth';

export interface BookingRules {
  courtSlotMinutes: number; publishingDays: number; holdSeconds: number;
  maxActiveHolds: number; maxFutureBookings: number; maxDiningPartySize: number;
  cancellationMinutes: number; checkinEarlyMinutes: number; reminderMinutes: number;
  defaultTimezone: string; serverTime: string; newBookingsEnabled: boolean;
}
export interface BookingResource {
  id: string; shopId: string; kind: 'COURT' | 'DINING_POOL'; name: string; timezone: string;
  maxPartySize: number; status: 'OPEN' | 'STOP_NEW' | 'UNAVAILABLE'; version: number;
}
export interface BookingSlot {
  id: string; resourceId: string; startAt: string; endAt: string; capacity: number;
  occupied: number; available: number; status: string; version: number;
}
export interface Booking {
  id: string; userId: string; shopId: string; resourceId: string; resourceName: string;
  kind: 'COURT' | 'DINING_POOL'; slotId: string; partySize: number; units: number; status: string;
  reasonCode?: string; startAt: string; endAt: string; holdExpiresAt?: string; version: number;
  scheduleVersion: number; priceSnapshot: Record<string, unknown>; checkinToken?: string;
  allowedActions: string[]; serverTime: string;
}
export interface BookingMutation { code: string; httpStatus: number; message: string; booking: Booking | null }
export interface BookingCommand { idempotencyKey: string; action: string; bookingId: string; result: BookingMutation; createdAt: string }
export interface BookingNotice { id: string; bookingId: string; kind: string; message: string; dueAt: string; deliveredAt: string; readAt?: string }
export interface BookingTarget { resourceId: string; slotId: string; partySize: number; quoteId?: string }
export interface ResourceRequest { kind: string; name: string; timezone: string; maxPartySize: number; status: string; expectedVersion?: number }
export interface SlotRequest { slotId?: string; startAt: string; endAt: string; capacity: number; status: string; expectedVersion?: number }
export interface PendingBookingCommand { key: string; path: string; body: unknown; action: string }

export const fetchBookingRules = () => getData<BookingRules>('/booking-rules');
export const fetchBookingResources = (shopId: string, admin = false) => getData<BookingResource[]>(`${admin ? '/admin' : ''}/shops/${shopId}/booking-resources`);
export const fetchBookingSlots = (id: string, from: string, to: string) => getData<BookingSlot[]>(`/booking-resources/${id}/slots`, { params: { dateFrom: from, dateTo: to } });
export const fetchBookings = (page = 1) => getData<Booking[]>('/bookings', { params: { page } });
export const fetchBooking = (id: string, admin = false) => getData<Booking>(`${admin ? '/admin' : ''}/bookings/${id}`);
export const fetchBookingNotices = () => getData<BookingNotice[]>('/booking-notices');
export const readBookingNotice = (id: string) => postData<BookingNotice>(`/booking-notices/${id}/read`);
export const fetchBookingShops = () => getData<{ id: string; name: string }[]>('/admin/booking-shops');
export const fetchShopBookings = (id: string, from: string, to: string) => getData<Booking[]>(`/admin/shops/${id}/bookings`, { params: { from, to } });
export const createBookingResource = (shopId: string, request: ResourceRequest) => postData<BookingResource>(`/admin/shops/${shopId}/booking-resources`, request);
export const updateBookingResource = (id: string, request: ResourceRequest) => putData<BookingResource>(`/admin/booking-resources/${id}`, request);
export const saveBookingSlot = (id: string, request: SlotRequest) => postData<BookingSlot>(`/admin/booking-resources/${id}/slots`, request);

function commandStorageKey() { return `citycopilot:booking-command:${authState.user?.id || 'anonymous'}`; }
export function pendingBookingCommand(): PendingBookingCommand | null {
  const value = sessionStorage.getItem(commandStorageKey());
  return value ? JSON.parse(value) as PendingBookingCommand : null;
}
function clearPending() { sessionStorage.removeItem(commandStorageKey()); }

export async function submitBookingCommand(action: string, path: string, body: unknown): Promise<BookingMutation> {
  const pending = pendingBookingCommand();
  if (pending && (pending.path !== path || JSON.stringify(pending.body) !== JSON.stringify(body))) {
    throw new ApiError('上一操作结果仍未确定，请先查询结果或按原请求重试。', 'BOOKING_PENDING_COMMAND');
  }
  const command = pending || { key: crypto.randomUUID(), action, path, body };
  sessionStorage.setItem(commandStorageKey(), JSON.stringify(command));
  try {
    const result = await postData<BookingMutation>(command.path, command.body, { headers: { 'Idempotency-Key': command.key } });
    clearPending();
    return result;
  } catch (error) {
    // Only a received terminal HTTP rejection resolves an operation; transport failures retain the original key and request.
    if (error instanceof ApiError && error.status && error.status < 500 && ![408, 429].includes(error.status)) clearPending();
    throw error;
  }
}
export async function recoverBookingCommand(): Promise<BookingMutation> {
  const pending = pendingBookingCommand();
  if (!pending) throw new ApiError('没有待查询的预约操作');
  const command = await getData<BookingCommand>(`/booking-commands/${encodeURIComponent(pending.key)}`);
  clearPending();
  return command.result;
}
export async function retryBookingCommand(): Promise<BookingMutation> {
  const pending = pendingBookingCommand();
  if (!pending) throw new ApiError('没有待重试的预约操作');
  return submitBookingCommand(pending.action, pending.path, pending.body);
}
