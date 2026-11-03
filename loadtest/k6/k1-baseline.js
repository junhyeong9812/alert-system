// K1 · 기준: 50 → 100 → 200 rps 단계(각 3분), 장애 없음 — 방법론별 순수 오버헤드 (RQ3, RQ6)
import exec from 'k6/execution';
import { placeOrder, summaryTrendStats } from './lib/harness.js';

const STAGES = [
  { duration: '10s', target: 50 }, { duration: '3m', target: 50 },
  { duration: '10s', target: 100 }, { duration: '3m', target: 100 },
  { duration: '10s', target: 200 }, { duration: '3m', target: 200 },
];
export const options = {
  scenarios: { baseline: { executor: 'ramping-arrival-rate', startRate: 50, timeUnit: '1s', stages: STAGES, preAllocatedVUs: 100, maxVUs: 600 } },
  summaryTrendStats,
};

function stage() {
  const s = (Date.now() - exec.scenario.startTime) / 1000;
  return s < 190 ? 'rps50' : s < 380 ? 'rps100' : 'rps200';
}

export default function () {
  placeOrder('', stage());
}
