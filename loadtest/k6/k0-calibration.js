// K0 · analyzer 실 파이프라인 검증 (spec I3): 알려진 실패 100건, 채널 장애 없음 → coverage 100%, dup 0이어야 한다.
import exec from 'k6/execution';
import { placeOrder, summaryTrendStats } from './lib/harness.js';

const FAULTS = ['order:biz', 'product:biz', 'shipping:biz', 'product:tech', 'shipping:tech'];
export const options = { scenarios: { calibration: { executor: 'shared-iterations', vus: 10, iterations: 100 } }, summaryTrendStats };

export default function () {
  placeOrder(FAULTS[exec.scenario.iterationInTest % FAULTS.length], 'calibration');
}
