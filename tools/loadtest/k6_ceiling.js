// 客户端上限：打 nginx 直接返回 404 的路径，不经过后端，确认 k6 本身不是瓶颈。
import http from 'k6/http';
import { options as base, summary, track } from './k6_common.js';

export const options = base('ceiling');

export default function () {
  track(http.get('http://frontend:8080/api/internal/ceiling', { tags: { name: 'ceiling' } }));
}

export const handleSummary = summary('ceiling');
