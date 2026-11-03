// 실험 하네스 공통부: 요청마다 관측 id를 붙이고, 정답(expected) 한 줄을 로그 파일로 남긴다.
// 실행: k6 run --log-output=file=<run>/expected.log --log-format=raw -e RUN_ID=<id> <scenario>.js
// 관측 id(X-Test-Run-Id, X-Request-Id)는 아키텍처 로직이 아닌 정답 대조 전용이다 (spec I1).
import http from 'k6/http';

export const RUN_ID = __ENV.RUN_ID || 'adhoc';
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

const BIZ_CODE = { order: 'ORDER_LIMIT_EXCEEDED', product: 'OUT_OF_STOCK', shipping: 'ADDRESS_INVALID' };

function parse(fault) {
  return (fault || '').split(',').filter(Boolean).map((t) => {
    const [service, rest] = t.split(':');
    return { service, kind: (rest || '').split('=')[0] };
  });
}

// 흐름(order → product → shipping → 실패 시 보상)을 따라 어떤 알림이 나와야 하는지 계산한다.
// SG-01에서는 실패한 서비스가 자기 실패를 알린다.
export function expectedAlerts(fault) {
  const d = parse(fault);
  const failing = (svc) => d.find((x) => x.service === svc && ['biz', 'tech', 'crash-after-commit'].includes(x.kind));
  for (const svc of ['order', 'product', 'shipping']) {
    const f = failing(svc);
    if (!f) continue;
    const out = [{ service: svc, errorCode: f.kind === 'biz' ? BIZ_CODE[svc] : 'TECH_ERROR' }];
    if (svc === 'shipping' && d.some((x) => x.service === 'product' && x.kind === 'compensation-fail')) {
      out.push({ service: 'product', errorCode: 'COMPENSATION_FAILED' });
    }
    return out;
  }
  return [];
}

// 정답은 "실패를 주입한 요청"이 아니라 "실패가 실제로 발생한 요청"이다.
// 대상 서비스가 다운돼 요청이 도달하지 못하면 실패도 알림도 발생하지 않는다 → 응답의 자기 오류 코드로 발생 여부를 판정.
// (응답 경로는 알림 경로와 독립이다. crash-after-commit은 응답이 없으므로 의도적 주입을 그대로 정답으로 둔다.)
function reached(x, fault, status, body) {
  if (x.errorCode === 'COMPENSATION_FAILED') return body.status === 'COMPENSATION_FAILED';
  if (parse(fault).some((d) => d.service === x.service && d.kind === 'crash-after-commit')) return true;
  if (x.service === 'order') return body.reason === x.errorCode && (x.errorCode !== 'TECH_ERROR' || status === 500);
  if (x.errorCode === 'TECH_ERROR') return body.reason === `${x.service.toUpperCase()}_HTTP_500`;
  return body.reason === x.errorCode;
}

// weights: [[fault, weight], ...] — fault ''은 정상 요청
export function pick(weights) {
  const total = weights.reduce((s, [, w]) => s + w, 0);
  let r = Math.random() * total;
  for (const [fault, w] of weights) {
    r -= w;
    if (r < 0) return fault;
  }
  return weights[weights.length - 1][0];
}

export function placeOrder(fault, stage) {
  const requestId = crypto.randomUUID();
  const t = new Date().toISOString();
  const headers = { 'Content-Type': 'application/json', 'X-Test-Run-Id': RUN_ID, 'X-Request-Id': requestId };
  if (fault) headers['X-Fault'] = fault;
  const body = JSON.stringify({ productId: 1 + Math.floor(Math.random() * 10), quantity: 1, address: 'Seoul' });
  const res = http.post(`${BASE_URL}/orders`, body, { headers, timeout: '10s', tags: { fault: fault || 'none' } });
  let parsed = {};
  try { parsed = res.json() || {}; } catch (_) { /* 연결 실패 등 본문 없음 */ }
  const all = expectedAlerts(fault);
  // 응답이 실패(연결 거부 status 0 포함)해도 정답 행은 남긴다 (spec A2).
  console.log(JSON.stringify({
    type: 'expected', t, testRunId: RUN_ID, requestId, fault: fault || '', stage: stage || '',
    expect: all.filter((x) => reached(x, fault, res.status, parsed)),
    unreached: all.filter((x) => !reached(x, fault, res.status, parsed)),
    status: res.status, durationMs: res.timings.duration,
  }));
  return res;
}

export const summaryTrendStats = ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'];
