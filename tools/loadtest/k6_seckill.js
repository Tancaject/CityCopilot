// S3 秒杀开抢：setup 新建一张进行中的爆款券（库存等于令牌池大小），每次迭代用一个不同用户抢购。
// 每一级都是一张新券，期间对账任务照常运行，买家数随压测增长到数万，用来复现全量比对的代价。
// 券在本级结束两分钟后到期，配合 bigdata 覆盖文件缩短的归档窗口，上一级的券不再拖累下一级的对账。
import http from 'k6/http';
import exec from 'k6/execution';
import { BASE, auth, fixture, options as base, summary, track } from './k6_common.js';

export const options = base('seckill');
const users = fixture('users');
const shops = fixture('shops');

export function setup() {
  const now = Date.now();
  const duration = __ENV.DURATION || '60s';
  const seconds = parseInt(duration, 10) * (duration.endsWith('m') ? 60 : 1);
  const response = http.post('http://commerce:8083/commerce/v1/vouchers', JSON.stringify({
    shopId: shops[0].id, title: `k6-seckill-${now}`, payValue: 1990, actualValue: 5000, type: 1, status: 1,
    stock: parseInt(__ENV.STOCK || String(users.length), 10), beginTime: new Date(now - 3600000).toISOString(), endTime: new Date(now + (seconds + 120) * 1000).toISOString(),
  }), { headers: { 'Content-Type': 'application/json', 'X-CityCopilot-Service-Token': __ENV.SERVICE_TOKEN,
    'X-CityCopilot-User-Id': '1', 'X-CityCopilot-Admin': 'true' }, responseType: 'text' });
  if (response.status >= 300) throw new Error(`voucher create failed: ${response.status} ${response.body}`);
  return { voucherId: JSON.parse(response.body).id };
}

export default function (data) {
  const index = exec.scenario.iterationInTest;
  const user = users[index % users.length];
  track(http.post(`${BASE}/voucher-order/seckill/${data.voucherId}`, null,
    Object.assign({ tags: { name: 'seckill' } }, auth(user.token, { 'Idempotency-Key': `${data.voucherId}-${index}` }))));
}

export const handleSummary = summary('seckill');
