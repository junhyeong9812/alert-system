// K2 · 혼합 정상상태: 100 rps 10분, biz 10% · tech 5% · latency 5% (RQ1, RQ2, RQ4)
import { pick, placeOrder, summaryTrendStats } from './lib/harness.js';

const MIX = [
  ['', 80],
  ['order:biz', 3], ['product:biz', 3], ['shipping:biz', 4],
  ['product:tech', 2], ['shipping:tech', 3],
  ['shipping:latency=300', 5],
];
export const options = {
  scenarios: { mixed: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s', duration: __ENV.DURATION || '10m', preAllocatedVUs: 100, maxVUs: 600 } },
  summaryTrendStats,
};

export default function () {
  placeOrder(pick(MIX), 'mixed');
}
