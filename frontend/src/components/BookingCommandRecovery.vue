<script setup lang="ts">
import { ref } from 'vue';
import { pendingBookingCommand, recoverBookingCommand, retryBookingCommand, type BookingMutation } from '../api/booking';
import { getErrorMessage } from '../utils/errors';
const emit = defineEmits<{ (event: 'resolved', result: BookingMutation): void }>();
const pending = ref(pendingBookingCommand());
const busy = ref(false);
const error = ref('');
async function recover(retry: boolean) {
  busy.value = true;
  error.value = '';
  try {
    const result = await (retry ? retryBookingCommand() : recoverBookingCommand());
    pending.value = pendingBookingCommand();
    emit('resolved', result);
  } catch (failure) {
    error.value = getErrorMessage(failure, '尚未查到最终结果，请稍后查询，或按原请求重试');
    pending.value = pendingBookingCommand();
  } finally { busy.value = false; }
}
</script>
<template>
  <section v-if="pending" class="booking-pending" role="status">
    <strong>上次「{{ pending.action }}」的结果待确认</strong>
    <p>请先恢复这次操作，避免重复预约。重试会使用原操作的全部条件。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div class="booking-actions">
      <button type="button" :disabled="busy" @click="recover(false)">查询操作结果</button>
      <button type="button" :disabled="busy" @click="recover(true)">按原请求重试</button>
    </div>
  </section>
</template>
