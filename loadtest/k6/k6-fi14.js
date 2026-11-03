// K6 · FI-14 중복 전달: 20 rps 3분, 20%는 같은 msgId 이중 전송(dup-send).
// 러너가 t=60s~120s 동안 콜백 경로(toxiproxy)를 끊어 ack · 재시도 명령 유실을 주입한다 (L1 · L5 · L7).
import { pick, placeOrder, summaryTrendStats } from './lib/harness.js';

const MIX = [['', 80], ['shipping:tech,shipping:dup-send', 20]];
export const options = {
  scenarios: { dup: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '3m', preAllocatedVUs: 20, maxVUs: 200 } },
  summaryTrendStats,
};

export default function () {
  placeOrder(pick(MIX), 'dup');
}
