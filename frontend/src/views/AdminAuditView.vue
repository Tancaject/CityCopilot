<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { AlertCircle, Database, Download, Loader2, RefreshCw, Search, Trash2 } from '@lucide/vue';
import { cleanupAuditLogs, exportOperationLogs, fetchAuditStatus, fetchOperationLogs } from '../api/citycopilot';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import { authState } from '../composables/auth';
import { hasPermission } from '../utils/permissions';
import type { AuditStatus, OperationLog } from '../types';

const logs = ref<OperationLog[]>([]);
const status = ref<AuditStatus | null>(null);
const moduleFilter = ref('');
const actionFilter = ref('');
const targetTypeFilter = ref('');
const targetIdFilter = ref<number | undefined>();
const loading = ref(false);
const exporting = ref(false);
const cleaning = ref(false);
const errorMessage = ref('');
const successMessage = ref('');
const cleanupOpen = ref(false);

const canCleanup = computed(() => hasPermission(authState.user, 'audit:write'));

function formatTime(value?: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '未记录';
}

function numberOrUndefined(value: unknown) {
  if (value === '' || value === null || value === undefined) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function params() {
  return {
    current: 1,
    module: moduleFilter.value.trim() || undefined,
    action: actionFilter.value.trim() || undefined,
    targetType: targetTypeFilter.value.trim() || undefined,
    targetId: numberOrUndefined(targetIdFilter.value),
  };
}

async function loadAudit() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const [logResult, statusResult] = await Promise.all([fetchOperationLogs(params()), fetchAuditStatus()]);
    logs.value = logResult || [];
    status.value = statusResult;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '审计日志加载失败';
  } finally {
    loading.value = false;
  }
}

async function exportLogs() {
  exporting.value = true;
  errorMessage.value = '';
  try {
    const csv = await exportOperationLogs(params());
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `citycopilot-audit-${Date.now()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '审计日志导出失败';
  } finally {
    exporting.value = false;
  }
}

async function cleanupLogs() {
  cleaning.value = true;
  errorMessage.value = '';
  successMessage.value = '';
  try {
    const count = await cleanupAuditLogs();
    successMessage.value = `已清理 ${count} 条过期日志`;
    cleanupOpen.value = false;
    await loadAudit();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '审计日志清理失败';
  } finally {
    cleaning.value = false;
  }
}

onMounted(loadAudit);
</script>

<template>
  <section class="admin-audit-page">
    <header class="orders-header">
      <div>
        <p class="eyebrow">后台审计</p>
        <h1>操作日志</h1>
      </div>
      <button type="button" :disabled="loading" @click="loadAudit">
        <Loader2 v-if="loading" class="spin" :size="17" aria-hidden="true" />
        <RefreshCw v-else :size="17" aria-hidden="true" />
        刷新
      </button>
    </header>

    <section v-if="successMessage" class="notice">
      <Database :size="18" aria-hidden="true" />
      {{ successMessage }}
    </section>
    <section v-if="errorMessage" class="notice notice-error">
      <AlertCircle :size="18" aria-hidden="true" />
      {{ errorMessage }}
    </section>

    <form class="admin-audit-filters" @submit.prevent="loadAudit">
      <input v-model="moduleFilter" placeholder="模块，如 geo/order" />
      <input v-model="actionFilter" placeholder="动作，如 shop_update" />
      <input v-model="targetTypeFilter" placeholder="对象类型" />
      <input v-model.number="targetIdFilter" type="number" min="1" placeholder="对象ID" />
      <button type="submit" :disabled="loading">
        <Search :size="16" aria-hidden="true" />
        查询
      </button>
      <button type="button" :disabled="exporting" @click="exportLogs">
        <Loader2 v-if="exporting" class="spin" :size="16" aria-hidden="true" />
        <Download v-else :size="16" aria-hidden="true" />
        导出
      </button>
      <button v-if="canCleanup" type="button" :disabled="cleaning" @click="cleanupOpen = true">
        <Loader2 v-if="cleaning" class="spin" :size="16" aria-hidden="true" />
        <Trash2 v-else :size="16" aria-hidden="true" />
        清理
      </button>
    </form>

    <section v-if="status" class="admin-audit-status">
      <article>
        <span>留存天数</span>
        <strong>{{ status.retentionDays }}</strong>
      </article>
      <article>
        <span>总日志</span>
        <strong>{{ status.total }}</strong>
      </article>
      <article>
        <span>过期日志</span>
        <strong>{{ status.expired }}</strong>
      </article>
      <article>
        <span>导出上限</span>
        <strong>{{ status.exportLimit }}</strong>
      </article>
    </section>

    <div v-if="loading" class="loading-row orders-loading">
      <Loader2 class="spin" :size="18" aria-hidden="true" />
      正在读取日志
    </div>

    <section v-else class="admin-audit-list">
      <article v-for="log in logs" :key="log.id">
        <div>
          <strong>{{ log.module }} / {{ log.action }}</strong>
          <span>{{ log.targetType || 'target' }} #{{ log.targetId || log.targetKey || '-' }}</span>
        </div>
        <em :class="{ failed: !log.success }">{{ log.success ? '成功' : '失败' }}</em>
        <small>{{ log.actorName || 'system' }} · {{ formatTime(log.createTime) }}</small>
      </article>
      <div v-if="!logs.length" class="empty-state orders-empty">暂无审计日志</div>
    </section>

    <ConfirmDialog
      :open="cleanupOpen"
      title="确认清理过期日志"
      message="系统将按当前审计留存策略删除过期操作日志，导出与追责链路将不再包含这些记录。"
      confirm-text="清理日志"
      tone="danger"
      :busy="cleaning"
      @cancel="cleanupOpen = false"
      @confirm="cleanupLogs"
    />
  </section>
</template>
