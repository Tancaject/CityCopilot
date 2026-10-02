<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { createBookingResource, fetchBooking, fetchBookingResources, fetchBookingRules, fetchBookingShops, fetchBookingSlots,
  fetchShopBookings, saveBookingSlot, submitBookingCommand, updateBookingResource,
  type Booking, type BookingMutation, type BookingResource, type BookingRules, type BookingSlot, type ResourceRequest, type SlotRequest } from '../api/booking';
import { authState } from '../composables/auth';
import { hasPermission } from '../utils/permissions';
import { bookingDayRange, bookingInstant, bookingPrice, bookingStatus, bookingTime, localBookingInput, nextBookingDate } from '../utils/booking';
import { getErrorMessage } from '../utils/errors';
import BookingCommandRecovery from '../components/BookingCommandRecovery.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import AdminBookingPricing from '../components/AdminBookingPricing.vue';

const route = useRoute();
const router = useRouter();
const shops = ref<{ id: string; name: string }[]>([]);
const shopId = ref('');
const resources = ref<BookingResource[]>([]);
const resourceId = ref('');
const resource = computed(() => resources.value.find(item => item.id === resourceId.value));
const rules = ref<BookingRules>();
const date = ref('');
const timezone = computed(() => resource.value?.timezone || rules.value?.defaultTimezone || 'UTC');
const slots = ref<BookingSlot[]>([]);
const bookings = ref<Booking[]>([]);
const error = ref('');
const message = ref('');
const busy = ref(false);
const commandRevision = ref(0);
const activeTab = ref<'bookings' | 'resources' | 'pricing'>('bookings');
const canManage = computed(() => hasPermission(authState.user, 'booking:manage'));
const canCheckIn = computed(() => hasPermission(authState.user, 'booking:check-in'));
const resourceForm = ref<ResourceRequest>({ kind: 'COURT', name: '', timezone: '', maxPartySize: 4, status: 'OPEN' });
const editingResourceId = ref('');
const slotForm = ref({ start: '', end: '', capacity: 1, status: 'OPEN' });
const editingSlot = ref<BookingSlot>();
const lookupId = ref('');
const tokenInputs = ref<Record<string, string>>({});
const reasonInputs = ref<Record<string, string>>({});
const pending = ref<{ kind: 'check-in' | 'cancel'; booking: Booking }>();
const minDate = computed(() => rules.value ? localBookingInput(rules.value.serverTime, timezone.value).slice(0, 10) : '');
const maxDate = computed(() => rules.value ? nextBookingDate(minDate.value, rules.value.publishingDays) : '');

async function loadShop() {
  if (!shopId.value) return;
  busy.value = true;
  error.value = '';
  try {
    resources.value = await fetchBookingResources(shopId.value, true);
    if (!resources.value.some(item => item.id === resourceId.value)) resourceId.value = resources.value[0]?.id || '';
    await Promise.all([loadSlots(), loadBookings()]);
    await router.replace({ name: 'admin-bookings', query: { shopId: shopId.value } });
  } catch (failure) { error.value = getErrorMessage(failure, '店铺预约信息加载失败'); }
  finally { busy.value = false; }
}
async function loadBookings() {
  if (!shopId.value || !date.value) return;
  const [from, to] = bookingDayRange(date.value, timezone.value);
  bookings.value = await fetchShopBookings(shopId.value, from, to);
}
async function loadSlots() {
  slots.value = [];
  if (!resource.value || !date.value) return;
  const [from, to] = bookingDayRange(date.value, timezone.value);
  slots.value = await fetchBookingSlots(resource.value.id, from, to);
}
async function refresh() {
  busy.value = true;
  error.value = '';
  try { await Promise.all([loadSlots(), loadBookings()]); }
  catch (failure) { error.value = getErrorMessage(failure, '排期刷新失败'); }
  finally { busy.value = false; }
}
function editResource(item?: BookingResource) {
  editingResourceId.value = item?.id || '';
  resourceForm.value = item ? { kind: item.kind, name: item.name, timezone: item.timezone, maxPartySize: item.maxPartySize, status: item.status, expectedVersion: item.version }
    : { kind: 'COURT', name: '', timezone: rules.value!.defaultTimezone, maxPartySize: 4, status: 'OPEN' };
}
async function saveResource() {
  if (!shopId.value) return;
  busy.value = true;
  error.value = '';
  try {
    const result = editingResourceId.value ? await updateBookingResource(editingResourceId.value, resourceForm.value) : await createBookingResource(shopId.value, resourceForm.value);
    resourceId.value = result.id;
    editResource();
    await loadShop();
    message.value = '预约资源已保存';
  } catch (failure) { error.value = getErrorMessage(failure, '资源保存失败'); }
  finally { busy.value = false; }
}
function editSlot(slot?: BookingSlot) {
  editingSlot.value = slot;
  slotForm.value = slot ? { start: localBookingInput(slot.startAt, timezone.value), end: localBookingInput(slot.endAt, timezone.value), capacity: slot.capacity, status: slot.status }
    : { start: `${date.value}T09:00`, end: `${date.value}T10:00`, capacity: resource.value?.kind === 'COURT' ? 1 : 20, status: 'OPEN' };
}
async function saveSlot() {
  if (!resource.value) return;
  busy.value = true;
  error.value = '';
  try {
    const request: SlotRequest = { slotId: editingSlot.value?.id, expectedVersion: editingSlot.value?.version,
      startAt: bookingInstant(slotForm.value.start, timezone.value), endAt: bookingInstant(slotForm.value.end, timezone.value),
      capacity: resource.value.kind === 'COURT' ? 1 : slotForm.value.capacity, status: slotForm.value.status };
    await saveBookingSlot(resource.value.id, request);
    editSlot();
    await loadSlots();
    message.value = '排期已保存';
  } catch (failure) { error.value = getErrorMessage(failure, '排期保存失败'); }
  finally { busy.value = false; }
}
function resolved(result: BookingMutation) {
  if (result.booking) {
    const index = bookings.value.findIndex(item => item.id === result.booking!.id);
    if (index >= 0) bookings.value[index] = result.booking;
    else bookings.value.unshift(result.booking);
  }
  if (result.httpStatus >= 400) error.value = result.message;
  else message.value = result.message || '预约已处理';
  pending.value = undefined;
  commandRevision.value++;
}
async function act() {
  const action = pending.value;
  if (!action) return;
  busy.value = true;
  error.value = '';
  try {
    resolved(await submitBookingCommand(action.kind === 'check-in' ? '到店核销' : '商家取消', `/admin/bookings/${action.booking.id}/${action.kind}`, {
      expectedVersion: action.booking.version,
      token: action.kind === 'check-in' ? tokenInputs.value[action.booking.id]?.trim() : undefined,
      reason: action.kind === 'cancel' ? reasonInputs.value[action.booking.id]?.trim() : undefined,
    }));
    await loadSlots();
  } catch (failure) { error.value = getErrorMessage(failure, '预约处理失败，请刷新后查看当前状态'); pending.value = undefined; }
  finally { busy.value = false; commandRevision.value++; }
}
async function lookup() {
  if (!lookupId.value.trim()) return;
  busy.value = true;
  error.value = '';
  try {
    const result = await fetchBooking(lookupId.value.trim(), true);
    bookings.value = [result, ...bookings.value.filter(item => item.id !== result.id)];
  } catch (failure) { error.value = getErrorMessage(failure, '未找到可管理的预约'); }
  finally { busy.value = false; }
}
onMounted(async () => {
  busy.value = true;
  try {
    [rules.value, shops.value] = await Promise.all([fetchBookingRules(), fetchBookingShops()]);
    date.value = localBookingInput(rules.value.serverTime, rules.value.defaultTimezone).slice(0, 10);
    const requested = typeof route.query.shopId === 'string' ? route.query.shopId : '';
    shopId.value = shops.value.find(item => item.id === requested)?.id || shops.value[0]?.id || '';
    editResource();
    await loadShop();
    editSlot();
  } catch (failure) { error.value = getErrorMessage(failure, '预约工作台加载失败'); }
  finally { busy.value = false; }
});
// A newly created id is selected before the resource list reloads; reset only against the resolved resource kind.
watch([() => resource.value?.id, () => resource.value?.kind, date], () => {
  if (resource.value) { editSlot(); void refresh(); }
});
</script>
<template>
  <section class="booking-page">
    <header class="booking-heading"><div><p class="eyebrow">商家预约工作台</p><h1>排期与到店履约</h1></div><button type="button" :disabled="busy" @click="refresh">刷新</button></header>
    <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p><p v-if="message" class="booking-summary" role="status">{{ message }}</p>
    <BookingCommandRecovery :key="commandRevision" @resolved="resolved" />
    <p v-if="!busy && !shops.length" class="empty-state">没有可管理的店铺，请联系管理员确认店铺归属。</p>
    <template v-if="rules && shops.length">
      <p v-if="!rules.newBookingsEnabled" class="booking-pending">新增预约入口已暂停。请继续处理已有预约与商家履约。</p>
      <div class="booking-filter"><label>店铺<select v-model="shopId" :disabled="busy" @change="loadShop"><option v-for="shop in shops" :key="shop.id" :value="shop.id">{{ shop.name }}</option></select></label><label>日期（{{ timezone }}）<input v-model="date" type="date" :disabled="busy" @change="refresh" /></label><label>排期资源<select v-model="resourceId" :disabled="busy"><option v-for="item in resources" :key="item.id" :value="item.id">{{ item.name }}</option></select></label></div>
      <nav class="booking-tabs" aria-label="预约管理视图"><button type="button" :aria-selected="activeTab === 'bookings'" @click="activeTab = 'bookings'">预约与核销</button><button v-if="canManage" type="button" :aria-selected="activeTab === 'resources'" @click="activeTab = 'resources'">资源与排期</button><button v-if="canManage" type="button" :aria-selected="activeTab === 'pricing'" @click="activeTab = 'pricing'">价目与预约券</button></nav>
      <AdminBookingPricing v-if="activeTab === 'pricing' && canManage" :shop-id="shopId" :resource="resource" :rules="rules" />
      <template v-if="activeTab === 'bookings'">
        <form class="booking-filter" @submit.prevent="lookup"><label>按预约号查询<input v-model.trim="lookupId" autocomplete="off" placeholder="输入用户预约号" required /></label><button type="submit" :disabled="busy">查询本店预约</button></form>
        <p class="booking-rule">核销窗口：开始前 {{ rules.checkinEarlyMinutes }} 分钟至时段结束。核销确认履约，消费线下结算。</p>
        <article v-for="booking in bookings" :key="booking.id" class="booking-card"><header class="booking-heading"><div><h2>{{ booking.resourceName }}</h2><small>{{ booking.id }}</small></div><span class="booking-status">{{ bookingStatus[booking.status] || booking.status }}</span></header><p>{{ bookingTime(booking.startAt, timezone) }} — {{ bookingTime(booking.endAt, timezone) }} · {{ booking.partySize }} 人</p><p>{{ bookingPrice(booking.priceSnapshot) }}</p>
          <form v-if="canCheckIn && booking.allowedActions.includes('CHECK_IN')" class="booking-inline-check" @submit.prevent="pending = { kind: 'check-in', booking }"><label :for="`token-${booking.id}`">用户核销凭证</label><input :id="`token-${booking.id}`" v-model.trim="tokenInputs[booking.id]" autocomplete="off" required /><button class="booking-primary" type="submit" :disabled="busy">核对并核销</button></form>
          <form v-if="canManage && booking.allowedActions.includes('CANCEL')" class="booking-inline-check" @submit.prevent="pending = { kind: 'cancel', booking }"><label :for="`reason-${booking.id}`">无法接待原因</label><input :id="`reason-${booking.id}`" v-model.trim="reasonInputs[booking.id]" maxlength="500" required placeholder="向用户说明取消原因" /><button class="booking-danger" type="submit" :disabled="busy">取消并通知用户</button></form>
        </article><p v-if="!busy && !bookings.length" class="empty-state">所选日期暂无预约。</p>
      </template>
      <template v-if="activeTab === 'resources' && canManage">
        <section class="booking-panel"><header class="booking-heading"><h2>接待资源</h2><button type="button" @click="editResource()">新建资源</button></header>
          <div class="booking-table"><table><thead><tr><th>名称</th><th>类型 / 人数上限</th><th>接待状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in resources" :key="item.id"><td>{{ item.name }}</td><td>{{ item.kind === 'COURT' ? '运动场地' : '餐厅名额池' }} / {{ item.maxPartySize }} 人</td><td>{{ { OPEN: '开放', STOP_NEW: '暂不接新单', UNAVAILABLE: '无法接待' }[item.status] }}</td><td><button type="button" @click="editResource(item)">编辑</button></td></tr></tbody></table></div>
          <form class="booking-form" @submit.prevent="saveResource"><label>资源名称<input v-model.trim="resourceForm.name" required maxlength="120" /></label><label>业务类型<select v-model="resourceForm.kind" :disabled="Boolean(editingResourceId)"><option value="COURT">一块具体场地</option><option value="DINING_POOL">餐厅接待名额池</option></select></label><label>每笔参与人数上限<input v-model.number="resourceForm.maxPartySize" type="number" min="1" :max="resourceForm.kind === 'DINING_POOL' ? rules.maxDiningPartySize : undefined" required /></label><label>商家时区<input v-model.trim="resourceForm.timezone" required placeholder="例如 Asia/Shanghai" /></label><label>接待状态<select v-model="resourceForm.status"><option value="OPEN">开放接待</option><option value="STOP_NEW">暂不接新单</option><option value="UNAVAILABLE">无法接待</option></select></label><p class="booking-rule">暂不接新单保留已有预约；无法接待后仍应逐笔处理已确认的预约，并填写原因通知用户。</p><button type="submit" class="booking-primary" :disabled="busy">{{ editingResourceId ? '保存资源变更' : '创建资源' }}</button></form>
        </section>
        <section v-if="resource" class="booking-panel"><header class="booking-heading"><h2>{{ resource.name }} · 排期</h2><button type="button" @click="editSlot()">新增时段</button></header><p class="booking-rule">排期可发布未来 {{ rules.publishingDays }} 天；场馆时段 {{ rules.courtSlotMinutes }} 分钟并按当地整点开始。餐次不可重叠。已有预约时不能修改时间，容量不得低于已占用人数。</p>
          <div class="booking-table"><table><thead><tr><th>时间（{{ timezone }}）</th><th>占用 / 容量</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="slot in slots" :key="slot.id"><td>{{ bookingTime(slot.startAt, timezone) }} — {{ bookingTime(slot.endAt, timezone) }}</td><td>{{ slot.occupied }} / {{ slot.capacity }}</td><td>{{ slot.status === 'OPEN' ? '开放' : '停止新预约' }}</td><td><button type="button" @click="editSlot(slot)">调整</button></td></tr></tbody></table></div>
          <form class="booking-form" @submit.prevent="saveSlot"><label>开始时间<input v-model="slotForm.start" type="datetime-local" :min="`${minDate}T00:00`" :max="`${maxDate}T23:59`" :disabled="!!editingSlot?.occupied" required /></label><label>结束时间<input v-model="slotForm.end" type="datetime-local" :disabled="!!editingSlot?.occupied" required /></label><label>容量（{{ resource.kind === 'COURT' ? '块场地' : '人' }}）<input v-model.number="slotForm.capacity" type="number" :min="Math.max(1, editingSlot?.occupied || 0)" :max="resource.kind === 'COURT' ? 1 : undefined" :disabled="resource.kind === 'COURT'" required /></label><label>开放状态<select v-model="slotForm.status"><option value="OPEN">开放</option><option value="STOP_NEW">停止新预约</option></select></label><button type="submit" class="booking-primary" :disabled="busy">{{ editingSlot ? '保存时段调整' : '发布时段' }}</button></form>
        </section>
      </template>
    </template>
    <ConfirmDialog :open="Boolean(pending)" :title="pending?.kind === 'check-in' ? '确认用户已到店履约' : '确认商家取消预约'" :message="pending ? `${pending.booking.resourceName} · ${bookingTime(pending.booking.startAt, timezone)} · ${pending.booking.partySize} 人` : ''" :detail="pending?.kind === 'cancel' ? reasonInputs[pending.booking.id] : '核销后不可重复使用；此操作不表示支付渠道到账。'" :busy="busy" :confirm-text="pending?.kind === 'check-in' ? '确认核销' : '取消并通知'" @cancel="pending = undefined" @confirm="act" />
  </section>
</template>
