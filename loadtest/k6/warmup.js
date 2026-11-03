// 측정 전 JVM 워밍업 (실험 계획 §2). 장애 없음, 별도 RUN_ID라 analyzer 집계에서 제외된다.
import { placeOrder } from './lib/harness.js';

export const options = {
  scenarios: { warmup: { executor: 'constant-arrival-rate', rate: Number(__ENV.WARMUP_RPS || 100), timeUnit: '1s',
    duration: __ENV.WARMUP_DURATION || '120s', preAllocatedVUs: 50, maxVUs: 300 } },
};

export default function () {
  placeOrder('', 'warmup');
}
