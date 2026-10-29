// k6 大数据压测的公共部分：固定到达率（开环）执行器、服务端错误率、夹具读取与按权重抽样、每级结果输出。
// 4xx 业务拒绝（售罄、名额已满、限购）属于正常结果；只有 5xx 与网络错误计入 server_errors。
import { Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

export const BASE = __ENV.BASE || 'http://core:8081';
export const serverErrors = new Rate('server_errors');

export function options(name) {
  const rate = parseInt(__ENV.RATE || '100', 10);
  return {
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
    scenarios: {
      [name]: {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration: __ENV.DURATION || '60s',
        preAllocatedVUs: Math.min(Math.max(rate, 50), 3000),
        maxVUs: Math.min(rate * 5, 6000),
      },
    },
  };
}

export function track(response) {
  serverErrors.add(response.status === 0 || response.status >= 500);
  return response;
}

const cache = {};
export function fixture(key) {
  if (!cache[key]) {
    cache[key] = new SharedArray(key, () => JSON.parse(open('/data/fixtures.json'))[key]);
  }
  return cache[key];
}

export function any(items) {
  return items[Math.floor(Math.random() * items.length)];
}

// 按 weight 字段抽样：累积权重在初始化阶段算一次，抽样用二分查找。
export function weightedPicker(items) {
  const cumulative = [];
  let total = 0;
  for (let i = 0; i < items.length; i++) {
    total += items[i].weight;
    cumulative.push(total);
  }
  return () => {
    const target = Math.random() * total;
    let low = 0;
    let high = cumulative.length - 1;
    while (low < high) {
      const mid = (low + high) >> 1;
      if (cumulative[mid] < target) low = mid + 1; else high = mid;
    }
    return items[low];
  };
}

export function auth(token, extra) {
  return { headers: Object.assign({ Authorization: token, 'Content-Type': 'application/json' }, extra || {}) };
}

export function summary(name) {
  return (data) => {
    const duration = data.metrics.http_req_duration.values;
    const result = {
      scenario: name,
      targetRate: parseInt(__ENV.RATE || '100', 10),
      requests: data.metrics.http_reqs.values.count,
      achievedRps: data.metrics.http_reqs.values.rate,
      iterations: data.metrics.iterations ? data.metrics.iterations.values.count : 0,
      droppedIterations: data.metrics.dropped_iterations ? data.metrics.dropped_iterations.values.count : 0,
      serverErrorRate: data.metrics.server_errors ? data.metrics.server_errors.values.rate : 0,
      latencyMs: { avg: duration.avg, p50: duration['p(50)'], p95: duration['p(95)'], p99: duration['p(99)'], max: duration.max },
    };
    return { [`/data/summary-${name}.json`]: JSON.stringify(result) };
  };
}
