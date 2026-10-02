import { getData, postData } from './client';
import type { BookingTarget } from './booking';
export interface BookingOffer {
  id: string; shopId: string; resourceId: string; kind: 'COURT_SLOT' | 'PER_PERSON_PACKAGE';
  title: string; includedItems: string; excludedItems: string; unitPriceCents: number; version: number; status: string;
}
export interface CouponRule {
  id: string; campaignKey: string; ruleVersion: number; shopId: string; offerId: string | null;
  type: 'DISCOUNT' | 'FULL_REDUCTION' | 'CASH'; thresholdCents: number; reductionCents: number;
  payFactorBps: number; discountCapCents: number | null; stackable: boolean;
  serviceFrom: string; serviceTo: string; claimUntil: string; couponExpiresAt: string; issuanceStatus: string;
}
export interface BookingCoupon { id: string; userId: string; status: string; expiresAt: string; bookingId?: string; revokedAt?: string; rule: CouponRule }
export interface BookingDiscount {
  couponId: string; ruleId: string; ruleVersion: number; type: string; thresholdCents: number;
  payFactorBps: number; discountCapCents: number | null; eligibleBaseCents: number; beforeCents: number;
  discountCents: number; afterCents: number; couponExpiresAt: string;
}
export interface BookingQuote {
  quoteId: string; userId: string; purpose: 'CREATE' | 'RESCHEDULE'; bookingId: string | null; expectedBookingVersion: number | null;
  resourceId: string; slotId: string; partySize: number; units: number; pricingMode: 'FIXED' | 'ON_SITE';
  offerId: string | null; offerVersion: number | null; offerTitle: string | null; includedItems: string | null;
  excludedItems: string | null; unitPriceCents: number | null; currency: string; baseCents: number | null;
  discountLines: BookingDiscount[]; totalCents: number | null; expiresAt: string;
  source: string; impressionId: string | null; requestId: string | null;
}
export interface QuoteRequest extends BookingTarget {
  offerId?: string; couponMode: 'AUTO' | 'NONE' | 'MANUAL'; couponIds: string[];
  purpose: 'CREATE' | 'RESCHEDULE'; bookingId?: string; expectedBookingVersion?: number; source: string;
  impressionId?: string; requestId?: string;
}
export const fetchBookingOffers = (id: string, admin = false) => getData<BookingOffer[]>(`${admin ? '/admin' : ''}/booking-resources/${id}/offers`);
export const fetchCouponRules = (id: string, admin = false) => getData<CouponRule[]>(`${admin ? '/admin' : ''}/shops/${id}/booking-coupon-rules`);
export const fetchBookingCoupons = (shopId?: string) => getData<BookingCoupon[]>('/booking-coupons', { params: { shopId } });
export const claimBookingCoupon = (id: string) => postData<BookingCoupon>('/booking-coupons/claims', { ruleId: id }, { headers: { 'Idempotency-Key': `booking-coupon-claim-${id}` } });
export const quoteBooking = (request: QuoteRequest) => postData<BookingQuote>('/booking-quotes', request);
export const fetchBookingQuote = (id: string) => getData<BookingQuote>(`/booking-quotes/${id}`);
export const saveBookingOffer = (id: string, request: { offerId?: string; kind: string; title: string; includedItems: string; excludedItems: string; unitPriceCents: number; status: string; expectedVersion?: number }) => postData<BookingOffer>(`/admin/booking-resources/${id}/offers`, request);
export const publishCouponRule = (id: string, request: Omit<CouponRule, 'id' | 'ruleVersion' | 'shopId' | 'issuanceStatus'>) => postData<CouponRule>(`/admin/shops/${id}/booking-coupon-rules`, request);
export const setCouponIssuance = (id: string, status: string) => postData<CouponRule>(`/admin/booking-coupon-rules/${id}/issuance`, { status });
export const revokeBookingCoupon = (id: string, reason: string) => postData<BookingCoupon>(`/admin/booking-coupons/${id}/revoke`, { reason });
export const couponType: Record<string, string> = { DISCOUNT: '折扣', FULL_REDUCTION: '满减', CASH: '代金' };
export const couponStatus: Record<string, string> = { AVAILABLE: '可用', RESERVED: '已用于预约', CONSUMED: '已消费', EXPIRED: '已过期', REVOKED: '已撤销' };
export const priceCents = (value: number) => `¥${(value / 100).toFixed(2)}`;
export function couponLabel(rule: CouponRule): string {
  return rule.type === 'DISCOUNT' ? `${(rule.payFactorBps / 1000).toFixed(2).replace(/\.?0+$/, '')} 折${rule.discountCapCents == null ? '' : `，最高减 ${priceCents(rule.discountCapCents)}`}`
    : rule.type === 'FULL_REDUCTION' ? `满 ${priceCents(rule.thresholdCents)} 减 ${priceCents(rule.reductionCents)}` : `${priceCents(rule.reductionCents)} 代金券`;
}
