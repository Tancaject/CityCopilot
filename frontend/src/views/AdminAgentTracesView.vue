<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Activity, RefreshCw } from '@lucide/vue';
import { fetchAgentTrace, fetchAgentTraces } from '../api/citycopilot';
import { getErrorMessage } from '../utils/errors';
import type { AgentTrace } from '../types';

const traces = ref<AgentTrace[]>([]);
const selected = ref<AgentTrace | null>(null);
const loading = ref(false);
const errorMessage = ref('');

async function loadTraces() {
  loading.value = true;
  errorMessage.value = '';
  try {
    traces.value = await fetchAgentTraces(50);
    if (!selected.value && traces.value.length) {
      selected.value = traces.value[0];
    }
  } catch (error) {
    errorMessage.value = getErrorMessage(error, 'Failed to load traces');
  } finally {
    loading.value = false;
  }
}

async function selectTrace(trace: AgentTrace) {
  selected.value = trace;
  try {
    selected.value = await fetchAgentTrace(trace.traceId);
  } catch {
    selected.value = trace;
  }
}

onMounted(loadTraces);
</script>

<template>
  <section class="admin-page">
    <header class="admin-page-head">
      <div>
        <p class="eyebrow">Agent observability</p>
        <h1>Traces</h1>
      </div>
      <button class="ghost-button" type="button" :disabled="loading" @click="loadTraces">
        <RefreshCw :class="{ spin: loading }" :size="16" aria-hidden="true" />
        Refresh
      </button>
    </header>

    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

    <div class="trace-dashboard">
      <aside class="trace-list">
        <button
          v-for="trace in traces"
          :key="trace.traceId"
          type="button"
          :class="{ active: selected?.traceId === trace.traceId }"
          @click="selectTrace(trace)"
        >
          <Activity :size="15" aria-hidden="true" />
          <span>
            <strong>{{ trace.mode }}</strong>
            <small>{{ trace.traceId }}</small>
          </span>
          <em>{{ trace.durationMs }}ms</em>
        </button>
      </aside>

      <article class="trace-detail">
        <template v-if="selected">
          <div class="trace-detail-head">
            <span>{{ selected.mode }}</span>
            <code>{{ selected.traceId }}</code>
          </div>
          <p>{{ selected.finalAnswer || 'No final answer recorded.' }}</p>
          <ol class="trace-steps">
            <li v-for="(step, index) in selected.steps" :key="index">
              <span>{{ step.type }}</span>
              <strong>{{ step.toolName || step.errorCode || 'final' }}</strong>
              <code>{{ JSON.stringify(step.observation || step.args || step.message || {}) }}</code>
            </li>
          </ol>
        </template>
        <p v-else class="empty-state">No traces yet.</p>
      </article>
    </div>
  </section>
</template>
