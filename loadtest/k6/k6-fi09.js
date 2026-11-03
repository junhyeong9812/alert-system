// K6 · FI-09 채널 5xx: 50 rps 5분(실패 10%). 러너가 t=60s~120s 동안 mock-slack을 5xx 모드로 전환 (SG-01 H2: 재시도 명령 증폭)
import { pick, placeOrder, summaryTrendStats } from './lib/harness.js';

const MIX = [['', 90], ['shipping:biz', 4], ['product:biz', 3], ['shipping:tech', 3]];
export const options = {
  scenarios: { channel: { executor: 'constant-arrival-rate', rate: 50, timeUnit: '1s', duration: '5m', preAllocatedVUs: 50, maxVUs: 300 } },
  summaryTrendStats,
};

export default function () {
  placeOrder(pick(MIX), 'channel');
}
