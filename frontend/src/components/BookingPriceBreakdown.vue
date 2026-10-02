<script setup lang="ts">
import type { BookingQuote } from '../api/booking-pricing';
import { couponType, priceCents } from '../api/booking-pricing';
defineProps<{ quote: BookingQuote }>();
</script>
<template>
  <section class="booking-summary" aria-label="预约价格明细">
    <template v-if="quote.pricingMode === 'FIXED' && quote.totalCents !== null">
      <strong>{{ quote.offerTitle }} · {{ priceCents(quote.totalCents) }}</strong>
      <p v-if="quote.includedItems">包含：{{ quote.includedItems }}</p><p v-if="quote.excludedItems">另收费 / 不含：{{ quote.excludedItems }}</p>
      <p>优惠前 {{ priceCents(quote.baseCents || 0) }}<span v-if="quote.unitPriceCents !== null"> · 单价 {{ priceCents(quote.unitPriceCents) }}</span></p>
      <div v-for="line in quote.discountLines" :key="line.couponId" class="booking-discount-line">
        <span>{{ couponType[line.type] || line.type }}：减 {{ priceCents(line.discountCents) }}</span>
        <small>适用原价 {{ priceCents(line.eligibleBaseCents) }}；本步 {{ priceCents(line.beforeCents) }} → {{ priceCents(line.afterCents) }}</small>
      </div>
      <p v-if="!quote.discountLines.length">本方案未使用预约优惠券。</p>
      <p><strong>按预约约定线下结算 {{ priceCents(quote.totalCents) }}</strong>，线上预约不收费。</p>
    </template>
    <template v-else><strong>纯订位 · 消费金额到店确定</strong><p>未选择固定套餐，不使用预约优惠券抵扣；线上预约不收费。</p></template>
  </section>
</template>
