<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { AlertCircle, Loader2, MessageCircle, ShieldCheck, Smartphone } from '@lucide/vue';
import { sendLoginCode } from '../api/citycopilot';
import { login } from '../composables/auth';
import { getErrorMessage } from '../utils/errors';

const router = useRouter();
const route = useRoute();

const form = reactive({
  phone: '',
  code: '',
});
const sendingCode = ref(false);
const submitting = ref(false);
const cooldown = ref(0);
const errorMessage = ref('');

const canSendCode = computed(() => /^1\d{10}$/.test(form.phone) && cooldown.value === 0 && !sendingCode.value);
const canSubmit = computed(() => /^1\d{10}$/.test(form.phone) && form.code.trim().length >= 4 && !submitting.value);

function startCooldown() {
  cooldown.value = 60;
  const timer = window.setInterval(() => {
    cooldown.value -= 1;
    if (cooldown.value <= 0) {
      window.clearInterval(timer);
    }
  }, 1000);
}

async function handleSendCode() {
  if (!canSendCode.value) {
    return;
  }
  errorMessage.value = '';
  sendingCode.value = true;
  try {
    await sendLoginCode(form.phone);
    startCooldown();
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '验证码发送失败');
  } finally {
    sendingCode.value = false;
  }
}

async function handleLogin() {
  if (!canSubmit.value) {
    return;
  }
  errorMessage.value = '';
  submitting.value = true;
  try {
    await login({
      phone: form.phone,
      code: form.code.trim(),
    });
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
    await router.replace(redirect);
  } catch (error) {
    errorMessage.value = getErrorMessage(error, '登录失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <section class="auth-layout">
    <div class="auth-art">
      <p class="eyebrow">Member access</p>
      <h1>用一条短信，把本地生活的动作接回用户身份。</h1>
      <div class="auth-promises" aria-label="登录能力">
        <span><ShieldCheck :size="18" aria-hidden="true" /> token 续期</span>
        <span><MessageCircle :size="18" aria-hidden="true" /> 验证码登录</span>
      </div>
    </div>

    <form class="login-panel" @submit.prevent="handleLogin">
      <div class="section-heading">
        <Smartphone :size="19" aria-hidden="true" />
        <h2>手机登录</h2>
      </div>

      <label class="field">
        <span>手机号</span>
        <input v-model.trim="form.phone" inputmode="tel" maxlength="11" placeholder="请输入 11 位手机号" />
      </label>

      <label class="field code-field">
        <span>验证码</span>
        <div>
          <input v-model.trim="form.code" inputmode="numeric" maxlength="6" placeholder="请输入验证码" />
          <button type="button" :disabled="!canSendCode" @click="handleSendCode">
            <Loader2 v-if="sendingCode" class="spin" :size="16" aria-hidden="true" />
            {{ cooldown > 0 ? `${cooldown}s` : '发送' }}
          </button>
        </div>
      </label>

      <p v-if="errorMessage" class="form-error">
        <AlertCircle :size="17" aria-hidden="true" />
        {{ errorMessage }}
      </p>

      <button class="primary-action" type="submit" :disabled="!canSubmit">
        <Loader2 v-if="submitting" class="spin" :size="17" aria-hidden="true" />
        {{ submitting ? '登录中' : '登录' }}
      </button>
    </form>
  </section>
</template>
