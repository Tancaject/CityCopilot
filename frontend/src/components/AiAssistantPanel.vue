<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bot, ExternalLink, Loader2, MapPin, Send, Sparkles, Store, Trash2, X } from '@lucide/vue';
import { retryGeoBatchJob, sendAiChat, startGeoBatchGeocode, syncShopGeoIndex } from '../api/hmdp';
import { authState } from '../composables/auth';
import { locationState, requestCurrentLocation } from '../composables/location';
import { formatDistance, formatPrice, formatScore } from '../utils/assets';
import { hasPermission } from '../utils/permissions';
import type { AiAssistantAction, AiAssistantShop, AiChatMessage, GeoBatchJob } from '../types';

interface ChatMessage {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  shops?: AiAssistantShop[];
  actions?: AiAssistantAction[];
}

const route = useRoute();
const router = useRouter();
const defaultCity = import.meta.env.VITE_DEFAULT_CITY || '杭州';

const open = ref(false);
const input = ref('');
const loading = ref(false);
const actionLoading = ref('');
const errorMessage = ref('');
const scrollRef = ref<HTMLElement | null>(null);
let messageId = 0;
const storageKey = 'hmdp:ai-assistant';

const welcomeMessage: ChatMessage = {
  id: ++messageId,
  role: 'assistant',
  text: '你可以问我附近推荐、预算筛选、有券店铺，也可以在校准台里问缺坐标和批量失败原因。',
  actions: [
    { label: '附近有券店铺', type: 'prompt', query: { prompt: '附近有没有人均 100 以内、有优惠券的店？' } },
    { label: '高分美食', type: 'prompt', query: { prompt: '推荐几家评分高的美食店' } },
  ],
};

const conversationId = ref(loadConversationId());
const messages = ref<ChatMessage[]>(loadMessages());

const canReadGeo = computed(() => hasPermission(authState.user, 'geo:read'));
const canWriteGeo = computed(() => hasPermission(authState.user, 'geo:write'));
const scene = computed(() => (String(route.name || '').includes('admin') ? 'admin' : 'user'));
const panelTitle = computed(() => (scene.value === 'admin' ? '运营 AI 助手' : '智能 AI 助手'));
const currentShopId = computed(() => {
  if (route.name !== 'shop-detail') {
    return undefined;
  }
  const value = Number(route.params.id);
  return Number.isFinite(value) && value > 0 ? value : undefined;
});
const quickPrompts = computed(() => {
  if (scene.value === 'admin' && canReadGeo.value) {
    return ['还有哪些店缺坐标？', '最近批量任务失败原因是什么？', '下一批校准建议怎么做？'];
  }
  if (currentShopId.value) {
    return ['这家店怎么样？', '这家店有什么优惠券？', '附近还有类似的吗？'];
  }
  return ['附近适合一个人吃的店', '人均 100 以内有券的店', '评分高的咖啡店'];
});

watch(
  messages,
  () => {
    persistConversation();
  },
  { deep: true },
);

function loadMessages() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || '{}');
    if (Array.isArray(saved.messages) && saved.messages.length) {
      const restored = (saved.messages as unknown[]).slice(-20).filter(isChatMessage);
      if (restored.length) {
        messageId = Math.max(messageId, ...restored.map((message) => message.id));
        return restored;
      }
    }
  } catch {
    // 忽略损坏的本地会话缓存，重新开始一轮对话。
  }
  return [welcomeMessage];
}

function loadConversationId() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || '{}');
    if (typeof saved.conversationId === 'string' && saved.conversationId) {
      return saved.conversationId;
    }
  } catch {
    // ignore
  }
  return createConversationId();
}

function persistConversation() {
  sessionStorage.setItem(
    storageKey,
    JSON.stringify({
      conversationId: conversationId.value,
      messages: messages.value.slice(-20),
    }),
  );
}

function createConversationId() {
  if (crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function isChatMessage(value: unknown): value is ChatMessage {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const message = value as ChatMessage;
  return typeof message.id === 'number' && (message.role === 'user' || message.role === 'assistant') && typeof message.text === 'string';
}

function resetConversation() {
  messageId = 0;
  conversationId.value = createConversationId();
  messages.value = [{ ...welcomeMessage, id: ++messageId }];
  errorMessage.value = '';
  nextTick(scrollToBottom);
}

function recentHistory(): AiChatMessage[] {
  return messages.value
    .filter((message) => message.role === 'user' || message.role === 'assistant')
    .slice(-8)
    .map((message) => ({
      role: message.role,
      text: message.text,
    }));
}

function toggleOpen() {
  open.value = !open.value;
  if (open.value) {
    nextTick(scrollToBottom);
  }
}

async function sendPrompt(prompt = input.value) {
  const text = prompt.trim();
  if (!text || loading.value) {
    return;
  }
  input.value = '';
  errorMessage.value = '';
  const history = recentHistory();
  messages.value.push({ id: ++messageId, role: 'user', text });
  loading.value = true;
  await nextTick(scrollToBottom);
  try {
    if (text.includes('附近') && !locationState.point && locationState.status !== 'denied') {
      await requestCurrentLocation();
    }
    const point = locationState.point;
    const response = await sendAiChat({
      message: text,
      scene: scene.value,
      conversationId: conversationId.value,
      routeName: String(route.name || ''),
      routePath: route.fullPath,
      currentShopId: currentShopId.value,
      x: point?.x,
      y: point?.y,
      history,
    });
    if (response.conversationId) {
      conversationId.value = response.conversationId;
    }
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: response.message,
      shops: response.shops || [],
      actions: response.actions || [],
    });
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'AI 助手暂时不可用';
  } finally {
    loading.value = false;
    await nextTick(scrollToBottom);
  }
}

async function useLocationPrompt(prompt: string) {
  if (!locationState.point) {
    await requestCurrentLocation();
  }
  await sendPrompt(prompt);
}

async function handleAction(action: AiAssistantAction) {
  if (action.type === 'prompt' && typeof action.query?.prompt === 'string') {
    await sendPrompt(action.query.prompt);
    return;
  }
  if (action.type === 'locate' && typeof action.query?.prompt === 'string') {
    await useLocationPrompt(action.query.prompt);
    return;
  }
  if (action.type === 'geo_batch_start') {
    await handleGeoBatchStart(action);
    return;
  }
  if (action.type === 'geo_index_sync') {
    await handleGeoIndexSync(action);
    return;
  }
  if (action.type === 'geo_batch_retry') {
    await handleGeoBatchRetry(action);
    return;
  }
  if (action.routeName) {
    await router.push({
      name: action.routeName,
      query: normalizeRouteQuery(action.query),
    });
    open.value = false;
    return;
  }
  if (action.routePath) {
    await router.push(action.routePath);
    open.value = false;
  }
}

async function handleGeoBatchStart(action: AiAssistantAction) {
  if (!canWriteGeo.value || !window.confirm('确认创建缺坐标批量任务？')) {
    return;
  }
  actionLoading.value = action.label;
  errorMessage.value = '';
  try {
    const query = action.query || {};
    const job = await startGeoBatchGeocode({
      city: stringValue(query.city, defaultCity),
      keyword: optionalString(query.keyword),
      verified: booleanValue(query.verified, false),
      onlyMissing: booleanValue(query.onlyMissing, true),
      limit: numberValue(query.limit, 20),
    });
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: job.total ? `批量任务已创建：${job.processed}/${job.total}，当前状态 ${job.status}。` : '没有找到需要批量处理的店铺。',
      actions: [{ label: '打开地理校准台', type: 'route', routeName: 'geo-shops-admin' }],
    });
    notifyGeoBatch(job);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '批量任务创建失败';
  } finally {
    actionLoading.value = '';
    await nextTick(scrollToBottom);
  }
}

async function handleGeoIndexSync(action: AiAssistantAction) {
  if (!canWriteGeo.value || !window.confirm('确认重建 Redis GEO 索引？')) {
    return;
  }
  actionLoading.value = action.label;
  errorMessage.value = '';
  try {
    const count = await syncShopGeoIndex();
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: `Redis GEO 索引已重建，共同步 ${count || 0} 家已验证店铺。`,
      actions: [{ label: '打开地理校准台', type: 'route', routeName: 'geo-shops-admin' }],
    });
    notifyGeoIndex(count || 0);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'GEO 索引重建失败';
  } finally {
    actionLoading.value = '';
    await nextTick(scrollToBottom);
  }
}

async function handleGeoBatchRetry(action: AiAssistantAction) {
  if (!canWriteGeo.value || !window.confirm('确认重试最近失败的批量任务？')) {
    return;
  }
  const jobId = optionalString(action.query?.jobId);
  if (!jobId) {
    errorMessage.value = '缺少批量任务 ID';
    return;
  }
  actionLoading.value = action.label;
  errorMessage.value = '';
  try {
    const job = await retryGeoBatchJob(jobId, booleanValue(action.query?.verified, false), numberValue(action.query?.limit, 20));
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: `失败明细重试任务已创建：${job.processed}/${job.total}，当前状态 ${job.status}。`,
      actions: [{ label: '打开地理校准台', type: 'route', routeName: 'geo-shops-admin', query: { focus: 'failed-jobs' } }],
    });
    notifyGeoBatch(job);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '批量失败重试失败';
  } finally {
    actionLoading.value = '';
    await nextTick(scrollToBottom);
  }
}

async function openShop(shop: AiAssistantShop) {
  await router.push({ name: 'shop-detail', params: { id: shop.id } });
  open.value = false;
}

function scrollToBottom() {
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight;
  }
}

function normalizeRouteQuery(query?: Record<string, unknown>) {
  if (!query) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(query)
      .filter(([, value]) => value !== undefined && value !== null)
      .map(([key, value]) => [key, String(value)]),
  );
}

function stringValue(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value : fallback;
}

function optionalString(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function booleanValue(value: unknown, fallback: boolean) {
  return typeof value === 'boolean' ? value : fallback;
}

function numberValue(value: unknown, fallback: number) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function notifyGeoBatch(job: GeoBatchJob) {
  window.dispatchEvent(new CustomEvent('hmdp:geo-batch-updated', { detail: job }));
}

function notifyGeoIndex(count: number) {
  window.dispatchEvent(new CustomEvent('hmdp:geo-index-synced', { detail: count }));
}
</script>

<template>
  <div class="ai-assistant">
    <button class="ai-fab" type="button" :aria-expanded="open" aria-label="打开智能 AI 助手" @click="toggleOpen">
      <Sparkles :size="20" aria-hidden="true" />
      <span>AI</span>
    </button>

    <section v-if="open" class="ai-panel" aria-label="智能 AI 助手">
      <header class="ai-panel-head">
        <div>
          <p class="eyebrow">{{ scene === 'admin' ? 'Operations copilot' : 'Local life copilot' }}</p>
          <h2>{{ panelTitle }}</h2>
        </div>
        <div class="ai-panel-tools">
          <button type="button" aria-label="清空对话" @click="resetConversation">
            <Trash2 :size="17" aria-hidden="true" />
          </button>
          <button type="button" aria-label="关闭 AI 助手" @click="open = false">
            <X :size="18" aria-hidden="true" />
          </button>
        </div>
      </header>

      <div ref="scrollRef" class="ai-thread">
        <article v-for="message in messages" :key="message.id" class="ai-message" :class="message.role">
          <div class="ai-avatar">
            <Bot v-if="message.role === 'assistant'" :size="16" aria-hidden="true" />
            <span v-else>你</span>
          </div>
          <div class="ai-bubble">
            <p>{{ message.text }}</p>

            <div v-if="message.shops?.length" class="ai-shop-results">
              <button v-for="shop in message.shops" :key="shop.id" class="ai-shop-card" type="button" @click="openShop(shop)">
                <span>
                  <strong>{{ shop.name }}</strong>
                  <small>{{ [shop.area, shop.address].filter(Boolean).join(' · ') || '地址待补充' }}</small>
                </span>
                <span class="ai-shop-meta">
                  <small>{{ formatPrice(shop.avgPrice) }}</small>
                  <small>{{ formatScore(shop.score) }}</small>
                  <small v-if="shop.distance">{{ formatDistance(shop.distance) }}</small>
                </span>
                <em>{{ shop.reason || '综合推荐' }}</em>
                <span v-if="shop.voucherHighlights?.length" class="ai-voucher-tags">
                  <b v-for="voucher in shop.voucherHighlights" :key="voucher">{{ voucher }}</b>
                </span>
                <i v-else-if="shop.voucherCount">{{ shop.voucherCount }} 张券</i>
              </button>
            </div>

            <div v-if="message.actions?.length" class="ai-actions">
              <button v-for="action in message.actions" :key="action.label" type="button" :disabled="Boolean(actionLoading)" @click="handleAction(action)">
                <Loader2 v-if="actionLoading === action.label" class="spin" :size="14" aria-hidden="true" />
                <ExternalLink v-if="action.type === 'route'" :size="14" aria-hidden="true" />
                <Sparkles v-else-if="actionLoading !== action.label" :size="14" aria-hidden="true" />
                {{ action.label }}
              </button>
            </div>
          </div>
        </article>

        <article v-if="loading" class="ai-message assistant">
          <div class="ai-avatar">
            <Bot :size="16" aria-hidden="true" />
          </div>
          <div class="ai-bubble loading">
            <Loader2 class="spin" :size="17" aria-hidden="true" />
            正在读取真实业务数据
          </div>
        </article>
      </div>

      <p v-if="errorMessage" class="ai-error">{{ errorMessage }}</p>

      <div class="ai-quick-prompts">
        <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="prompt.includes('附近') ? useLocationPrompt(prompt) : sendPrompt(prompt)">
          <MapPin v-if="prompt.includes('附近')" :size="14" aria-hidden="true" />
          <Store v-else :size="14" aria-hidden="true" />
          {{ prompt }}
        </button>
      </div>

      <form class="ai-input" @submit.prevent="sendPrompt()">
        <input v-model="input" type="text" placeholder="问我找店、优惠券、校准任务..." />
        <button type="submit" :disabled="loading || !input.trim()" aria-label="发送">
          <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
          <Send v-else :size="17" aria-hidden="true" />
        </button>
      </form>
    </section>
  </div>
</template>
