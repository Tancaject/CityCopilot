<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { couponLabel, fetchBookingOffers, fetchCouponRules, priceCents, publishCouponRule, revokeBookingCoupon,
  saveBookingOffer, setCouponIssuance, type BookingOffer, type CouponRule } from '../api/booking-pricing';
import type { BookingResource, BookingRules } from '../api/booking';
import { bookingInstant, bookingMoneyCents, bookingTime, localBookingInput, nextBookingDate } from '../utils/booking';
import { getErrorMessage } from '../utils/errors';
import ConfirmDialog from './ConfirmDialog.vue';
const props = defineProps<{ shopId: string; resource?: BookingResource; rules: BookingRules }>();
const offers = ref<BookingOffer[]>([]);
const rules = ref<CouponRule[]>([]);
const busy = ref(false);
const error = ref('');
const message = ref('');
const editing = ref<BookingOffer>();
const offerForm = ref({ title: '', includedItems: '', excludedItems: '', price: '0.00', status: 'OPEN' });
const ruleForm = ref({ campaignKey: '', offerId: '', type: 'FULL_REDUCTION' as CouponRule['type'], threshold: '0.00', reduction: '0.00', payPercent: '90.00', cap: '', stackable: false, serviceFrom: '', serviceTo: '', claimUntil: '', couponExpiresAt: '' });
const revokeId = ref('');
const revokeReason = ref('');
const pendingRevoke = ref(false);
const timezone = computed(() => props.resource?.timezone || props.rules.defaultTimezone);

function resetOffer(item?: BookingOffer) {
  editing.value = item;
  offerForm.value = item ? { title: item.title, includedItems: item.includedItems || '', excludedItems: item.excludedItems || '', price: (item.unitPriceCents / 100).toFixed(2), status: item.status }
    : { title: '', includedItems: '', excludedItems: '', price: '0.00', status: 'OPEN' };
}
function resetRule(item?: CouponRule) {
  const today = localBookingInput(props.rules.serverTime, timezone.value).slice(0, 10);
  const end = `${nextBookingDate(today, props.rules.publishingDays)}T23:59`;
  ruleForm.value = item ? { campaignKey: item.campaignKey, offerId: item.offerId || '', type: item.type,
    threshold: (item.thresholdCents / 100).toFixed(2), reduction: (item.reductionCents / 100).toFixed(2),
    payPercent: (item.payFactorBps / 100).toFixed(2), cap: item.discountCapCents == null ? '' : (item.discountCapCents / 100).toFixed(2),
    stackable: item.stackable, serviceFrom: localBookingInput(item.serviceFrom, timezone.value), serviceTo: localBookingInput(item.serviceTo, timezone.value),
    claimUntil: localBookingInput(item.claimUntil, timezone.value), couponExpiresAt: localBookingInput(item.couponExpiresAt, timezone.value) }
    : { campaignKey: '', offerId: '', type: 'FULL_REDUCTION', threshold: '0.00', reduction: '0.00', payPercent: '90.00', cap: '', stackable: false,
      serviceFrom: `${today}T00:00`, serviceTo: end, claimUntil: end, couponExpiresAt: end };
}
async function load() {
  busy.value = true;
  error.value = '';
  try {
    [offers.value, rules.value] = await Promise.all([props.resource ? fetchBookingOffers(props.resource.id, true) : Promise.resolve([]), fetchCouponRules(props.shopId, true)]);
    resetOffer();
    resetRule();
  } catch (failure) { error.value = getErrorMessage(failure, '价目和预约券规则加载失败'); }
  finally { busy.value = false; }
}
async function saveOffer() {
  if (!props.resource) return;
  busy.value = true;
  error.value = '';
  try {
    await saveBookingOffer(props.resource.id, { offerId: editing.value?.id, expectedVersion: editing.value?.version,
      kind: props.resource.kind === 'COURT' ? 'COURT_SLOT' : 'PER_PERSON_PACKAGE', title: offerForm.value.title,
      includedItems: offerForm.value.includedItems, excludedItems: offerForm.value.excludedItems,
      unitPriceCents: bookingMoneyCents(offerForm.value.price), status: offerForm.value.status });
    await load();
    message.value = '价目已保存，新报价采用更新后的版本；已有预约继续履行其价格快照。';
  } catch (failure) { error.value = getErrorMessage(failure, '价目保存失败'); }
  finally { busy.value = false; }
}
async function publishRule() {
  busy.value = true;
  error.value = '';
  try {
    const form = ruleForm.value;
    await publishCouponRule(props.shopId, { campaignKey: form.campaignKey, offerId: form.offerId || null, type: form.type,
      thresholdCents: bookingMoneyCents(form.threshold), reductionCents: form.type === 'DISCOUNT' ? 0 : bookingMoneyCents(form.reduction),
      payFactorBps: form.type === 'DISCOUNT' ? bookingMoneyCents(form.payPercent) : 0,
      discountCapCents: form.type === 'DISCOUNT' && form.cap ? bookingMoneyCents(form.cap) : null,
      stackable: form.stackable, serviceFrom: bookingInstant(form.serviceFrom, timezone.value), serviceTo: bookingInstant(form.serviceTo, timezone.value),
      claimUntil: bookingInstant(form.claimUntil, timezone.value), couponExpiresAt: bookingInstant(form.couponExpiresAt, timezone.value) });
    await load();
    message.value = '预约券新版本已发布。已领取的券继续引用原规则版本。';
  } catch (failure) { error.value = getErrorMessage(failure, '规则发布失败'); }
  finally { busy.value = false; }
}
async function toggleIssuance(rule: CouponRule) {
  busy.value = true;
  error.value = '';
  try {
    const updated = await setCouponIssuance(rule.id, rule.issuanceStatus === 'OPEN' ? 'STOPPED' : 'OPEN');
    rules.value = rules.value.map(item => item.id === updated.id ? updated : item);
    message.value = updated.issuanceStatus === 'OPEN' ? '已恢复发行' : '已停止新领取；已领券资格保持不变。';
  } catch (failure) { error.value = getErrorMessage(failure, '发行状态修改失败'); }
  finally { busy.value = false; }
}
async function revoke() {
  busy.value = true;
  error.value = '';
  try {
    const result = await revokeBookingCoupon(revokeId.value.trim(), revokeReason.value.trim());
    message.value = `已记录撤销资格。当前券状态：${result.status}；已确认预约的承诺价格需继续履行或明确取消。`;
    pendingRevoke.value = false;
  } catch (failure) { error.value = getErrorMessage(failure, '撤券未完成'); }
  finally { busy.value = false; }
}
watch(() => [props.shopId, props.resource?.id], load, { immediate: true });
</script>
<template>
  <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p><p v-if="message" class="booking-summary" role="status">{{ message }}</p>
  <section v-if="resource" class="booking-panel"><header class="booking-heading"><h2>{{ resource.name }} · {{ resource.kind === 'COURT' ? '场地价目' : '按人套餐' }}</h2><button type="button" @click="resetOffer()">新增价目</button></header>
    <div class="booking-table"><table><thead><tr><th>价目</th><th>单价</th><th>包含 / 另收费项目</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="offer in offers" :key="offer.id"><td>{{ offer.title }} · v{{ offer.version }}</td><td>{{ priceCents(offer.unitPriceCents) }}</td><td>{{ offer.includedItems || '未填写' }} / {{ offer.excludedItems || '无' }}</td><td>{{ offer.status === 'OPEN' ? '发行中' : '停售' }}</td><td><button type="button" @click="resetOffer(offer)">修改价目</button></td></tr></tbody></table></div>
    <form class="booking-form" @submit.prevent="saveOffer"><label>价目名称<input v-model.trim="offerForm.title" maxlength="120" required /></label><label>单价（元 / {{ resource.kind === 'COURT' ? '一个时段' : '人' }}）<input v-model="offerForm.price" inputmode="decimal" pattern="\d+(\.\d{1,2})?" required /></label><label>发行状态<select v-model="offerForm.status"><option value="OPEN">发行中</option><option value="STOPPED">停售</option></select></label><label>包含项目<textarea v-model.trim="offerForm.includedItems" rows="3" required /></label><label>另收费或不含项目<textarea v-model.trim="offerForm.excludedItems" rows="3" placeholder="没有时填写无" required /></label><p class="booking-rule">同一场地只保留一个在售时段价；餐厅可以同时发布多个按人套餐。已确认预约的价格与包含项目保留其快照。</p><button class="booking-primary" type="submit" :disabled="busy">{{ editing ? '发布更新后的价目' : '发布价目' }}</button></form>
  </section>
  <section class="booking-panel"><header class="booking-heading"><h2>预约券规则</h2><button type="button" @click="resetRule()">新活动</button></header><p class="booking-rule">修改经济规则会发布新版本；停止发行只影响后续领取，不改变已领券资格。服务时间按 {{ timezone }} 填写。</p>
    <div class="booking-table"><table><thead><tr><th>活动 / 版本</th><th>优惠</th><th>可叠加</th><th>领取截止</th><th>操作</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id"><td>{{ rule.campaignKey }} · v{{ rule.ruleVersion }}</td><td>{{ couponLabel(rule) }}</td><td>{{ rule.stackable ? '允许' : '不允许' }}</td><td>{{ bookingTime(rule.claimUntil, timezone) }}</td><td><button type="button" :disabled="busy" @click="toggleIssuance(rule)">{{ rule.issuanceStatus === 'OPEN' ? '停止发行' : '恢复发行' }}</button><button type="button" @click="resetRule(rule)">以此发布新版本</button></td></tr></tbody></table></div>
    <form class="booking-form" @submit.prevent="publishRule"><label>活动标识<input v-model.trim="ruleForm.campaignKey" maxlength="128" placeholder="同活动新版本沿用标识" required /></label><label>适用价目<select v-model="ruleForm.offerId"><option value="">本店所有价目</option><option v-for="offer in offers" :key="offer.id" :value="offer.id">{{ offer.title }}</option></select></label><label>优惠种类<select v-model="ruleForm.type"><option value="FULL_REDUCTION">满减</option><option value="DISCOUNT">折扣</option><option value="CASH">代金</option></select></label><label>优惠前适用金额门槛（元）<input v-model="ruleForm.threshold" inputmode="decimal" pattern="\d+(\.\d{1,2})?" required /></label><label v-if="ruleForm.type !== 'DISCOUNT'">减免额（元）<input v-model="ruleForm.reduction" inputmode="decimal" pattern="\d+(\.\d{1,2})?" required /></label><template v-else><label>实际支付比例（%，90 表示九折）<input v-model="ruleForm.payPercent" inputmode="decimal" pattern="\d+(\.\d{1,2})?" required /></label><label>折扣减免封顶（元，可留空）<input v-model="ruleForm.cap" inputmode="decimal" pattern="\d+(\.\d{1,2})?" /></label></template><label>叠加规则<select v-model="ruleForm.stackable"><option :value="false">不能与其他券叠加</option><option :value="true">允许与另一种可叠加券共同使用</option></select></label><label>适用到店开始时间<input v-model="ruleForm.serviceFrom" type="datetime-local" required /></label><label>适用到店结束时间<input v-model="ruleForm.serviceTo" type="datetime-local" required /></label><label>领取截止<input v-model="ruleForm.claimUntil" type="datetime-local" required /></label><label>个人券使用截止<input v-model="ruleForm.couponExpiresAt" type="datetime-local" required /></label><button class="booking-primary" type="submit" :disabled="busy">发布新的规则版本</button></form>
  </section>
  <section class="booking-panel"><h2>撤销一张个人券的资格</h2><form class="booking-form" @submit.prevent="pendingRevoke = true"><label>个人预约券 ID<input v-model.trim="revokeId" required /></label><label>撤销原因<input v-model.trim="revokeReason" maxlength="255" required /></label><p class="booking-rule">此操作会撤销指定用户券的资格，不能代替停止活动发行。已确认预约不会因此静默改价或取消。</p><button type="submit" class="booking-danger" :disabled="busy">核对撤券信息</button></form></section>
  <ConfirmDialog :open="pendingRevoke" title="确认撤销个人预约券资格" :message="revokeReason" :detail="`券 ID：${revokeId}`" :busy="busy" confirm-text="确认撤销资格" tone="danger" @cancel="pendingRevoke = false" @confirm="revoke" />
</template>
