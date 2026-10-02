<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  cancelOpsCase,
  createOpsPlan,
  decideOpsApproval,
  fetchOpsCase,
  fetchOpsCases,
  retryOpsCase,
} from '../api/citycopilot';
import { getErrorMessage } from '../utils/errors';
import type { OpsCase, OpsCaseDetail, OpsPlanSubmission } from '../types';

const cases = ref<OpsCase[]>([]);
const selected = ref<OpsCaseDetail | null>(null);
const pendingApproval = ref<OpsPlanSubmission | null>(null);
const loading = ref(false);
const busy = ref(false);
const error = ref('');

const currentEvidence = computed(() => {
  const rows = selected.value?.evidence || [];
  return rows.length ? rows[rows.length - 1] : undefined;
});

const snapshot = computed<Record<string, unknown> | null>(() => {
  const payload = currentEvidence.value?.payload_json;
  if (typeof payload !== 'string') return null;
  try {
    const parsed = JSON.parse(payload);
    return parsed.snapshot || parsed;
  } catch {
    return null;
  }
});

async function loadCases(selectId?: string) {
  loading.value = true;
  error.value = '';
  try {
    cases.value = await fetchOpsCases();
    const id = selectId || selected.value?.case.id || cases.value[0]?.id;
    selected.value = id ? await fetchOpsCase(id) : null;
  } catch (cause) {
    error.value = getErrorMessage(cause, 'Case 加载失败');
  } finally {
    loading.value = false;
  }
}

async function selectCase(item: OpsCase) {
  selected.value = await fetchOpsCase(item.id);
  pendingApproval.value = null;
}

async function prepareRepair() {
  const item = selected.value;
  const evidenceHash = snapshot.value?.evidenceHash;
  const voucherId = snapshot.value?.voucherId;
  if (!item || typeof evidenceHash !== 'string' || typeof voucherId !== 'number') {
    error.value = '当前没有可绑定的库存证据';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    pendingApproval.value = await createOpsPlan(
      item.case.id,
      {
        summary: 'Redis 库存镜像与 Reservation 感知的期望库存不一致',
        source: 'operator-console',
      },
      [{
        toolName: 'repair_seckill_stock',
        arguments: { voucherId, expectedEvidenceHash: evidenceHash },
        purpose: '仅恢复该券 Redis 库存镜像，并在写入后独立复核',
      }],
    );
    await loadCases(item.case.id);
  } catch (cause) {
    error.value = getErrorMessage(cause, '计划创建失败');
  } finally {
    busy.value = false;
  }
}

async function decide(approved: boolean) {
  if (!pendingApproval.value) return;
  busy.value = true;
  try {
    await decideOpsApproval(
      pendingApproval.value.approvalId,
      pendingApproval.value.planHash,
      approved,
    );
    const caseId = pendingApproval.value.case.id;
    pendingApproval.value = null;
    window.setTimeout(() => loadCases(caseId), approved ? 2500 : 300);
  } catch (cause) {
    error.value = getErrorMessage(cause, '审批失败');
  } finally {
    busy.value = false;
  }
}

async function retryCase() {
  if (!selected.value) return;
  busy.value = true;
  try {
    await retryOpsCase(selected.value.case.id);
    window.setTimeout(() => loadCases(selected.value!.case.id), 2500);
  } finally {
    busy.value = false;
  }
}

async function cancelCase() {
  if (!selected.value) return;
  busy.value = true;
  try {
    await cancelOpsCase(selected.value.case.id);
    await loadCases(selected.value.case.id);
  } finally {
    busy.value = false;
  }
}

function pretty(value: unknown) {
  return JSON.stringify(value, null, 2);
}

onMounted(() => loadCases());
</script>

<template>
  <section class="ops-cases-page">
    <header class="ops-cases-head">
      <div>
        <span class="eyebrow">Operations agent</span>
        <h1>诊断、审批与复核</h1>
        <p>事件只负责触发；证据、计划哈希、一次性审批、执行结果和独立复核全部持久化。</p>
      </div>
      <button type="button" :disabled="loading" @click="loadCases()">刷新 Case</button>
    </header>

    <p v-if="error" class="ops-case-error">{{ error }}</p>

    <div class="ops-cases-layout">
      <aside class="ops-case-list">
        <button
          v-for="item in cases"
          :key="item.id"
          type="button"
          :class="{ active: selected?.case.id === item.id }"
          @click="selectCase(item)"
        >
          <span>{{ item.caseType }}</span>
          <strong>{{ item.incidentKey }}</strong>
          <small>{{ item.status }} · v{{ item.version }}</small>
        </button>
        <p v-if="!cases.length && !loading">暂无异常 Case</p>
      </aside>

      <main v-if="selected" class="ops-case-detail">
        <section class="case-summary">
          <div>
            <span>当前状态</span>
            <strong>{{ selected.case.status }}</strong>
          </div>
          <div>
            <span>Case ID</span>
            <code>{{ selected.case.id }}</code>
          </div>
          <div>
            <span>重试 / 版本</span>
            <strong>{{ selected.case.retryCount }} / {{ selected.case.version }}</strong>
          </div>
          <div class="case-actions">
            <button
              v-if="selected.case.status === 'MANUAL_REVIEW'"
              type="button"
              :disabled="busy"
              @click="prepareRepair"
            >生成受限修复计划</button>
            <button
              v-if="['MANUAL_REVIEW', 'FAILED'].includes(selected.case.status)"
              type="button"
              :disabled="busy"
              @click="retryCase"
            >重新采证</button>
            <button
              v-if="!['SUCCEEDED', 'CANCELLED'].includes(selected.case.status)"
              class="quiet"
              type="button"
              :disabled="busy"
              @click="cancelCase"
            >取消</button>
          </div>
        </section>

        <section v-if="pendingApproval" class="approval-card">
          <div>
            <span>一次性审批</span>
            <strong>{{ pendingApproval.planHash }}</strong>
            <small>到期：{{ pendingApproval.approvalExpiresAt }}</small>
          </div>
          <div>
            <button type="button" :disabled="busy" @click="decide(true)">批准并执行</button>
            <button class="quiet" type="button" :disabled="busy" @click="decide(false)">拒绝</button>
          </div>
        </section>

        <div class="evidence-grid">
          <section>
            <h2>最新证据</h2>
            <pre>{{ pretty(snapshot) }}</pre>
          </section>
          <section>
            <h2>计划与差异</h2>
            <pre>{{ pretty(selected.plan || { status: '尚未形成计划' }) }}</pre>
          </section>
          <section>
            <h2>执行与复核</h2>
            <pre>{{ pretty(selected.executions) }}</pre>
          </section>
        </div>

        <section class="case-timeline">
          <h2>状态时间线</h2>
          <ol>
            <li v-for="entry in selected.transitions" :key="String(entry.id)">
              <span>{{ entry.from_status || 'EVENT' }} → {{ entry.to_status }}</span>
              <strong>{{ entry.reason }}</strong>
              <small>{{ entry.actor }} · {{ entry.created_at }}</small>
            </li>
          </ol>
        </section>
      </main>
    </div>
  </section>
</template>

<style scoped>
.ops-cases-page { padding: 30px; color: #17332a; }
.ops-cases-head { display: flex; justify-content: space-between; gap: 24px; align-items: end; margin-bottom: 22px; }
.ops-cases-head h1 { margin: 4px 0 6px; font-size: 30px; }
.ops-cases-head p { margin: 0; color: #5f716b; }
.eyebrow { color: #aa6b2e; font-size: 12px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
button { border: 0; border-radius: 10px; padding: 10px 14px; background: #173f35; color: white; cursor: pointer; }
button:disabled { opacity: .5; cursor: wait; }
button.quiet { background: #e8eeeb; color: #29463d; }
.ops-case-error { padding: 12px 14px; border-radius: 10px; background: #fff0ec; color: #9d3528; }
.ops-cases-layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 18px; }
.ops-case-list { display: flex; flex-direction: column; gap: 8px; }
.ops-case-list button { display: grid; text-align: left; gap: 5px; background: #edf2ef; color: #17332a; }
.ops-case-list button.active { background: #173f35; color: white; }
.ops-case-list span, .ops-case-list small { font-size: 11px; opacity: .72; }
.ops-case-detail { min-width: 0; display: grid; gap: 16px; }
.case-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)) auto; gap: 12px; padding: 18px; border-radius: 16px; background: white; box-shadow: 0 8px 30px #16362b12; }
.case-summary div { display: grid; gap: 5px; min-width: 0; }
.case-summary span { font-size: 12px; color: #75837f; }
.case-summary code { overflow: hidden; text-overflow: ellipsis; }
.case-actions { display: flex !important; flex-wrap: wrap; align-content: center; justify-content: end; }
.approval-card { display: flex; justify-content: space-between; gap: 20px; align-items: center; padding: 16px 18px; border: 1px solid #d39a52; border-radius: 14px; background: #fff8eb; }
.approval-card div { display: grid; gap: 5px; min-width: 0; }
.approval-card strong { font-family: monospace; overflow: hidden; text-overflow: ellipsis; }
.approval-card div:last-child { display: flex; }
.evidence-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.evidence-grid section, .case-timeline { padding: 16px; border-radius: 14px; background: white; box-shadow: 0 8px 30px #16362b0d; }
h2 { margin: 0 0 12px; font-size: 15px; }
pre { min-height: 210px; max-height: 390px; overflow: auto; margin: 0; padding: 12px; border-radius: 10px; background: #112820; color: #dcebe5; font-size: 11px; white-space: pre-wrap; word-break: break-word; }
.case-timeline ol { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.case-timeline li { display: grid; grid-template-columns: 190px 1fr 250px; gap: 12px; align-items: center; padding: 10px; border-bottom: 1px solid #edf1ef; }
.case-timeline li span { font-family: monospace; color: #a46125; }
.case-timeline li small { color: #71807b; text-align: right; }
@media (max-width: 1050px) {
  .ops-cases-layout { grid-template-columns: 1fr; }
  .ops-case-list { display: grid; grid-template-columns: repeat(2, 1fr); }
  .evidence-grid { grid-template-columns: 1fr; }
  .case-summary { grid-template-columns: repeat(2, 1fr); }
}
</style>
