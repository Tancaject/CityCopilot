<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { ApiError } from '../api/client';
import { approveBookingConfirmation, bookingActionLabel, declineBookingConfirmation, fetchBookingConfirmation, queryBookingOperation, type BookingConfirmation } from '../api/booking-confirmation';
import type { BookingMutation } from '../api/booking';
import type { BookingQuote } from '../api/booking-pricing';
import { bookingStatus, bookingTime } from '../utils/booking';
import { getErrorMessage } from '../utils/errors';
import BookingPriceBreakdown from './BookingPriceBreakdown.vue';

const props = defineProps<{ operationId: string; initial?: BookingConfirmation }>();
const card = ref<BookingConfirmation | null>(props.initial || null);
const recovered = ref<BookingMutation | null>(null);
const busy = ref(false);
const error = ref('');
const uncertain = ref(false);
const time = ref(Date.now());
let loadedAt = Date.now();
let expiredRefresh = '';
let timer: ReturnType<typeof setInterval>;
const serverNow = computed(() => card.value ? Date.parse(card.value.serverTime) + (time.value - loadedAt) : time.value);
const seconds = computed(() => card.value ? Math.max(0, Math.ceil((Date.parse(card.value.expiresAt) - serverNow.value) / 1000)) : 0);
const price = computed(() => card.value?.quote || card.value?.previous?.priceSnapshot as unknown as BookingQuote | undefined);
const result = computed(() => recovered.value || card.value?.result);
const mayApprove = computed(() => card.value?.state === 'APPROVED' || (card.value?.state === 'PENDING' && seconds.value > 0) || uncertain.value);

function replace(value: BookingConfirmation) { card.value = value; loadedAt = Date.now(); time.value = loadedAt; uncertain.value = false; }
async function refresh() {
  busy.value = true; error.value = '';
  try { replace(await fetchBookingConfirmation(props.operationId)); }
  catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // The immutable Commerce command remains owner-scoped even if the short-lived review record was lost.
      try { recovered.value = (await queryBookingOperation(props.operationId)).result; uncertain.value = false; }
      catch (queryError) { error.value = getErrorMessage(queryError, '未查到原操作结果，请保留此操作编号并到我的预约核对。'); }
    } else error.value = getErrorMessage(e, '确认状态暂时无法查询，请保留原操作编号后重试。');
  } finally { busy.value = false; }
}
async function decide(approve: boolean) {
  busy.value = true; error.value = '';
  try { replace(await (approve ? approveBookingConfirmation(props.operationId, card.value!.requestHash) : declineBookingConfirmation(props.operationId))); }
  catch (e) {
    const details = e instanceof ApiError ? e.details : null;
    if (details && typeof details === 'object' && 'type' in details && details.type === 'BOOKING_CONFIRMATION') replace(details as BookingConfirmation);
    else {
      uncertain.value = approve;
      error.value = getErrorMessage(e, '连接中断，批准结果尚未确定。请查询状态，重试时仍使用此确认卡。');
      await refresh();
    }
  } finally { busy.value = false; }
}
watch(() => props.operationId, () => { recovered.value = null; card.value = props.initial || null; expiredRefresh = ''; void refresh(); });
onMounted(() => {
  void refresh();
  timer = setInterval(() => {
    time.value = Date.now();
    if (card.value?.state === 'PENDING' && seconds.value === 0 && !busy.value && expiredRefresh !== props.operationId) {
      expiredRefresh = props.operationId; void refresh();
    }
  }, 1000);
});
onUnmounted(() => clearInterval(timer));
</script>

<template>
  <section class="booking-card booking-confirmation-card" aria-label="预约操作确认">
    <template v-if="card">
      <strong>{{ bookingActionLabel[card.action] }} · {{ card.resourceName }}</strong>
      <p>{{ bookingTime(card.startAt, card.timezone) }} — {{ bookingTime(card.endAt, card.timezone) }} · {{ card.partySize }} 人</p>
      <small>时区：{{ card.timezone }}</small>
      <div v-if="card.action === 'RESCHEDULE' && card.previous" class="booking-summary">
        <strong>原预约</strong><p>{{ card.previous.resourceName }} · {{ bookingTime(card.previous.startAt, card.timezone) }} · {{ card.previous.partySize }} 人</p>
        <BookingPriceBreakdown :quote="card.previous.priceSnapshot as unknown as BookingQuote" />
        <strong>改期后的方案</strong>
      </div>
      <BookingPriceBreakdown v-if="price" :quote="price" />
      <p>{{ card.message }}</p>
      <p v-if="card.state === 'PENDING'">本次确认有效期还剩 {{ seconds }} 秒。{{ card.action === 'HOLD' ? '批准后才暂留名额，之后需要单独最终确认。' : '' }}</p>
      <div v-if="!result" class="booking-actions">
        <button v-if="card.state === 'PENDING' && !uncertain" class="btn btn-ghost" :disabled="busy" @click="decide(false)">不执行</button>
        <button v-if="mayApprove" class="btn btn-primary" :disabled="busy || (card.state === 'PENDING' && seconds === 0 && !uncertain)" @click="decide(true)">
          {{ card.state === 'APPROVED' || uncertain ? '重试原操作' : `批准${bookingActionLabel[card.action]}` }}
        </button>
      </div>
    </template>
    <div v-if="result" class="booking-summary" role="status">
      <strong>{{ result.httpStatus < 400 ? '操作已完成' : '操作未成功' }}</strong><p>{{ result.message }}</p>
      <p v-if="result.booking">操作完成时的预约状态：{{ bookingStatus[result.booking.status] || result.booking.status }}</p>
      <RouterLink v-if="result.booking" :to="`/bookings/${result.booking.id}`" class="btn btn-primary">
        查看预约当前状态
      </RouterLink>
    </div>
    <p v-if="error" class="booking-error" role="alert">{{ error }}</p>
    <button class="btn btn-ghost" :disabled="busy" @click="refresh">{{ busy ? '查询 / 提交中…' : '查询原操作结果' }}</button>
    <details><summary>操作编号</summary><code class="booking-operation-id">{{ operationId }}</code></details>
  </section>
</template>
<style scoped>
.booking-confirmation-card { display: grid; gap: .65rem; min-width: 0; }
.booking-operation-id { display: block; overflow-wrap: anywhere; font-size: .75rem; }
</style>
