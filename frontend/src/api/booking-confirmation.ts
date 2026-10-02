import { getData, postData } from './client';
import type { Booking, BookingCommand, BookingMutation, BookingResource, BookingSlot } from './booking';
import type { BookingQuote } from './booking-pricing';

export interface BookingConfirmation {
  type: 'BOOKING_CONFIRMATION'; operationId: string; action: 'HOLD' | 'CONFIRM' | 'RESCHEDULE' | 'CANCEL';
  state: 'PENDING' | 'APPROVED' | 'SUCCEEDED' | 'FAILED' | 'DECLINED' | 'EXPIRED';
  requestHash: string; expiresAt: string; serverTime: string; bookingId?: string;
  resourceName: string; timezone: string; startAt: string; endAt: string; partySize: number;
  previous?: Booking; quote?: BookingQuote; result?: BookingMutation; message: string; confirmationPath: string;
}
export interface BookingPlanOption {
  shopId: string; shopName: string; address: string; distanceMeters?: number;
  resource: BookingResource; slot: BookingSlot; quote: BookingQuote; reasons: string[];
}
export interface BookingPlan {
  status: string; requestId: string; serverTime: string; options: BookingPlanOption[];
  clarifications: { field: string; question: string }[]; message: string;
}
export interface BookingProposal {
  action: BookingConfirmation['action']; quoteId?: string; bookingId?: string; expectedVersion?: number; reason?: string;
}
export const proposeBooking = (request: BookingProposal) => postData<BookingConfirmation>('/booking-confirmations', request);
export const fetchBookingConfirmation = (id: string) => getData<BookingConfirmation>(`/booking-confirmations/${encodeURIComponent(id)}`);
export const approveBookingConfirmation = (id: string, requestHash: string) => postData<BookingConfirmation>(`/booking-confirmations/${encodeURIComponent(id)}/approve`, { requestHash });
export const declineBookingConfirmation = (id: string) => postData<BookingConfirmation>(`/booking-confirmations/${encodeURIComponent(id)}/decline`);
export const queryBookingOperation = (id: string) => getData<BookingCommand>(`/booking-commands/${encodeURIComponent(id)}`);
export const bookingActionLabel = { HOLD: '暂留名额', CONFIRM: '最终确认预约', RESCHEDULE: '更改预约', CANCEL: '取消预约' };
