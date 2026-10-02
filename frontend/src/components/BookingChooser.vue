<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { fetchBookingResources, fetchBookingRules, fetchBookingSlots, submitBookingCommand,
  type Booking, type BookingMutation, type BookingResource, type BookingRules, type BookingSlot } from '../api/booking';
import { authState } from '../composables/auth';
import { bookingDayRange, bookingTime, localBookingInput, nextBookingDate, bookingPrice } from '../utils/booking';
import { getErrorMessage } from '../utils/errors';
import BookingCommandRecovery from './BookingCommandRecovery.vue';
import BookingPriceBreakdown from './BookingPriceBreakdown.vue';
import { claimBookingCoupon, couponLabel, couponStatus, fetchBookingCoupons, fetchBookingOffers, fetchCouponRules,
  priceCents, quoteBooking, type BookingCoupon, type BookingOffer, type BookingQuote, type CouponRule } from '../api/booking-pricing';

const props = defineProps<{ shopId: string; original?: Booking }>();
const emit = defineEmits<{ (event: 'booked', booking: Booking): void }>();
const route = useRoute();
const router = useRouter();
const rules = ref<BookingRules>();
const resources = ref<BookingResource[]>([]);
const resourceId = ref('');
const resource = computed(() => resources.value.find(item => item.id === resourceId.value));
const slots = ref<BookingSlot[]>([]);
const selectedSlotId = ref('');
const selectedSlot = computed(() => slots.value.find(item => item.id === selectedSlotId.value));
const date = ref('');
const partySize = ref(props.original?.partySize || 1);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const commandRevision = ref(0);
const offers = ref<BookingOffer[]>([]);
const offersLoaded = ref(false);
const offerId = ref('');
const couponMode = ref<'AUTO' | 'NONE' | 'MANUAL'>('AUTO');
const selectedCouponIds = ref<string[]>([]);
const coupons = ref<BookingCoupon[]>([]);
const couponRules = ref<CouponRule[]>([]);
const quote = ref<BookingQuote>();
const timezone = computed(() => resource.value?.timezone || rules.value?.defaultTimezone || 'UTC');
const minDate = computed(() => rules.value ? localBookingInput(rules.value.serverTime, timezone.value).slice(0, 10) : '');
const maxDate = computed(() => rules.value ? nextBookingDate(minDate.value, rules.value.publishingDays) : '');
const maxParty = computed(() => Math.min(resource.value?.maxPartySize || 1,
  resource.value?.kind === 'DINING_POOL' ? rules.value?.maxDiningPartySize || 1 : Infinity));
const canSelect = (slot: BookingSlot) => slot.status === 'OPEN' && (slot.available >= (resource.value?.kind === 'COURT' ? 1 : partySize.value)
  || props.original?.slotId === slot.id);

async function load() {
  loading.value = true;
  error.value = '';
  resources.value = [];
  try {
    [rules.value, resources.value] = await Promise.all([fetchBookingRules(), fetchBookingResources(props.shopId)]);
    resourceId.value = props.original?.resourceId || resources.value[0]?.id || '';
    date.value = localBookingInput(props.original?.startAt || rules.value.serverTime, timezone.value).slice(0, 10);
    await loadSlots();
    [couponRules.value, coupons.value] = await Promise.all([fetchCouponRules(props.shopId), authState.isLoggedIn ? fetchBookingCoupons(props.shopId) : Promise.resolve([])]);
  } catch (failure) { error.value = getErrorMessage(failure, '预约资源加载失败'); }
  finally { loading.value = false; }
}
async function loadSlots() {
  slots.value = [];
  selectedSlotId.value = '';
  quote.value = undefined;
  offersLoaded.value = false;
  offers.value = [];
  if (!resource.value || !date.value) return;
  error.value = '';
  try {
    const [from, to] = bookingDayRange(date.value, timezone.value);
    const id = resourceId.value;
    const selectedDate = date.value;
    const [result, availableOffers] = await Promise.all([fetchBookingSlots(id, from, to), fetchBookingOffers(id)]);
    if (id === resourceId.value && selectedDate === date.value) {
      slots.value = result;
      offers.value = availableOffers;
      offerId.value = resource.value?.kind === 'COURT' ? availableOffers[0]?.id || '' : '';
      offersLoaded.value = true;
    }
  } catch (failure) { error.value = getErrorMessage(failure, '排期加载失败'); }
}
function resourceChanged() {
  partySize.value = Math.min(partySize.value, maxParty.value);
  void loadSlots();
}
function resolved(result: BookingMutation) {
  commandRevision.value++;
  if (result.httpStatus >= 400) error.value = result.message;
  else if (result.booking) emit('booked', result.booking);
}
async function claim(ruleId: string) {
  if (!authState.isLoggedIn) { await router.push({ name: 'login', query: { redirect: route.fullPath } }); return; }
  busy.value = true;
  error.value = '';
  try {
    await claimBookingCoupon(ruleId);
    coupons.value = await fetchBookingCoupons(props.shopId);
    quote.value = undefined;
  } catch (failure) { error.value = getErrorMessage(failure, '领取未完成，请刷新我的券后重试'); }
  finally { busy.value = false; }
}
async function submit() {
  if (!selectedSlot.value || !resource.value) return;
  if (!authState.isLoggedIn) {
    await router.push({ name: 'login', query: { redirect: route.fullPath } });
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    const target = { resourceId: resourceId.value, slotId: selectedSlotId.value, partySize: partySize.value };
    const currentTime = Date.now() + (rules.value ? Date.parse(rules.value.serverTime) - loadedAt : 0);
    if (!quote.value || Date.parse(quote.value.expiresAt) <= currentTime) {
      quote.value = await quoteBooking({ ...target, offerId: offerId.value || undefined, couponMode: offerId.value ? couponMode.value : 'NONE',
        couponIds: offerId.value && couponMode.value === 'MANUAL' ? selectedCouponIds.value : [], purpose: props.original ? 'RESCHEDULE' : 'CREATE',
        bookingId: props.original?.id, expectedBookingVersion: props.original?.version, source: 'WEB' });
      return;
    }
    const result = props.original
      ? await submitBookingCommand('确认改期', `/bookings/${props.original.id}/reschedule`, { ...target, quoteId: quote.value.quoteId, expectedVersion: props.original.version })
      : await submitBookingCommand('暂留名额', '/bookings/holds', { ...target, quoteId: quote.value.quoteId, source: 'WEB' });
    resolved(result);
  } catch (failure) { error.value = getErrorMessage(failure, '预约操作未完成'); }
  finally { busy.value = false; commandRevision.value++; }
}
watch(() => props.shopId, load, { immediate: true });
let loadedAt = Date.now();
watch(rules, () => { loadedAt = Date.now(); });
watch([selectedSlotId, partySize, offerId, couponMode, selectedCouponIds], () => { quote.value = undefined; }, { deep: true });
</script>
<template>
  <section class="booking-panel" :aria-label="original ? '选择新预约方案' : '选择预约时段'">
    <header class="booking-heading"><div><p class="eyebrow">{{ original ? '调整预约' : '到店预约' }}</p><h2>{{ original ? '选择新时段与人数' : '先选时段，再确认到店' }}</h2></div></header>
    <BookingCommandRecovery :key="commandRevision" @resolved="resolved" />
    <p v-if="error" class="notice notice-error" role="alert">{{ error }} <button type="button" @click="load">刷新</button></p>
    <p v-if="loading">正在查询商家排期…</p>
    <p v-else-if="!resources.length" class="empty-state">商家尚未发布预约资源。</p>
    <template v-else-if="rules">
      <p v-if="!rules.newBookingsEnabled" class="booking-pending">目前暂停新增预约，已有预约仍可查询、确认、取消和履约处理。</p>
      <p class="booking-rule">名额暂留 {{ rules.holdSeconds }} 秒；最多保留 {{ rules.maxActiveHolds }} 个有效暂留、{{ rules.maxFutureBookings }} 笔未来预约。确认后的取消和改期须在开始前 {{ rules.cancellationMinutes }} 分钟完成。</p>
      <form class="booking-form" @submit.prevent="submit">
        <label>场地 / 接待区<select v-model="resourceId" :disabled="busy" @change="resourceChanged"><option v-for="item in resources" :key="item.id" :value="item.id">{{ item.name }} · {{ item.kind === 'COURT' ? '运动场地' : '餐厅订位' }}{{ item.status !== 'OPEN' ? ' · 暂不接新单' : '' }}</option></select></label>
        <label>日期（{{ timezone }}）<input v-model="date" type="date" :min="minDate" :max="maxDate" required :disabled="busy" @change="loadSlots" /></label>
        <label>{{ resource?.kind === 'COURT' ? '参与人数' : '就餐人数' }}<input v-model.number="partySize" type="number" min="1" :max="maxParty" required :disabled="busy" /></label>
        <p class="booking-rule">{{ resource?.kind === 'COURT' ? '每次预约占用一块具体场地。' : '按人数占用餐次名额，不分配具体餐桌。' }}</p>
        <fieldset class="booking-slots"><legend>选择{{ resource?.kind === 'COURT' ? '场地时段' : '餐次' }}</legend>
          <label v-for="slot in slots" :key="slot.id" class="booking-slot" :class="{ selected: selectedSlotId === slot.id, unavailable: !canSelect(slot) }">
            <input v-model="selectedSlotId" type="radio" name="booking-slot" :value="slot.id" :disabled="busy || !canSelect(slot) || resource?.status !== 'OPEN'" />
            <strong>{{ bookingTime(slot.startAt, timezone) }} — {{ bookingTime(slot.endAt, timezone) }}</strong>
            <small>{{ slot.status === 'OPEN' ? `剩余 ${slot.available} / ${slot.capacity} ${resource?.kind === 'COURT' ? '块场地' : '人'}` : '已停止接单' }}</small>
          </label>
          <p v-if="!slots.length">当天没有发布可用排期，请选择其他日期。</p>
        </fieldset>
        <label v-if="offers.length">{{ resource?.kind === 'COURT' ? '场地价目' : '套餐选择' }}<select v-model="offerId" :disabled="busy"><option v-if="resource?.kind !== 'COURT'" value="">仅订位，消费到店确定</option><option v-for="offer in offers" :key="offer.id" :value="offer.id">{{ offer.title }} · {{ priceCents(offer.unitPriceCents) }} / {{ offer.kind === 'COURT_SLOT' ? '时段' : '人' }}</option></select></label>
        <label v-if="offerId">优惠选择<select v-model="couponMode" :disabled="busy"><option value="AUTO">按当前完整候选集自动选券</option><option value="NONE">不使用优惠券</option><option value="MANUAL">手动选择</option></select></label>
        <fieldset v-if="offerId && couponMode === 'MANUAL'" class="booking-slots"><legend>选择预约券（最多两种可叠加券）</legend><label v-for="coupon in coupons.filter(item => item.status === 'AVAILABLE' || (item.status === 'RESERVED' && item.bookingId === original?.id))" :key="coupon.id" class="booking-slot"><input v-model="selectedCouponIds" type="checkbox" :value="coupon.id" :disabled="busy" />{{ couponLabel(coupon.rule) }}<small>{{ couponStatus[coupon.status] }} · {{ coupon.rule.stackable ? '允许叠加' : '不可叠加' }} · 有效至 {{ bookingTime(coupon.expiresAt, timezone) }}</small></label></fieldset>
        <section v-if="couponRules.length" class="booking-coupon-list"><h3>本店预约券</h3><article v-for="rule in couponRules" :key="rule.id"><strong>{{ couponLabel(rule) }}</strong><small> {{ rule.offerId ? '限指定价目' : '本店价目' }} · {{ rule.stackable ? '允许叠加' : '不可叠加' }} · 领取截止 {{ bookingTime(rule.claimUntil, timezone) }}</small><button type="button" :disabled="busy || coupons.some(item => item.rule.id === rule.id)" @click="claim(rule.id)">{{ coupons.some(item => item.rule.id === rule.id) ? '已领取' : '领取' }}</button></article></section>
        <div v-if="selectedSlot" class="booking-summary">
          <template v-if="original"><p>原预约：{{ original.resourceName }} · {{ bookingTime(original.startAt, timezone) }} · {{ original.partySize }} 人</p><p>原价格：{{ bookingPrice(original.priceSnapshot) }}</p></template>
          <strong>{{ original ? '新预约' : '所选方案' }}：{{ resource?.name }} · {{ bookingTime(selectedSlot.startAt, timezone) }} · {{ partySize }} 人</strong>
          <p v-if="!quote">请核对以上条件后计算报价；查询和报价不会占用名额。</p>
          <p v-if="original">只有新方案变更成功后才会释放原名额；更改未成功会保留原预约。</p>
        </div>
        <BookingPriceBreakdown v-if="quote" :quote="quote" />
        <button v-if="quote" type="button" :disabled="busy" @click="quote = undefined">重新计算报价</button>
        <p v-if="quote" class="booking-rule">本报价有效至 {{ bookingTime(quote.expiresAt, timezone) }}。{{ original ? '请确认新时间、人数、价格与使用券；原预约将在变更成功后更新。' : '选择暂留后请在名额截止前完成最终确认。' }}</p>
        <button class="booking-primary" type="submit" :disabled="busy || !selectedSlot || !offersLoaded || resource?.status !== 'OPEN' || (!original && !rules.newBookingsEnabled)">{{ busy ? '正在提交…' : !quote ? (offerId ? '计算价格与优惠' : '核对订位方案') : original ? '确认新的时间、人数与价格' : '按此方案暂留名额' }}</button>
      </form>
    </template>
  </section>
</template>
