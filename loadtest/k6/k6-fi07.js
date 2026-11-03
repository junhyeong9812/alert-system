// K6 · FI-07 커밋 후 크래시: 배경 부하 20 rps(실패 10%) + 30초 간격 crash-after-commit 5회 (SG-01 H1: dual write 유실)
import { sleep } from 'k6';
import { pick, placeOrder, summaryTrendStats } from './lib/harness.js';

const MIX = [['', 90], ['shipping:biz', 5], ['product:tech', 5]];
export const options = {
  scenarios: {
    background: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '3m', preAllocatedVUs: 20, maxVUs: 200, exec: 'background' },
    crasher: { executor: 'per-vu-iterations', vus: 1, iterations: 5, startTime: '30s', exec: 'crasher' },
  },
  summaryTrendStats,
};

export function background() {
  placeOrder(pick(MIX), 'background');
}

export function crasher() {
  placeOrder('shipping:crash-after-commit', 'crash');
  sleep(30);
}
