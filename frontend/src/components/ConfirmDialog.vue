<script setup lang="ts">
import { computed } from 'vue';
import { AlertTriangle, Loader2, X } from '@lucide/vue';

const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    message?: string;
    detail?: string;
    confirmText?: string;
    cancelText?: string;
    tone?: 'default' | 'warning' | 'danger';
    busy?: boolean;
  }>(),
  {
    message: '',
    detail: '',
    confirmText: '确认',
    cancelText: '取消',
    tone: 'default',
    busy: false,
  },
);

const emit = defineEmits<{
  (event: 'cancel'): void;
  (event: 'confirm'): void;
}>();

const dialogClass = computed(() => [`confirm-dialog-${props.tone}`, { busy: props.busy }]);

function requestCancel() {
  if (!props.busy) {
    emit('cancel');
  }
}

function requestConfirm() {
  if (!props.busy) {
    emit('confirm');
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="confirm-dialog-backdrop" @click.self="requestCancel">
      <section class="confirm-dialog" :class="dialogClass" role="dialog" aria-modal="true" :aria-label="title">
        <header>
          <span class="confirm-dialog-icon">
            <AlertTriangle :size="19" aria-hidden="true" />
          </span>
          <button type="button" :disabled="busy" aria-label="关闭确认弹窗" @click="requestCancel">
            <X :size="18" aria-hidden="true" />
          </button>
        </header>

        <div class="confirm-dialog-copy">
          <h2>{{ title }}</h2>
          <p v-if="message">{{ message }}</p>
          <small v-if="detail">{{ detail }}</small>
        </div>

        <footer>
          <button type="button" :disabled="busy" @click="requestCancel">{{ cancelText }}</button>
          <button type="button" class="confirm-dialog-primary" :disabled="busy" @click="requestConfirm">
            <Loader2 v-if="busy" class="spin" :size="16" aria-hidden="true" />
            {{ confirmText }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>
