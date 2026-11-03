// K1 · 기준: 50 → 100 → 200 rps 고정 도착률 구간(각 3분), 장애 없음 — 방법론별 순수 오버헤드 (RQ3, RQ6)
import exec from 'k6/execution';
import { placeOrder, summaryTrendStats } from './lib/harness.js';

const step = (rate, startTime) => ({
  executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: '3m', startTime,
  preAllocatedVUs: 100, maxVUs: 600,
});
export const options = {
  scenarios: { rps50: step(50, '0s'), rps100: step(100, '3m'), rps200: step(200, '6m') },
  summaryTrendStats,
};

export default function () {
  placeOrder('', exec.scenario.name);
}
