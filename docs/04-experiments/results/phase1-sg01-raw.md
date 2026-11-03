# Phase 1 원시 집계 — SG-01 / SG-01+

> `python3 scripts/summarize-runs.py --since 20261103T140137` 출력 그대로(2026-11-03, 실행 디렉터리는 gitignore). 해석은 [phase1-sg01.md](phase1-sg01.md).

## k0-calibration

| 지표 | 중앙값 | r1 |
|---|---|---|
| 요청 | 100 | 100 |
| 의도치 않은 실패 | 0 | 0 |
| p50 ms | 43.7 | 43.7 |
| p95 ms | 70.8 | 70.8 |
| p99 ms | 84.6 | 84.6 |
| 정답 | 100 | 100 |
| 미도달 | 0 | 0 |
| 불명 | 0 | 0 |
| 도달 | 100 | 100 |
| 유실 | 0 | 0 |
| 중복 | 0 | 0 |
| 예상 밖 | 0 | 0 |
| 사람이 본 알림 | 100 | 100 |
| 채널 거절 | 0 | 0 |
| e2e p50 ms | 226 | 226 |
| e2e p95 ms | 418 | 418 |
| e2e max ms | 447 | 447 |
| 감지 s | - | - |
| 운영 알림 | 0 | 0 |
| sink 오류 | 0 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 |
|---|---|---|
| `8081 alert_send_total{result="ok"}` | 20 | 20 |
| `8082 alert_send_total{result="ok"}` | 40 | 40 |
| `8083 alert_send_total{result="ok"}` | 40 | 40 |
| `8084 ack_total{result="ok",status="ACKED"}` | 100 | 100 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 100 | 100 |

- r1 `k0-calibration-20261103T140137-r1` · dual-write(실패기록|alert_status): order 20|20, product 40|40, shipping 40|40 · inbox: SENT|100|0|100|100|0 · 생산자 상태: order=[ACKED|20|0]; product=[ACKED|40|0]; shipping=[ACKED|40|0]

## k1-baseline

| 지표 | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| 요청 | 63002 | 63002 | 63001 | 63002 |
| 의도치 않은 실패 | 0 | 0 | 0 | 0 |
| p50 ms | 20.0 | 20.4 | 15.1 | 20.0 |
| p95 ms | 40.7 | 40.7 | 38.0 | 42.8 |
| p99 ms | 62.0 | 62.0 | 58.1 | 65.0 |
| 정답 | 0 | 0 | 0 | 0 |
| 미도달 | 0 | 0 | 0 | 0 |
| 불명 | 0 | 0 | 0 | 0 |
| 도달 | 0 | 0 | 0 | 0 |
| 유실 | 0 | 0 | 0 | 0 |
| 중복 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 0 | 0 | 0 | 0 |
| 채널 거절 | 0 | 0 | 0 | 0 |
| e2e p50 ms | 0 | 0 | 0 | 0 |
| e2e p95 ms | 0 | 0 | 0 | 0 |
| e2e max ms | 0 | 0 | 0 | 0 |
| 감지 s | - | - | - | - |
| 운영 알림 | 0 | 0 | 0 | 0 |
| sink 오류 | 0 | 0 | 0 | 0 |

| 구간 | p50 ms (중앙값) | p95 ms (중앙값) | p99 ms (중앙값) | 의도치 않은 실패 (중앙값) |
|---|---|---|---|---|
| rps100 | 20.0 | 32.2 | 43.8 | 0 |
| rps200 | 21.4 | 48.1 | 71.1 | 0 |
| rps50 | 15.4 | 23.9 | 31.9 | 0 |

- r1 `k1-baseline-20261103T140514-r1` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 0|0 · inbox:  · 생산자 상태: order=[]; product=[]; shipping=[]
- r2 `k1-baseline-20261103T150158-r2` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 0|0 · inbox:  · 생산자 상태: order=[]; product=[]; shipping=[]
- r3 `k1-baseline-20261103T155841-r3` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 0|0 · inbox:  · 생산자 상태: order=[]; product=[]; shipping=[]

## k2-mixed

| 지표 | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| 요청 | 60001 | 60000 | 60001 | 60001 |
| 의도치 않은 실패 | 0 | 0 | 0 | 0 |
| p50 ms | 15.6 | 22.5 | 15.5 | 15.6 |
| p95 ms | 78.4 | 124.9 | 78.4 | 77.8 |
| p99 ms | 318.1 | 326.4 | 318.1 | 318.1 |
| 정답 | 9023 | 9023 | 9068 | 8999 |
| 미도달 | 0 | 0 | 0 | 0 |
| 불명 | 0 | 0 | 0 | 0 |
| 도달 | 9023 | 9023 | 9068 | 8999 |
| 유실 | 0 | 0 | 0 | 0 |
| 중복 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 9023 | 9023 | 9068 | 8999 |
| 채널 거절 | 0 | 0 | 0 | 0 |
| e2e p50 ms | 130 | 139 | 130 | 129 |
| e2e p95 ms | 227 | 240 | 227 | 226 |
| e2e max ms | 336 | 558 | 336 | 290 |
| 감지 s | - | - | - | - |
| 운영 알림 | 0 | 0 | 0 | 0 |
| sink 오류 | 0 | 0 | 0 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| `8081 alert_send_total{result="ok"}` | 1800 | 1777 | 1821 | 1800 |
| `8082 alert_send_total{result="ok"}` | 3016 | 3012 | 3072 | 3016 |
| `8083 alert_send_total{result="ok"}` | 4183 | 4234 | 4175 | 4183 |
| `8084 ack_total{result="ok",status="ACKED"}` | 9023 | 9023 | 9068 | 8999 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 9023 | 9023 | 9068 | 8999 |

- r1 `k2-mixed-20261103T141751-r1` · dual-write(실패기록|alert_status): order 1777|1777, product 3012|3012, shipping 4234|4234 · inbox: SENT|9023|0|9023|9023|0 · 생산자 상태: order=[ACKED|1777|0]; product=[ACKED|3012|0]; shipping=[ACKED|4234|0]
- r2 `k2-mixed-20261103T151436-r2` · dual-write(실패기록|alert_status): order 1821|1821, product 3072|3072, shipping 4175|4175 · inbox: SENT|9068|0|9068|9068|0 · 생산자 상태: order=[ACKED|1821|0]; product=[ACKED|3072|0]; shipping=[ACKED|4175|0]
- r3 `k2-mixed-20261103T161118-r3` · dual-write(실패기록|alert_status): order 1800|1800, product 3016|3016, shipping 4183|4183 · inbox: SENT|8999|0|8999|8999|0 · 생산자 상태: order=[ACKED|1800|0]; product=[ACKED|3016|0]; shipping=[ACKED|4183|0]

## k3-storm

| 지표 | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| 요청 | 30001 | 30001 | 30001 | 30001 |
| 의도치 않은 실패 | 0 | 0 | 0 | 0 |
| p50 ms | 24.4 | 23.1 | 24.4 | 25.1 |
| p95 ms | 44.3 | 43.0 | 44.3 | 46.4 |
| p99 ms | 53.5 | 48.4 | 65.2 | 53.5 |
| 정답 | 2981 | 2981 | 2973 | 2991 |
| 미도달 | 0 | 0 | 0 | 0 |
| 불명 | 0 | 0 | 0 | 0 |
| 도달 | 2981 | 2981 | 2973 | 2991 |
| 유실 | 0 | 0 | 0 | 0 |
| 중복 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 2981 | 2981 | 2973 | 2991 |
| 채널 거절 | 0 | 0 | 0 | 0 |
| e2e p50 ms | 152 | 145 | 152 | 154 |
| e2e p95 ms | 255 | 248 | 258 | 255 |
| e2e max ms | 283 | 273 | 475 | 283 |
| 감지 s | - | - | - | - |
| 운영 알림 | 0 | 0 | 0 | 0 |
| sink 오류 | 0 | 0 | 0 | 0 |

| 구간 | p50 ms (중앙값) | p95 ms (중앙값) | p99 ms (중앙값) | 의도치 않은 실패 (중앙값) |
|---|---|---|---|---|
| calm | 24.1 | 31.1 | 42.3 | 0 |
| storm | 34.2 | 53.2 | 57.6 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| `8083 alert_send_total{result="ok"}` | 2981 | 2981 | 2973 | 2991 |
| `8084 ack_total{result="ok",status="ACKED"}` | 2981 | 2981 | 2973 | 2991 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 2981 | 2981 | 2973 | 2991 |

- r1 `k3-storm-20261103T143129-r1` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 2981|2981 · inbox: SENT|2981|0|2981|2981|0 · 생산자 상태: order=[]; product=[]; shipping=[ACKED|2981|0]
- r2 `k3-storm-20261103T152813-r2` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 2973|2973 · inbox: SENT|2973|0|2973|2973|0 · 생산자 상태: order=[]; product=[]; shipping=[ACKED|2973|0]
- r3 `k3-storm-20261103T162456-r3` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 2991|2991 · inbox: SENT|2991|0|2991|2991|0 · 생산자 상태: order=[]; product=[]; shipping=[ACKED|2991|0]

## k6-fi07

| 지표 | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| 요청 | 3599 | 3598 | 3599 | 3600 |
| 의도치 않은 실패 | 1197 | 1300 | 1191 | 1197 |
| p50 ms | 25.2 | 25.5 | 25.2 | 24.1 |
| p95 ms | 240.0 | 373.8 | 240.0 | 80.3 |
| p99 ms | 1058.1 | 1230.1 | 1058.1 | 1023.3 |
| 정답 | 313 | 313 | 308 | 329 |
| 미도달 | 2 | 2 | 1 | 4 |
| 불명 | 66 | 68 | 66 | 59 |
| 도달 | 308 | 308 | 303 | 324 |
| 유실 | 5 | 5 | 5 | 5 |
| 중복 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 309 | 309 | 303 | 324 |
| 채널 거절 | 0 | 0 | 0 | 0 |
| e2e p50 ms | 135 | 135 | 130 | 136 |
| e2e p95 ms | 247 | 284 | 247 | 231 |
| e2e max ms | 1943 | 1902 | 2042 | 1943 |
| 감지 s | - | - | - | - |
| 운영 알림 | 0 | 0 | 0 | 0 |
| sink 오류 | 0 | 0 | 0 | 0 |

| 구간 | p50 ms (중앙값) | p95 ms (중앙값) | p99 ms (중앙값) | 의도치 않은 실패 (중앙값) |
|---|---|---|---|---|
| background | 25.2 | 217.0 | 1058.1 | 1197 |
| crash | 391.0 | 423.2 | 423.2 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| `8082 alert_send_total{result="ok"}` | 192 | 207 | 178 | 192 |
| `8083 alert_ack_resend_total` | 2 | 3 | 2 | 0 |
| `8083 alert_send_total{result="ok"}` | 20 | 13 | 21 | 20 |
| `8084 ack_total{result="error",status="ACKED"}` | 1 | 1 | 1 | 1 |
| `8084 ack_total{result="ok",status="ACKED"}` | 308 | 308 | 302 | 323 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 309 | 309 | 303 | 324 |

- r1 `k6-fi07-20261103T144006-r1` · dual-write(실패기록|alert_status): order 0|0, product 207|207, shipping 107|102 · inbox: SENT|309|0|309|312|0 · 생산자 상태: order=[]; product=[ACKED|207|0]; shipping=[ACKED|101|0, SENT|1|0]
- r2 `k6-fi07-20261103T153650-r2` · dual-write(실패기록|alert_status): order 0|0, product 178|178, shipping 130|125 · inbox: SENT|303|0|303|306|0 · 생산자 상태: order=[]; product=[ACKED|178|0]; shipping=[ACKED|124|0, SENT|1|0]
- r3 `k6-fi07-20261103T163332-r3` · dual-write(실패기록|alert_status): order 0|0, product 192|192, shipping 137|132 · inbox: SENT|324|0|324|327|0 · 생산자 상태: order=[]; product=[ACKED|192|0]; shipping=[ACKED|131|0, SENT|1|0]

## k6-fi09

| 지표 | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| 요청 | 15001 | 15001 | 15000 | 15001 |
| 의도치 않은 실패 | 0 | 0 | 0 | 0 |
| p50 ms | 21.0 | 21.0 | 21.7 | 20.9 |
| p95 ms | 43.5 | 43.5 | 45.8 | 41.6 |
| p99 ms | 84.8 | 118.2 | 84.8 | 63.2 |
| 정답 | 1487 | 1495 | 1444 | 1487 |
| 미도달 | 0 | 0 | 0 | 0 |
| 불명 | 0 | 0 | 0 | 0 |
| 도달 | 1487 | 1495 | 1444 | 1487 |
| 유실 | 0 | 0 | 0 | 0 |
| 중복 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 1487 | 1495 | 1444 | 1487 |
| 채널 거절 | 516 | 516 | 419 | 634 |
| e2e p50 ms | 166 | 166 | 168 | 164 |
| e2e p95 ms | 56420 | 56420 | 46179 | 68851 |
| e2e max ms | 72254 | 72254 | 60609 | 80267 |
| 감지 s | - | - | - | - |
| 운영 알림 | 0 | 0 | 0 | 0 |
| sink 오류 | 0 | 0 | 0 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| `8082 alert_send_total{result="ok"}` | 589 | 589 | 540 | 647 |
| `8083 alert_send_total{result="ok"}` | 1422 | 1422 | 1323 | 1474 |
| `8084 ack_total{result="ok",status="ACKED"}` | 1487 | 1495 | 1444 | 1487 |
| `8084 ack_total{result="ok",status="FAILED"}` | 516 | 516 | 419 | 634 |
| `8084 notifier_send_total{channel="slack",result="error"}` | 516 | 516 | 419 | 634 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 1487 | 1495 | 1444 | 1487 |
| `8084 retry_commands_total{result="ok"}` | 516 | 516 | 419 | 634 |

- r1 `k6-fi09-20261103T144645-r1` · dual-write(실패기록|alert_status): order 0|0, product 447|447, shipping 1048|1048 · inbox: SENT|1495|516|1495|2011|516 · 생산자 상태: order=[]; product=[ACKED|447|142]; shipping=[ACKED|1048|374]
- r2 `k6-fi09-20261103T154329-r2` · dual-write(실패기록|alert_status): order 0|0, product 419|419, shipping 1025|1025 · inbox: SENT|1444|419|1444|1863|419 · 생산자 상태: order=[]; product=[ACKED|419|121]; shipping=[ACKED|1025|298]
- r3 `k6-fi09-20261103T164012-r3` · dual-write(실패기록|alert_status): order 0|0, product 447|447, shipping 1040|1040 · inbox: SENT|1487|634|1487|2121|634 · 생산자 상태: order=[]; product=[ACKED|447|200]; shipping=[ACKED|1040|434]

채널 시도 시계열 (실행 시작 기준 30초 버킷, 수락/거절):

- r1: 0s 159/0 · 30s 136/0 · 60s 0/182 · 90s 0/334 · 120s 461/0 · 150s 135/0 · 180s 144/0 · 210s 148/0 · 240s 148/0 · 270s 162/0 · 300s 2/0
- r2: 0s 142/0 · 30s 157/0 · 60s 1/191 · 90s 0/228 · 120s 424/0 · 150s 137/0 · 180s 138/0 · 210s 142/0 · 240s 152/0 · 270s 151/0
- r3: 0s 145/0 · 30s 149/0 · 60s 0/270 · 90s 0/364 · 120s 434/0 · 150s 156/0 · 180s 154/0 · 210s 139/0 · 240s 148/0 · 270s 160/0 · 300s 2/0

## k6-fi14

| 지표 | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| 요청 | 3600 | 3601 | 3600 | 3600 |
| 의도치 않은 실패 | 0 | 0 | 0 | 0 |
| p50 ms | 15.9 | 15.7 | 15.9 | 21.3 |
| p95 ms | 35.7 | 35.5 | 35.7 | 46.6 |
| p99 ms | 52.6 | 52.6 | 50.8 | 66.7 |
| 정답 | 727 | 700 | 727 | 752 |
| 미도달 | 0 | 0 | 0 | 0 |
| 불명 | 0 | 0 | 0 | 0 |
| 도달 | 727 | 700 | 727 | 752 |
| 유실 | 0 | 0 | 0 | 0 |
| 중복 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 727 | 700 | 727 | 752 |
| 채널 거절 | 0 | 0 | 0 | 0 |
| e2e p50 ms | 130 | 129 | 130 | 137 |
| e2e p95 ms | 222 | 222 | 221 | 236 |
| e2e max ms | 258 | 258 | 247 | 373 |
| 감지 s | - | - | - | - |
| 운영 알림 | 0 | 0 | 0 | 0 |
| sink 오류 | 0 | 0 | 0 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 | r2 | r3 |
|---|---|---|---|---|
| `8083 alert_ack_resend_total` | 702 | 684 | 702 | 729 |
| `8083 alert_send_total{result="ok"}` | 2156 | 2084 | 2156 | 2233 |
| `8084 ack_total{result="error",status="ACKED"}` | 234 | 228 | 234 | 243 |
| `8084 ack_total{result="ok",status="ACKED"}` | 493 | 472 | 493 | 509 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 727 | 700 | 727 | 752 |

- r1 `k6-fi14-20261103T145522-r1` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 700|700 · inbox: SENT|700|0|700|2084|0 · 생산자 상태: order=[]; product=[]; shipping=[ACKED|472|0, SENT|228|0]
- r2 `k6-fi14-20261103T155205-r2` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 727|727 · inbox: SENT|727|0|727|2156|0 · 생산자 상태: order=[]; product=[]; shipping=[ACKED|493|0, SENT|234|0]
- r3 `k6-fi14-20261103T164849-r3` · dual-write(실패기록|alert_status): order 0|0, product 0|0, shipping 752|752 · inbox: SENT|752|0|752|2233|0 · 생산자 상태: order=[]; product=[]; shipping=[ACKED|509|0, SENT|243|0]

## obs-servicedown

| 지표 | 중앙값 | r1 | rplus1 | rplus2 | rplus3 |
|---|---|---|---|---|---|
| 요청 | 17948 | 17951 | 17947 | 17949 | 17942 |
| 의도치 않은 실패 | 9807 | 9858 | 10004 | 9612 | 9756 |
| p50 ms | 25.9 | 25.9 | 25.8 | 25.9 | 19.8 |
| p95 ms | 1026.9 | 1027.0 | 1027.7 | 1026.4 | 1026.8 |
| p99 ms | 1240.4 | 1151.9 | 1453.8 | 1043.2 | 1329.0 |
| 정답 | 1950.5 | 1941 | 1852 | 1986 | 1960 |
| 미도달 | 688.5 | 719 | 697 | 680 | 676 |
| 불명 | 61 | 61 | 72 | 34 | 61 |
| 도달 | 1950.5 | 1941 | 1852 | 1986 | 1960 |
| 유실 | 0 | 0 | 0 | 0 | 0 |
| 중복 | 0 | 0 | 0 | 0 | 0 |
| 예상 밖 | 0 | 0 | 0 | 0 | 0 |
| 사람이 본 알림 | 1964.5 | 1951 | 1870 | 1994 | 1978 |
| 채널 거절 | 0 | 0 | 0 | 0 | 0 |
| e2e p50 ms | 141.5 | 143 | 140 | 144 | 135 |
| e2e p95 ms | 244.5 | 250 | 247 | 242 | 237 |
| e2e max ms | 2533 | 2771 | 2369 | 2189 | 2697 |
| 감지 s | 46.2 | - | 47.6 | 46.2 | 44.2 |
| 운영 알림 | 4 | 0 | 4 | 4 | 4 |
| sink 오류 | 0 | 0 | 0 | 0 | 0 |

| 카운터 증분 (port metric) | 중앙값 | r1 | rplus1 | rplus2 | rplus3 |
|---|---|---|---|---|---|
| `8081 alert_send_total{result="ok"}` | 556.5 | 565 | 473 | 550 | 563 |
| `8082 alert_send_total{result="ok"}` | 886 | 886 | 894 | 886 | 881 |
| `8083 alert_ack_resend_total` | 0 | 6 | 0 | 0 | 0 |
| `8083 alert_send_total{result="ok"}` | 290.5 | 292 | 289 | 44 | 310 |
| `8084 ack_total{result="error",status="ACKED"}` | 1 | 2 | 2 | 0 | 0 |
| `8084 ack_total{result="ok",status="ACKED"}` | 1963.5 | 1949 | 1868 | 1994 | 1978 |
| `8084 notifier_send_total{channel="slack",result="ok"}` | 1964.5 | 1951 | 1870 | 1994 | 1978 |

- r1 `obs-servicedown-20261103T165526-r1` · dual-write(실패기록|alert_status): order 565|565, product 886|886, shipping 500|500 · inbox: SENT|1951|0|1951|1957|0 · 생산자 상태: order=[ACKED|565|0]; product=[ACKED|886|0]; shipping=[ACKED|498|0, SENT|2|0]
- rplus1 `obs-servicedown-20261103T170253-rplus1` · dual-write(실패기록|alert_status): order 473|473, product 894|894, shipping 503|503 · inbox: SENT|1870|0|1870|1876|0 · 생산자 상태: order=[ACKED|473|0]; product=[ACKED|894|0]; shipping=[ACKED|501|0, SENT|2|0]
- rplus2 `obs-servicedown-20261103T171000-rplus2` · dual-write(실패기록|alert_status): order 550|550, product 886|886, shipping 558|558 · inbox: SENT|1994|0|1994|1994|0 · 생산자 상태: order=[ACKED|550|0]; product=[ACKED|886|0]; shipping=[ACKED|558|0]
- rplus3 `obs-servicedown-20261103T171707-rplus3` · dual-write(실패기록|alert_status): order 563|563, product 881|881, shipping 534|534 · inbox: SENT|1978|0|1978|1978|0 · 생산자 상태: order=[ACKED|563|0]; product=[ACKED|881|0]; shipping=[ACKED|534|0]

