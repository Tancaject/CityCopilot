<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { ApiError } from '../api/client';
import { fetchBooking, fetchBookings, fetchBookingNotices, fetchBookingRules, readBookingNotice, submitBookingCommand,
  type Booking, type BookingMutation, type BookingNotice, type BookingRules } from '../api/booking';
import BookingChooser from '../components/BookingChooser.vue';
import BookingCommandRecovery from '../components/BookingCommandRecovery.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import BookingPriceBreakdown from '../components/BookingPriceBreakdown.vue';
import type { BookingQuote } from '../api/booking-pricing';
import { bookingPrice, bookingStatus, bookingTime } from '../utils/booking';
import { getErrorMessage } from '../utils/errors';

const route = useRoute();
const bookings = ref<Booking[]>([]);
const rules = ref<BookingRules>();
const notices = ref<BookingNotice[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const message = ref('');
const page = ref(1);
const hasMore = ref(false);
const pendingAction = ref<{ action: 'confirm' | 'cancel'; booking: Booking }>();
const reschedulingId = ref('');
const commandRevision = ref(0);
const now = ref(Date.now());
const serverOffset = ref(0);
const expiredRefreshed = new Set<string>();
const displayTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
const focusedId = computed(() => typeof route.params.id === 'string' ? route.params.id : '');
const actionTitle = computed(() => pendingAction.value?.action === 'confirm' ? '确认到店预约' : '确认取消预约');
const actionMessage = computed(() => {
  const item = pendingAction.value?.booking;
  if (!item) return '';
  return `${item.resourceName} · ${bookingTime(item.startAt)} — ${bookingTime(item.endAt)} · ${item.partySize} 人。${bookingPrice(item.priceSnapshot)}。`;
});
const remaining = (booking: Booking) => booking.holdExpiresAt ? Math.max(0, Math.ceil((Date.parse(booking.holdExpiresAt) - now.value - serverOffset.value) / 1000)) : 0;
const can = (booking: Booking, action: string) => booking.allowedActions.includes(action) && !(booking.status === 'HELD' && remaining(booking) <= 0 && action === 'CONFIRM');
const frozenQuote = (booking: Booking) => booking.priceSnapshot as unknown as BookingQuote;

function update(booking: Booking) {
  const index = bookings.value.findIndex(item => item.id === booking.id);
  if (index >= 0) bookings.value[index] = booking;
  else bookings.value.unshift(booking);
  serverOffset.value = Date.parse(booking.serverTime) - Date.now();
}
function resolved(result: BookingMutation) {
  if (result.booking) update(result.booking);
  if (result.httpStatus >= 400) error.value = result.message;
  else message.value = result.message || '预约已更新';
  pendingAction.value = undefined;
  reschedulingId.value = '';
  commandRevision.value++;
}
async function load(reset = true) {
  loading.value = true;
  error.value = '';
  try {
    if (reset) page.value = 1;
    const [currentRules, list, currentNotices] = await Promise.all([
      fetchBookingRules(), focusedId.value ? fetchBooking(focusedId.value).then(item => [item]) : fetchBookings(page.value), fetchBookingNotices(),
    ]);
    rules.value = currentRules;
    serverOffset.value = Date.parse(currentRules.serverTime) - Date.now();
    bookings.value = reset ? list : [...bookings.value, ...list];
    notices.value = currentNotices;
    hasMore.value = !focusedId.value && list.length > 0;
  } catch (failure) { error.value = getErrorMessage(failure, '预约加载失败'); }
  finally { loading.value = false; }
}
async function act() {
  const pending = pendingAction.value;
  if (!pending) return;
  busy.value = true;
  error.value = '';
  message.value = '';
  try {
    resolved(await submitBookingCommand(pending.action === 'confirm' ? '确认预约' : '取消预约',
      `/bookings/${pending.booking.id}/${pending.action}`, { expectedVersion: pending.booking.version }));
  } catch (failure) {
    error.value = getErrorMessage(failure, '预约操作未完成');
    if (failure instanceof ApiError && failure.details && typeof failure.details === 'object' && 'booking' in failure.details) {
      const current = (failure.details as BookingMutation).booking;
      if (current) update(current);
    }
    pendingAction.value = undefined;
  } finally { busy.value = false; commandRevision.value++; }
}
async function markRead(notice: BookingNotice) {
  try {
    const updated = await readBookingNotice(notice.id);
    notices.value = notices.value.map(item => item.id === updated.id ? updated : item);
  } catch (failure) { error.value = getErrorMessage(failure, '消息已读状态未保存'); }
}
async function loadMore() { page.value++; await load(false); }
let timer: ReturnType<typeof setInterval>;
onMounted(() => {
  void load();
  timer = setInterval(() => {
    now.value = Date.now();
    for (const booking of bookings.value) {
      const key = `${booking.id}:${booking.version}`;
      if (booking.status === 'HELD' && remaining(booking) <= 0 && !expiredRefreshed.has(key)) {
        expiredRefreshed.add(key);
        fetchBooking(booking.id).then(update).catch(failure => { error.value = getErrorMessage(failure, '暂留已到期，请刷新预约状态'); });
      }
    }
  }, 1000);
});
onUnmounted(() => clearInterval(timer));
watch(focusedId, () => { reschedulingId.value = ''; void load(); });
</script>
<template>
  <section class="booking-page">
    <header class="booking-heading"><div><p class="eyebrow">我的到店安排</p><h1>{{ focusedId ? '预约详情' : '我的预约' }}</h1></div><button type="button" :disabled="loading" @click="load()">刷新</button></header>
    <p class="booking-rule">显示时间：{{ displayTimezone }}。预约不收取线上费用，核销表示商家确认履约。</p>
    <RouterLink v-if="focusedId" :to="{ name: 'bookings' }">返回全部预约</RouterLink>
    <BookingCommandRecovery :key="commandRevision" @resolved="resolved" />
    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    <p v-if="message" class="booking-summary" role="status">{{ message }}</p>
    <section v-if="notices.length" class="booking-notices" aria-label="预约站内消息"><h2>到店提醒</h2><article v-for="notice in notices" :key="notice.id"><p>{{ notice.message }}</p><div class="booking-actions"><RouterLink :to="{ name: 'booking-detail', params: { id: notice.bookingId } }">查看当前预约</RouterLink><button v-if="!notice.readAt" type="button" @click="markRead(notice)">标为已读</button><span v-else>已读</span></div></article></section>
    <p v-if="loading && !bookings.length">正在加载预约…</p>
    <article v-for="booking in bookings" :key="booking.id" class="booking-card" :class="{ highlighted: focusedId === booking.id }">
      <header class="booking-heading"><div><h2>{{ booking.resourceName }}</h2><span class="booking-small">{{ booking.kind === 'COURT' ? '运动场地' : '餐厅订位' }}</span></div><span class="booking-status">{{ bookingStatus[booking.status] || booking.status }}</span></header>
      <p><time>{{ bookingTime(booking.startAt) }} — {{ bookingTime(booking.endAt) }}</time> · {{ booking.partySize }} 人{{ booking.kind === 'COURT' ? ' · 1 块场地' : '' }}</p>
      <BookingPriceBreakdown :quote="frozenQuote(booking)" />
      <p v-if="booking.status === 'HELD'" class="booking-summary" aria-live="off">{{ remaining(booking) > 0 ? `名额暂留剩余 ${remaining(booking)} 秒，请在截止前确认。` : '暂留已到期，无法继续确认。请重新选择时段。' }}</p>
      <p v-if="booking.reasonCode" class="booking-small">处理原因：{{ booking.reasonCode }}</p>
      <p v-if="booking.checkinToken && booking.status === 'CONFIRMED'" class="booking-proof">到店时向本店工作人员出示核销凭证：<br /><code>{{ booking.checkinToken }}</code></p>
      <p v-if="rules && booking.status === 'CONFIRMED'" class="booking-rule">可在开始前 {{ rules.checkinEarlyMinutes }} 分钟至结束时核销；取消或改期须早于开始前 {{ rules.cancellationMinutes }} 分钟。</p>
      <div class="booking-actions">
        <RouterLink :to="{ name: 'shop-detail', params: { id: booking.shopId } }">查看店铺</RouterLink>
        <RouterLink v-if="!focusedId" :to="{ name: 'booking-detail', params: { id: booking.id } }">查看详情</RouterLink>
        <button v-if="can(booking, 'CONFIRM')" class="booking-primary" type="button" :disabled="busy" @click="pendingAction = { action: 'confirm', booking }">确认到店预约</button>
        <button v-if="can(booking, 'RESCHEDULE')" type="button" :disabled="busy" @click="reschedulingId = reschedulingId === booking.id ? '' : booking.id">{{ reschedulingId === booking.id ? '收起改期' : '改期 / 调整人数' }}</button>
        <button v-if="can(booking, 'CANCEL')" type="button" :disabled="busy" @click="pendingAction = { action: 'cancel', booking }">取消预约</button>
      </div>
      <BookingChooser v-if="reschedulingId === booking.id" :shop-id="booking.shopId" :original="booking" @booked="item => { update(item); reschedulingId = ''; message = '预约已改期，请使用新核销凭证'; }" />
    </article>
    <div v-if="!loading && !bookings.length" class="empty-state">暂无预约，<RouterLink :to="{ name: 'shops' }">去查看可预约店铺</RouterLink>。</div>
    <button v-if="hasMore" type="button" class="load-more" :disabled="loading" @click="loadMore">{{ loading ? '正在加载…' : '加载更多' }}</button>
    <ConfirmDialog :open="Boolean(pendingAction)" :title="actionTitle" :message="actionMessage" :detail="pendingAction?.action === 'cancel' ? '取消后将释放名额，需要再次选择时段才能预约。' : '确认后将按以上时间到店，实际消费线下结算。'" :busy="busy" :confirm-text="pendingAction?.action === 'confirm' ? '确认预约' : '取消这笔预约'" @cancel="pendingAction = undefined" @confirm="act" />
  </section>
</template>
