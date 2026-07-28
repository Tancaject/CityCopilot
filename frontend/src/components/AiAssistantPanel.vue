<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bot, ChevronRight, CircleAlert, CircleCheck, ExternalLink, Loader2, MapPin, Send, Sparkles, Store, Trash2, X } from '@lucide/vue';
import { cancelAgentAction, confirmAgentAction, retryGeoBatchJob, startGeoBatchGeocode, streamAgentChat, syncShopGeoIndex } from '../api/citycopilot';
import ConfirmDialog from './ConfirmDialog.vue';
import { authState } from '../composables/auth';
import { locationState } from '../composables/location';
import { formatDistance, formatPrice, formatScore } from '../utils/assets';
import { hasPermission } from '../utils/permissions';
import type { AgentToolEvent, AiAssistantAction, AiAssistantShop, AiChatRequest, GeoBatchJob } from '../types';

interface ChatMessage {
  id: number;
  role: 'user' | 'assistant';
  text: string;
  shops?: AiAssistantShop[];
  actions?: AiAssistantAction[];
  traceId?: string;
  toolEvents?: AgentToolEvent[];
  confirmation?: AgentConfirmation;
}

interface AgentConfirmation {
  pendingId: string;
  toolName: string;
  summary: string;
  args?: Record<string, unknown>;
  expiresAt?: string;
  state: 'pending' | 'confirming' | 'executed' | 'cancelled' | 'failed';
}

const route = useRoute();
const router = useRouter();
const defaultCity = import.meta.env.VITE_DEFAULT_CITY || '杭州';

const open = ref(false);
const input = ref('');
const loading = ref(false);
const actionLoading = ref('');
const pendingAction = ref<AiAssistantAction | null>(null);
const errorMessage = ref('');
const scrollRef = ref<HTMLElement | null>(null);
const streamingMessageId = ref<number | null>(null);
const expandedTools = reactive<Set<string>>(new Set());
let messageId = 0;
const storageKey = 'citycopilot:ai-assistant';

interface ToolStep {
  index: number;
  toolName: string;
  status: 'running' | 'done' | 'error';
  args?: Record<string, unknown>;
  observation?: unknown;
}

function isStreaming(message: ChatMessage) {
  return streamingMessageId.value === message.id;
}

function groupToolSteps(message: ChatMessage): ToolStep[] {
  const events = message.toolEvents || [];
  const byIndex = new Map<number, ToolStep>();
  events.forEach((event, fallbackIdx) => {
    const idx = typeof event.index === 'number' ? event.index : fallbackIdx;
    const existing = byIndex.get(idx) ?? {
      index: idx,
      toolName: event.toolName || 'tool',
      status: 'running' as const,
      args: undefined,
      observation: undefined,
    };
    if (event.toolName) existing.toolName = event.toolName;
    if (event.phase === 'call') {
      existing.args = event.args;
    } else if (event.phase === 'result') {
      existing.observation = event.observation;
      const obs = event.observation as { errorCode?: unknown } | null | undefined;
      existing.status = obs && typeof obs === 'object' && 'errorCode' in obs && obs.errorCode ? 'error' : 'done';
    }
    byIndex.set(idx, existing);
  });
  return Array.from(byIndex.values()).sort((a, b) => a.index - b.index);
}

function toolStepKey(messageId: number, step: ToolStep) {
  return `${messageId}:${step.index}`;
}

function toggleToolStep(messageId: number, step: ToolStep) {
  const key = toolStepKey(messageId, step);
  if (expandedTools.has(key)) {
    expandedTools.delete(key);
  } else {
    expandedTools.add(key);
  }
}

function isToolExpanded(messageId: number, step: ToolStep) {
  return expandedTools.has(toolStepKey(messageId, step));
}

function formatToolPayload(value: unknown): string {
  if (value === undefined || value === null) return '—';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function shortTrace(trace?: string) {
  if (!trace) return '';
  return trace.length > 8 ? `${trace.slice(0, 4)}…${trace.slice(-4)}` : trace;
}

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
const conversationOwnerId = ref(loadConversationOwnerId());

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
const pendingActionDialog = computed(() => {
  const action = pendingAction.value;
  if (action?.type === 'geo_index_sync') {
    return {
      title: '确认重建 GEO 索引',
      message: '系统会重新同步已验证店铺到 Redis GEO，附近推荐会使用新的索引结果。',
      confirmText: '重建索引',
      busy: Boolean(actionLoading.value),
    };
  }
  if (action?.type === 'geo_batch_retry') {
    return {
      title: '确认重试失败任务',
      message: '系统会基于最近失败明细创建新的批量校准任务，请确认当前高德服务状态可用。',
      confirmText: '重试任务',
      busy: Boolean(actionLoading.value),
    };
  }
  return {
    title: '确认创建批量任务',
    message: '系统会为缺坐标或未验证店铺创建后台校准任务，执行过程会写入审计日志。',
    confirmText: '创建任务',
    busy: Boolean(actionLoading.value),
  };
});

watch(
  messages,
  () => {
    persistConversation();
  },
  { deep: true },
);

watch(
  () => [authState.initialized, authState.user?.id ?? null] as const,
  ([initialized, userId]) => {
    if (!initialized) return;
    const ownerId = userId == null ? 'anonymous' : `user:${userId}`;
    if (conversationOwnerId.value !== ownerId) {
      conversationOwnerId.value = ownerId;
      resetConversation();
      return;
    }
    persistConversation();
  },
  { immediate: true },
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

function loadConversationOwnerId() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || '{}');
    return typeof saved.ownerId === 'string' && saved.ownerId ? saved.ownerId : null;
  } catch {
    return null;
  }
}

function persistConversation() {
  sessionStorage.setItem(
    storageKey,
    JSON.stringify({
      ownerId: conversationOwnerId.value,
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
  messages.value.push({ id: ++messageId, role: 'user', text });
  const assistantMessage: ChatMessage = {
    id: ++messageId,
    role: 'assistant',
    text: 'Agent 正在规划工具调用...',
    toolEvents: [],
  };
  messages.value.push(assistantMessage);
  loading.value = true;
  streamingMessageId.value = assistantMessage.id;
  await nextTick(scrollToBottom);
  try {
    const point = locationState.point;
    const payload: AiChatRequest = {
      message: text,
      scene: scene.value,
      conversationId: conversationId.value,
      routeName: String(route.name || ''),
      routePath: route.fullPath,
      currentShopId: currentShopId.value,
      x: point?.x,
      y: point?.y,
    };
    await streamAgentChat(payload, (event) => handleAgentEvent(event.event, event.data, assistantMessage));
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'AI assistant is unavailable';
    assistantMessage.text = 'AI assistant is unavailable.';
  } finally {
    loading.value = false;
    streamingMessageId.value = null;
    await nextTick(scrollToBottom);
  }
}

function handleAgentEvent(event: string, data: unknown, assistantMessage: ChatMessage) {
  if (event === 'traceId' && isRecord(data)) {
    assistantMessage.traceId = stringValue(data.traceId, '');
    return;
  }
  if (event === 'tool' && isRecord(data)) {
    assistantMessage.toolEvents = [...(assistantMessage.toolEvents || []), data as AgentToolEvent];
    nextTick(scrollToBottom);
    return;
  }
  if (event === 'confirmation' && isRecord(data)) {
    assistantMessage.confirmation = {
      pendingId: stringValue(data.pendingId, ''),
      toolName: stringValue(data.toolName, 'tool'),
      summary: stringValue(data.summary, '请确认是否执行该高风险操作'),
      args: isRecord(data.args) ? data.args : undefined,
      expiresAt: stringValue(data.expiresAt, ''),
      state: 'pending',
    };
    assistantMessage.text = '高风险操作已暂停，等待你的确认。';
    nextTick(scrollToBottom);
    return;
  }
  if (event === 'error' && isRecord(data)) {
    errorMessage.value = stringValue(data.message, 'Agent call failed');
    return;
  }
  if (event === 'delta' && isRecord(data)) {
    const delta = stringValue(data.content, '');
    if (delta) {
      if (assistantMessage.text === 'Agent 正在规划工具调用...') {
        assistantMessage.text = '';
      }
      assistantMessage.text += delta;
      nextTick(scrollToBottom);
    }
    return;
  }
  if (event === 'final' && isRecord(data)) {
    assistantMessage.text = stringValue(data.message, assistantMessage.text);
  }
}

async function confirmPending(message: ChatMessage) {
  const confirmation = message.confirmation;
  if (!confirmation || confirmation.state !== 'pending') return;
  confirmation.state = 'confirming';
  try {
    const result = await confirmAgentAction(confirmation.pendingId);
    confirmation.state = result.ok === true ? 'executed' : 'failed';
    message.text = result.ok === true ? '操作已确认并执行成功。' : '操作已确认，但执行失败。';
  } catch (error) {
    confirmation.state = 'failed';
    errorMessage.value = error instanceof Error ? error.message : '确认失败';
  }
}

async function cancelPending(message: ChatMessage) {
  const confirmation = message.confirmation;
  if (!confirmation || confirmation.state !== 'pending') return;
  try {
    await cancelAgentAction(confirmation.pendingId);
    confirmation.state = 'cancelled';
    message.text = '已取消本次高风险操作。';
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '取消失败';
  }
}

async function useLocationPrompt(prompt: string) {
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
    requestGeoAction(action);
    return;
  }
  if (action.type === 'geo_index_sync') {
    requestGeoAction(action);
    return;
  }
  if (action.type === 'geo_batch_retry') {
    requestGeoAction(action);
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

function requestGeoAction(action: AiAssistantAction) {
  if (!canWriteGeo.value) {
    errorMessage.value = '需要 geo:write 权限才能执行地理校准写操作';
    return;
  }
  pendingAction.value = action;
}

async function confirmPendingAction() {
  const action = pendingAction.value;
  if (!action || actionLoading.value) {
    return;
  }
  if (action.type === 'geo_batch_start') {
    await handleGeoBatchStart(action);
  } else if (action.type === 'geo_index_sync') {
    await handleGeoIndexSync(action);
  } else if (action.type === 'geo_batch_retry') {
    await handleGeoBatchRetry(action);
  }
  pendingAction.value = null;
}

async function handleGeoBatchStart(action: AiAssistantAction) {
  if (!canWriteGeo.value) {
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
  if (!canWriteGeo.value) {
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
  if (!canWriteGeo.value) {
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object';
}

function notifyGeoBatch(job: GeoBatchJob) {
  window.dispatchEvent(new CustomEvent('citycopilot:geo-batch-updated', { detail: job }));
}

function notifyGeoIndex(count: number) {
  window.dispatchEvent(new CustomEvent('citycopilot:geo-index-synced', { detail: count }));
}
</script>

<template>
  <div class="ai-assistant" :data-open="open">
    <button class="ai-fab" type="button" :aria-expanded="open" aria-label="打开智能 AI 助手" @click="toggleOpen">
      <span class="ai-fab-glow" aria-hidden="true" />
      <span class="ai-fab-core">
        <Sparkles :size="18" aria-hidden="true" />
      </span>
      <span class="ai-fab-label">
        <small>Copilot</small>
        <strong>{{ scene === 'admin' ? 'Ops' : 'City' }}</strong>
      </span>
    </button>

    <section v-if="open" class="ai-panel" aria-label="智能 AI 助手">
      <header class="ai-panel-head">
        <div class="ai-panel-head-copy">
          <p class="eyebrow">
            <span class="ai-status-dot" :data-state="loading ? 'live' : 'idle'" aria-hidden="true" />
            {{ scene === 'admin' ? 'Operations copilot' : 'Local life copilot' }}
            <code v-if="conversationId" class="ai-session-id mono">{{ shortTrace(conversationId) }}</code>
          </p>
          <h2>{{ panelTitle }}</h2>
          <span>{{ scene === 'admin' ? '读取任务、审计和校准上下文' : '按当前位置、店铺和优惠缩短决策路径' }}</span>
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
        <article v-for="message in messages" :key="message.id" class="ai-message" :class="message.role" :data-streaming="isStreaming(message)">
          <div class="ai-avatar">
            <Bot v-if="message.role === 'assistant'" :size="16" aria-hidden="true" />
            <span v-else>你</span>
          </div>
          <div class="ai-bubble">
            <p>
              <span class="ai-text">{{ message.text }}</span>
              <span v-if="isStreaming(message) && message.role === 'assistant'" class="ai-cursor" aria-hidden="true" />
            </p>

            <div v-if="message.traceId || message.toolEvents?.length" class="ai-agent-trace">
              <small v-if="message.traceId" class="mono">trace · {{ shortTrace(message.traceId) }}</small>
              <ol class="ai-tool-timeline">
                <li
                  v-for="step in groupToolSteps(message)"
                  :key="`${message.id}-${step.index}`"
                  class="ai-tool-step"
                  :data-status="step.status"
                  :data-expanded="isToolExpanded(message.id, step)"
                >
                  <button class="ai-tool-step-head" type="button" @click="toggleToolStep(message.id, step)">
                    <span class="ai-tool-rail" aria-hidden="true">
                      <Loader2 v-if="step.status === 'running'" class="spin" :size="13" />
                      <CircleAlert v-else-if="step.status === 'error'" :size="13" />
                      <CircleCheck v-else :size="13" />
                    </span>
                    <span class="ai-tool-meta">
                      <strong class="mono">{{ step.toolName }}</strong>
                      <small>{{ step.status === 'running' ? '调用中' : step.status === 'error' ? '失败' : '已返回' }}</small>
                    </span>
                    <ChevronRight class="ai-tool-chev" :size="14" aria-hidden="true" />
                  </button>
                  <div v-show="isToolExpanded(message.id, step)" class="ai-tool-payload">
                    <div v-if="step.args" class="ai-tool-payload-block">
                      <span class="ai-tool-tag">args</span>
                      <pre class="mono">{{ formatToolPayload(step.args) }}</pre>
                    </div>
                    <div v-if="step.observation !== undefined" class="ai-tool-payload-block">
                      <span class="ai-tool-tag" data-variant="result">observation</span>
                      <pre class="mono">{{ formatToolPayload(step.observation) }}</pre>
                    </div>
                  </div>
                </li>
              </ol>
            </div>

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

            <section v-if="message.confirmation" class="ai-confirmation" :data-state="message.confirmation.state">
              <div>
                <small>高风险操作确认</small>
                <strong class="mono">{{ message.confirmation.toolName }}</strong>
                <p>{{ message.confirmation.summary }}</p>
                <pre v-if="message.confirmation.args" class="mono">{{ formatToolPayload(message.confirmation.args) }}</pre>
              </div>
              <div v-if="message.confirmation.state === 'pending' || message.confirmation.state === 'confirming'" class="ai-confirmation-actions">
                <button type="button" :disabled="message.confirmation.state === 'confirming'" @click="cancelPending(message)">取消</button>
                <button type="button" class="primary" :disabled="message.confirmation.state === 'confirming'" @click="confirmPending(message)">
                  <Loader2 v-if="message.confirmation.state === 'confirming'" class="spin" :size="14" />
                  确认执行
                </button>
              </div>
              <small v-else>{{ message.confirmation.state === 'executed' ? '已执行' : message.confirmation.state === 'cancelled' ? '已取消' : '执行失败' }}</small>
            </section>
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

    <ConfirmDialog
      :open="Boolean(pendingAction)"
      :title="pendingActionDialog.title"
      :message="pendingActionDialog.message"
      :confirm-text="pendingActionDialog.confirmText"
      tone="warning"
      :busy="pendingActionDialog.busy"
      @cancel="pendingAction = null"
      @confirm="confirmPendingAction"
    />
  </div>
</template>

<style>
/* ============================================================
   AI Cockpit · 2026 Refined Warm Light
   Scene-adaptive: consumer = warm paper, admin/ops = deep forest.
   Driven by body[data-scene] set in App.vue.
   Overrides global .ai-* rules from styles.css.
   ============================================================ */

.ai-assistant {
  /* Cockpit-local design tokens — flipped by body[data-scene="admin"] */
  --cp-canvas: oklch(98% 0.020 70);
  --cp-card: #ffffff;
  --cp-raised: oklch(96% 0.022 72);
  --cp-inset: oklch(94% 0.025 72);
  --cp-text: oklch(22% 0.018 60);
  --cp-text-sec: oklch(40% 0.016 55);
  --cp-text-mu: oklch(56% 0.014 55);
  --cp-hairline: oklch(22% 0.018 60 / 0.10);
  --cp-hairline-strong: oklch(22% 0.018 60 / 0.20);
  --cp-payload: oklch(96% 0.014 70);
  --cp-shadow: 0 2px 0 oklch(100% 0 0 / 0.6) inset, 0 32px 80px oklch(40% 0.04 60 / 0.22);
  --cp-edge-glow: 0 0 0 1px oklch(70% 0.18 42 / 0.18);

  position: fixed;
  right: max(20px, env(safe-area-inset-right, 20px));
  bottom: max(20px, env(safe-area-inset-bottom, 20px));
  z-index: 40;
}

body[data-scene="admin"] .ai-assistant {
  --cp-canvas: oklch(13% 0.012 165);
  --cp-card: oklch(17% 0.014 165);
  --cp-raised: oklch(22% 0.016 165);
  --cp-inset: oklch(11% 0.012 165);
  --cp-text: oklch(98% 0.012 80);
  --cp-text-sec: oklch(76% 0.014 80);
  --cp-text-mu: oklch(60% 0.014 80);
  --cp-hairline: oklch(100% 0 0 / 0.08);
  --cp-hairline-strong: oklch(100% 0 0 / 0.18);
  --cp-payload: oklch(0% 0 0 / 0.32);
  --cp-shadow: 0 1px 0 oklch(100% 0 0 / 0.04) inset, 0 40px 100px oklch(0% 0 0 / 0.55);
  --cp-edge-glow: 0 0 0 1px oklch(70% 0.18 42 / 0.22);
}

/* ---- FAB ---- */
.ai-assistant .ai-fab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 0 18px 0 12px;
  border: 1px solid oklch(70% 0.18 42 / 0.35);
  border-radius: var(--radius-pill);
  background: linear-gradient(135deg, #ffffff, oklch(96% 0.022 72));
  color: var(--cp-text);
  font-family: var(--font-sans);
  font-weight: 500;
  letter-spacing: -0.01em;
  box-shadow:
    inset 0 1px 0 oklch(100% 0 0 / 0.9),
    0 14px 32px oklch(70% 0.18 42 / 0.22),
    0 0 0 1px oklch(70% 0.18 42 / 0.18);
  cursor: pointer;
  transition: transform var(--dur) var(--ease-out-expo), box-shadow var(--dur) var(--ease-out-expo);
  isolation: isolate;
}

body[data-scene="admin"] .ai-assistant .ai-fab {
  background: linear-gradient(135deg, oklch(17% 0.014 165), oklch(13% 0.012 165));
  border-color: oklch(70% 0.18 42 / 0.4);
  color: oklch(98% 0.012 80);
  box-shadow:
    inset 0 1px 0 oklch(100% 0 0 / 0.06),
    0 16px 38px oklch(0% 0 0 / 0.45),
    0 0 0 1px oklch(0% 0 0 / 0.4);
}

.ai-assistant .ai-fab:hover {
  transform: translateY(-2px);
  box-shadow:
    inset 0 1px 0 oklch(100% 0 0 / 0.9),
    0 24px 56px oklch(70% 0.18 42 / 0.36),
    0 0 0 1px oklch(70% 0.18 42 / 0.6);
}

.ai-assistant[data-open="true"] .ai-fab {
  opacity: 0;
  pointer-events: none;
}

.ai-fab-glow {
  position: absolute;
  inset: -6px;
  z-index: -1;
  border-radius: inherit;
  background: radial-gradient(circle at center, var(--color-ember) 0%, transparent 60%);
  filter: blur(14px);
  opacity: 0.55;
  animation: ai-fab-breathe 3.4s ease-in-out infinite;
}

.ai-fab-core {
  display: inline-grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-pill);
  background: linear-gradient(135deg, var(--color-ember), var(--color-ember-strong));
  color: oklch(99% 0.01 70);
  box-shadow: inset 0 1px 0 oklch(100% 0 0 / 0.35);
}

.ai-fab-label {
  display: grid;
  text-align: left;
  line-height: 1;
}

.ai-fab-label small {
  font-family: var(--font-mono);
  font-size: 9px;
  font-weight: 500;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-ember-strong);
}

body[data-scene="admin"] .ai-fab-label small {
  color: var(--color-ember-soft);
}

.ai-fab-label strong {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

@keyframes ai-fab-breathe {
  0%, 100% { opacity: 0.4; transform: scale(0.92); }
  50% { opacity: 0.85; transform: scale(1.12); }
}

/* ---- Panel ---- */
.ai-assistant .ai-panel {
  position: absolute;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  width: min(560px, calc(100vw - 28px));
  max-height: min(82dvh, 780px);
  overflow: hidden;
  border: 1px solid var(--cp-hairline);
  border-radius: var(--radius-lg);
  background: var(--cp-card);
  color: var(--cp-text);
  box-shadow: var(--cp-shadow), var(--cp-edge-glow);
  transform-origin: bottom right;
  animation: ai-panel-in 0.28s var(--ease-out-expo);
  isolation: isolate;
}

.ai-assistant .ai-panel::before {
  content: "";
  position: absolute;
  inset: 0 0 auto 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--color-ember) 50%, transparent);
  opacity: 0.55;
}

@keyframes ai-panel-in {
  from { transform: translateY(12px) scale(0.96); }
  to { transform: none; }
}

/* ---- Head ---- */
.ai-assistant .ai-panel-head {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 18px 16px;
  border-bottom: 1px solid var(--cp-hairline);
  background:
    radial-gradient(120% 80% at 10% -20%, oklch(70% 0.18 42 / 0.14), transparent 60%),
    linear-gradient(180deg, var(--cp-raised), var(--cp-card));
  color: var(--cp-text);
}

.ai-panel-head-copy {
  min-width: 0;
}

.ai-assistant .ai-panel-head .eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 8px;
  color: var(--color-ember-strong);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

body[data-scene="admin"] .ai-assistant .ai-panel-head .eyebrow {
  color: var(--color-ember-soft);
}

.ai-assistant .ai-panel-head h2 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  font-variation-settings: "opsz" 24;
  color: var(--cp-text);
}

.ai-assistant .ai-panel-head span {
  display: block;
  margin-top: 4px;
  color: var(--cp-text-sec);
  font-size: 12.5px;
  line-height: 1.5;
}

.ai-session-id {
  padding: 2px 7px;
  border: 1px solid var(--cp-hairline-strong);
  border-radius: var(--radius-xs);
  background: var(--cp-inset);
  color: var(--cp-text-sec);
  font-size: 10px;
  letter-spacing: 0.02em;
  text-transform: none;
}

.ai-status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--cp-text-mu);
  box-shadow: 0 0 0 0 oklch(60% 0.014 80 / 0);
}

.ai-status-dot[data-state="live"] {
  background: var(--color-moss);
  animation: ai-status-pulse 1.4s ease-out infinite;
}

@keyframes ai-status-pulse {
  0% { box-shadow: 0 0 0 0 oklch(54% 0.10 145 / 0.45); }
  100% { box-shadow: 0 0 0 8px oklch(54% 0.10 145 / 0); }
}

.ai-assistant .ai-panel-tools {
  display: inline-flex;
  gap: 6px;
}

.ai-assistant .ai-panel-head button {
  display: inline-grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--cp-hairline);
  border-radius: var(--radius-sm);
  background: var(--cp-inset);
  color: var(--cp-text-sec);
  transition: background var(--dur-fast) var(--ease-out-expo), color var(--dur-fast) var(--ease-out-expo);
}

.ai-assistant .ai-panel-head button:hover {
  background: oklch(70% 0.18 42 / 0.12);
  color: var(--color-ember-strong);
}

/* ---- Thread ---- */
.ai-assistant .ai-thread {
  display: grid;
  gap: 18px;
  max-height: none;
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 20px 18px;
  background: var(--cp-card);
  scrollbar-width: thin;
  scrollbar-color: var(--cp-hairline-strong) transparent;
}

.ai-assistant .ai-thread::-webkit-scrollbar { width: 6px; }
.ai-assistant .ai-thread::-webkit-scrollbar-thumb { background: var(--cp-hairline-strong); border-radius: 999px; }

.ai-assistant .ai-message {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 12px;
  align-items: flex-start;
}

.ai-assistant .ai-message.user {
  grid-template-columns: minmax(0, 1fr) 32px;
}

.ai-assistant .ai-message.user .ai-avatar { order: 2; }
.ai-assistant .ai-message.user .ai-bubble { order: 1; text-align: left; }

.ai-assistant .ai-avatar {
  display: inline-grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  background: oklch(70% 0.18 42 / 0.10);
  color: var(--color-ember-strong);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
}

body[data-scene="admin"] .ai-assistant .ai-avatar {
  background: oklch(100% 0 0 / 0.06);
  color: var(--color-ember-soft);
}

.ai-assistant .ai-message.user .ai-avatar {
  background: linear-gradient(135deg, var(--color-ember), var(--color-ember-strong));
  color: oklch(99% 0.01 70);
}

.ai-assistant .ai-bubble {
  position: relative;
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid var(--cp-hairline);
  border-radius: 14px;
  background: var(--cp-raised);
  color: var(--cp-text);
  font-size: 14px;
  line-height: 1.62;
  box-shadow: none;
}

.ai-assistant .ai-message.user .ai-bubble {
  background: linear-gradient(135deg, oklch(70% 0.18 42 / 0.14), oklch(70% 0.18 42 / 0.04));
  border-color: oklch(70% 0.18 42 / 0.3);
  color: var(--cp-text);
}

.ai-assistant .ai-bubble p {
  margin: 0;
  text-wrap: pretty;
}

.ai-text {
  white-space: pre-wrap;
  word-break: break-word;
}

/* Streaming cursor */
.ai-cursor {
  display: inline-block;
  width: 0.55em;
  height: 1em;
  margin-left: 3px;
  vertical-align: -2px;
  border-radius: 1px;
  background: var(--color-ember);
  animation: ai-cursor-blink 0.92s steps(1, end) infinite;
}

@keyframes ai-cursor-blink {
  0%, 49% { opacity: 1; }
  50%, 100% { opacity: 0; }
}

/* ---- Agent Trace + Tool Timeline ---- */
.ai-assistant .ai-agent-trace {
  margin-top: 12px;
  padding-top: 12px;
  padding-left: 0;
  border-left: 0;
  border-top: 1px dashed var(--cp-hairline-strong);
}

.ai-assistant .ai-agent-trace > small {
  display: inline-block;
  margin-bottom: 10px;
  padding: 2px 8px;
  border: 1px solid var(--cp-hairline);
  border-radius: var(--radius-xs);
  background: var(--cp-inset);
  color: var(--cp-text-mu);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
}

.ai-tool-timeline {
  position: relative;
  list-style: none;
  margin: 0;
  padding: 0 0 0 12px;
}

.ai-tool-timeline::before {
  content: "";
  position: absolute;
  top: 12px;
  bottom: 12px;
  left: 6px;
  width: 1px;
  background: linear-gradient(180deg, var(--cp-hairline-strong), var(--cp-hairline));
}

.ai-tool-step {
  position: relative;
  margin: 0 0 8px;
}

.ai-tool-step:last-child { margin-bottom: 0; }

.ai-tool-step-head {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) auto;
  gap: 10px;
  width: 100%;
  align-items: center;
  padding: 7px 10px 7px 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background var(--dur-fast) var(--ease-out-expo);
}

.ai-tool-step-head:hover {
  background: var(--cp-inset);
}

.ai-tool-rail {
  position: relative;
  display: inline-grid;
  place-items: center;
  width: 22px;
  height: 22px;
  margin-left: -18px;
  border-radius: 50%;
  background: var(--cp-card);
  box-shadow: 0 0 0 3px var(--cp-raised);
}

.ai-tool-step[data-status="running"] .ai-tool-rail { color: var(--color-ember-strong); }
.ai-tool-step[data-status="done"] .ai-tool-rail { color: var(--color-moss); }
.ai-tool-step[data-status="error"] .ai-tool-rail { color: var(--color-danger); }

.ai-tool-meta {
  display: grid;
  gap: 1px;
  min-width: 0;
  text-align: left;
}

.ai-tool-meta strong {
  display: block;
  color: var(--cp-text);
  font-family: var(--font-mono);
  font-size: 12.5px;
  font-weight: 500;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-tool-meta small {
  color: var(--cp-text-mu);
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.ai-tool-chev {
  color: var(--cp-text-mu);
  transition: transform var(--dur-fast) var(--ease-out-expo);
}

.ai-tool-step[data-expanded="true"] .ai-tool-chev {
  transform: rotate(90deg);
  color: var(--color-ember-strong);
}

.ai-tool-payload {
  display: grid;
  gap: 8px;
  margin: 6px 0 6px 4px;
  padding: 10px 12px;
  border: 1px solid var(--cp-hairline);
  border-radius: var(--radius-sm);
  background: var(--cp-payload);
}

.ai-tool-payload-block { display: grid; gap: 4px; }

.ai-tool-tag {
  display: inline-block;
  align-self: flex-start;
  padding: 2px 7px;
  border-radius: var(--radius-xs);
  background: oklch(70% 0.18 42 / 0.18);
  color: var(--color-ember-strong);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 500;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.ai-tool-tag[data-variant="result"] {
  background: oklch(54% 0.10 145 / 0.18);
  color: oklch(40% 0.10 145);
}

body[data-scene="admin"] .ai-tool-tag[data-variant="result"] {
  background: oklch(82% 0.16 142 / 0.16);
  color: oklch(82% 0.16 142);
}

.ai-tool-payload pre {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  color: var(--cp-text-sec);
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
}

/* Override legacy .ai-tool-card from styles.css (we replaced it) */
.ai-assistant .ai-tool-card { display: none; }

/* ---- Shop result cards ---- */
.ai-assistant .ai-shop-results {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}

.ai-assistant .ai-shop-card {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--cp-hairline);
  border-radius: var(--radius-sm);
  background: var(--cp-inset);
  color: var(--cp-text);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease-out-expo), background var(--dur-fast) var(--ease-out-expo);
}

.ai-assistant .ai-shop-card:hover {
  border-color: oklch(70% 0.18 42 / 0.35);
  background: oklch(70% 0.18 42 / 0.06);
}

.ai-assistant .ai-shop-card strong {
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--cp-text);
}

.ai-assistant .ai-shop-card small,
.ai-assistant .ai-shop-card em,
.ai-assistant .ai-shop-card i {
  color: var(--cp-text-mu);
  font-style: normal;
  font-size: 11.5px;
}

.ai-assistant .ai-shop-card em {
  color: var(--color-ember-strong);
  font-family: var(--font-mono);
  font-size: 11px;
}

.ai-assistant .ai-shop-meta {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 10px;
  font-family: var(--font-mono);
  font-feature-settings: "tnum";
}

.ai-assistant .ai-voucher-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 2px;
}

.ai-assistant .ai-voucher-tags b {
  padding: 2px 7px;
  border-radius: var(--radius-xs);
  background: oklch(70% 0.18 42 / 0.16);
  color: var(--color-ember-strong);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.04em;
}

/* ---- Quick prompts (pill chips) ---- */
.ai-assistant .ai-quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 18px;
  border-top: 1px solid var(--cp-hairline);
  background: var(--cp-card);
}

.ai-assistant .ai-quick-prompts button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--cp-hairline-strong);
  border-radius: var(--radius-pill);
  background: var(--cp-inset);
  color: var(--cp-text-sec);
  font-family: var(--font-mono);
  font-size: 11.5px;
  font-weight: 500;
  letter-spacing: -0.005em;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out-expo), border-color var(--dur-fast) var(--ease-out-expo);
}

.ai-assistant .ai-quick-prompts button:hover {
  background: oklch(70% 0.18 42 / 0.12);
  border-color: oklch(70% 0.18 42 / 0.4);
  color: var(--color-ember-strong);
}

/* ---- Action buttons inside messages ---- */
.ai-assistant .ai-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.ai-assistant .ai-actions button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid oklch(70% 0.18 42 / 0.34);
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, oklch(70% 0.18 42 / 0.14), oklch(70% 0.18 42 / 0.04));
  color: var(--color-ember-strong);
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease-out-expo);
}

.ai-assistant .ai-actions button:hover {
  background: linear-gradient(135deg, oklch(70% 0.18 42 / 0.26), oklch(70% 0.18 42 / 0.08));
}

.ai-assistant .ai-actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.ai-confirmation {
  display: grid;
  gap: 12px;
  margin-top: 12px;
  padding: 14px;
  border: 1px solid oklch(70% 0.16 55 / 0.45);
  border-radius: var(--radius-sm);
  background: oklch(72% 0.15 65 / 0.10);
}

.ai-confirmation strong,
.ai-confirmation small {
  display: block;
}

.ai-confirmation p {
  margin: 6px 0;
}

.ai-confirmation pre {
  max-height: 120px;
  overflow: auto;
  margin: 8px 0 0;
  padding: 8px;
  border-radius: var(--radius-xs);
  background: var(--cp-inset);
  white-space: pre-wrap;
}

.ai-confirmation-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.ai-confirmation-actions button {
  padding: 8px 12px;
  border: 1px solid var(--cp-hairline-strong);
  border-radius: var(--radius-xs);
  background: var(--cp-card);
}

.ai-confirmation-actions button.primary {
  border-color: var(--color-ember);
  background: var(--color-ember);
  color: white;
}

/* ---- Error ---- */
.ai-assistant .ai-error {
  margin: 0;
  padding: 8px 18px;
  border-top: 1px solid oklch(58% 0.20 28 / 0.3);
  background: oklch(58% 0.20 28 / 0.10);
  color: oklch(45% 0.15 28);
  font-size: 12.5px;
  font-family: var(--font-mono);
}

body[data-scene="admin"] .ai-assistant .ai-error {
  background: oklch(58% 0.20 28 / 0.16);
  color: oklch(82% 0.12 28);
}

/* ---- Input ---- */
.ai-assistant .ai-input {
  display: flex;
  gap: 8px;
  padding: 14px 16px 16px;
  border-top: 1px solid var(--cp-hairline);
  background: var(--cp-raised);
}

.ai-assistant .ai-input input {
  flex: 1 1 auto;
  min-height: 44px;
  padding: 0 14px;
  border: 1px solid var(--cp-hairline-strong);
  border-radius: var(--radius-sm);
  background: var(--cp-card);
  color: var(--cp-text);
  font-family: var(--font-sans);
  font-size: 14px;
  outline: 0;
  transition: border-color var(--dur-fast) var(--ease-out-expo), background var(--dur-fast) var(--ease-out-expo);
}

.ai-assistant .ai-input input::placeholder {
  color: var(--cp-text-mu);
  font-family: var(--font-mono);
  font-size: 12.5px;
}

.ai-assistant .ai-input input:focus {
  border-color: oklch(70% 0.18 42 / 0.5);
  background: var(--cp-card);
  box-shadow: 0 0 0 3px oklch(70% 0.18 42 / 0.1);
}

.ai-assistant .ai-input button {
  display: inline-grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border: 1px solid oklch(70% 0.18 42 / 0.5);
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, var(--color-ember), var(--color-ember-strong));
  color: oklch(99% 0.01 70);
  cursor: pointer;
  transition: transform var(--dur-fast) var(--ease-out-expo), box-shadow var(--dur-fast) var(--ease-out-expo);
}

.ai-assistant .ai-input button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 12px 28px oklch(70% 0.18 42 / 0.35);
}

.ai-assistant .ai-input button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

/* ---- Mobile ---- */
@media (max-width: 560px) {
  .ai-assistant .ai-panel {
    right: 0;
    left: 0;
    width: 100%;
    max-height: 88dvh;
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  }
}
</style>
