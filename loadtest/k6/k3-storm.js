// K3 · 실패 폭주: 100 rps 5분, t=2m~3m 구간에 shipping:tech 50% (RQ2 알림 피로)
import exec from 'k6/execution';
import { placeOrder, summaryTrendStats } from './lib/harness.js';

export const options = {
  scenarios: { storm: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: '5m', preAllocatedVUs: 100, maxVUs: 600 } },
  summaryTrendStats,
};

export default function () {
  const s = (Date.now() - exec.scenario.startTime) / 1000;
  const storm = s >= 120 && s < 180;
  placeOrder(storm && Math.random() < 0.5 ? 'shipping:tech' : '', storm ? 'storm' : 'calm');
}
