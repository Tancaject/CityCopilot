<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { getData } from '../api/client';
import { authState } from '../composables/auth';
import type { AgentToolEvent } from '../types';
import { proposeBooking, type BookingConfirmation, type BookingPlan } from '../api/booking-confirmation';
import { quoteBooking, type BookingQuote } from '../api/booking-pricing';
import type { Booking, BookingSlot } from '../api/booking';
import { bookingStatus, bookingTime } from '../utils/booking';
import { getErrorMessage } from '../utils/errors';
import BookingConfirmationCard from './BookingConfirmationCard.vue';
import BookingPriceBreakdown from './BookingPriceBreakdown.vue';
import BookingFeedbackButtons from './BookingFeedbackButtons.vue';

const props = defineProps<{ events: AgentToolEvent[] }>();
interface ResultCard { key: string; tool: string; confirmation?: BookingConfirmation; plan?: BookingPlan; quote?: BookingQuote; bookings?: Booking[]; slots?: BookingSlot[] }
const proposalStorage = `citycopilot:booking-proposals:${authState.user?.id || 'anonymous'}`;
const selected = reactive<Record<string, BookingConfirmation>>(JSON.parse(sessionStorage.getItem(proposalStorage) || '{}'));
watch(selected, () => sessionStorage.setItem(proposalStorage, JSON.stringify(Object.fromEntries(Object.entries(selected).slice(-40)))), { deep: true });
const feedbackEnabled = ref(false);
onMounted(async () => { if (authState.user) { try { feedbackEnabled.value = (await getData<{ enabled: boolean }>('/booking-feedback/rules')).enabled; } catch { feedbackEnabled.value = false; } } });
const refreshedQuotes = reactive<Record<string, BookingQuote>>({});
const errors = reactive<Record<string, string>>({});
const busy = reactive<Record<string, boolean>>({});
const cards = computed(() => props.events.flatMap((event, index): ResultCard[] => {
  if (event.phase !== 'result' || !event.observation || typeof event.observation !== 'object') return [];
  const observation = event.observation as { ok?: boolean; data?: unknown };
  if (!observation.ok || !observation.data) return [];
  const data = observation.data;
  const result: ResultCard = { key: `${index}:${event.toolName}`, tool: event.toolName || '' };
  if (typeof data === 'object' && 'type' in data && data.type === 'BOOKING_CONFIRMATION') result.confirmation = data as BookingConfirmation;
  else if (event.toolName === 'search_bookable_options') result.plan = data as BookingPlan;
  else if (event.toolName === 'quote_booking') result.quote = data as BookingQuote;
  else if (event.toolName === 'get_my_bookings' && Array.isArray(data)) result.bookings = data as Booking[];
  else if (event.toolName === 'list_booking_slots' && Array.isArray(data)) result.slots = data as BookingSlot[];
  else return [];
  return [result];
}));
async function choose(original: BookingQuote) {
  const key = original.quoteId; busy[key] = true; errors[key] = '';
  try {
    const quote = refreshedQuotes[key] || original;
    selected[key] = await proposeBooking({ action: quote.purpose === 'RESCHEDULE' ? 'RESCHEDULE' : 'HOLD', quoteId: quote.quoteId,
      bookingId: quote.bookingId || undefined, expectedVersion: quote.expectedBookingVersion ?? undefined });
  } catch (e) { errors[key] = getErrorMessage(e, '生成确认卡失败，请重新报价或在商家页面选择时段。'); }
  finally { busy[key] = false; }
}
async function reprice(original: BookingQuote) {
  const key = original.quoteId; busy[key] = true; errors[key] = '';
  try {
    refreshedQuotes[key] = await quoteBooking({ resourceId: original.resourceId, slotId: original.slotId, partySize: original.partySize,
      offerId: original.offerId || undefined, couponMode: 'AUTO', couponIds: [], purpose: original.purpose,
      bookingId: original.bookingId || undefined, expectedBookingVersion: original.expectedBookingVersion ?? undefined,
      source: original.source, impressionId: original.impressionId || undefined, requestId: original.requestId || undefined });
    delete selected[key];
  } catch (e) { errors[key] = getErrorMessage(e, '该方案暂时无法重新报价，请重新查询可约时段。'); }
  finally { busy[key] = false; }
}
</script>
<template>
  <div class="agent-booking-cards">
    <template v-for="card in cards" :key="card.key">
      <BookingConfirmationCard v-if="card.confirmation" :operation-id="card.confirmation.operationId" :initial="card.confirmation" />
      <section v-if="card.plan" class="booking-card">
        <p>{{ card.plan.message }}</p>
        <p v-for="question in card.plan.clarifications" :key="question.field">{{ question.question }}</p>
        <article v-for="option in card.plan.options" :key="option.quote.quoteId" class="booking-card">
          <strong>{{ option.shopName }} · {{ option.resource.name }}</strong>
          <p>{{ bookingTime(option.slot.startAt, option.resource.timezone) }} · {{ option.quote.partySize }} 人</p>
          <small>{{ option.resource.timezone }} · {{ option.address }}<span v-if="option.distanceMeters != null"> · {{ Math.round(option.distanceMeters) }} 米</span></small>
          <p>{{ option.reasons.join('；') }}</p>
          <BookingFeedbackButtons v-if="feedbackEnabled && option.quote.impressionId" :shop-id="option.shopId" :impression-id="option.quote.impressionId" />
          <BookingPriceBreakdown :quote="refreshedQuotes[option.quote.quoteId] || option.quote" />
          <p>报价展示截至 {{ bookingTime((refreshedQuotes[option.quote.quoteId] || option.quote).expiresAt, option.resource.timezone) }}；尚未占用名额。</p>
          <BookingConfirmationCard v-if="selected[option.quote.quoteId]" :operation-id="selected[option.quote.quoteId]!.operationId" :initial="selected[option.quote.quoteId]" />
          <div v-else class="booking-actions">
            <button class="btn btn-primary" :disabled="busy[option.quote.quoteId]" @click="choose(option.quote)">查看暂留确认</button>
            <button class="btn btn-ghost" :disabled="busy[option.quote.quoteId]" @click="reprice(option.quote)">重新计算报价</button>
          </div>
          <p v-if="errors[option.quote.quoteId]" class="booking-error">{{ errors[option.quote.quoteId] }}</p>
        </article>
      </section>
      <section v-if="card.quote" class="booking-card">
        <BookingPriceBreakdown :quote="refreshedQuotes[card.quote.quoteId] || card.quote" />
        <BookingConfirmationCard v-if="selected[card.quote.quoteId]" :operation-id="selected[card.quote.quoteId]!.operationId" :initial="selected[card.quote.quoteId]" />
        <div v-else class="booking-actions"><button class="btn btn-primary" :disabled="busy[card.quote.quoteId]" @click="choose(card.quote)">查看操作确认</button><button class="btn btn-ghost" :disabled="busy[card.quote.quoteId]" @click="reprice(card.quote)">重新计算报价</button></div>
        <p v-if="errors[card.quote.quoteId]" class="booking-error">{{ errors[card.quote.quoteId] }}</p>
      </section>
      <section v-if="card.bookings" class="booking-card">
        <p v-if="!card.bookings.length">当前没有预约。</p>
        <RouterLink v-for="booking in card.bookings" :key="booking.id" :to="`/bookings/${booking.id}`">{{ booking.resourceName }} · {{ bookingStatus[booking.status] || booking.status }} · {{ bookingTime(booking.startAt) }}</RouterLink>
      </section>
      <section v-if="card.slots" class="booking-card"><p v-if="!card.slots.length">查询时段内没有已发布排期。</p><p v-for="slot in card.slots" :key="slot.id">{{ bookingTime(slot.startAt) }} — {{ bookingTime(slot.endAt) }} · 剩余 {{ slot.available }}</p><small>时间按当前设备时区显示，确认卡使用商家资源时区。</small></section>
    </template>
  </div>
</template>
<style scoped>
.agent-booking-cards { display: grid; gap: .8rem; margin-top: .75rem; min-width: 0; }
.booking-card { display: grid; gap: .6rem; min-width: 0; }
</style>
