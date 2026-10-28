# Redis 실험 (redis-lab)

Redis의 동시성과 장애 상황을 일회용 컨테이너에서 재현하는 스크립트 모음. 제품 코드는 건드리지 않는다.

## 안전 장치

- `exp5-oom-http.sh`를 뺀 모든 컨테이너는 `tr-lab-` 접두어와 전용 네트워크 `tr-lab-net`만 쓴다. `exp5-oom-http.sh`는 앱이 필요해 측정 스택(`tr-measure-*`)을 쓰며, 이미 같은 이름의 컨테이너가 있으면 거부하고 끝날 때 `down -v`로 지운다. 둘 다 개발 스택과는 별개다 `common.sh`가 그 밖의 컨테이너 이름을 거부하므로 개발 스택(`tr-redis`, `tr-postgres`)을 건드리지 않는다
- 각 스크립트는 종료할 때 자기가 만든 컨테이너와 네트워크를 지운다(`trap`). Redis 이미지는 `VOLUME`이 없어 볼륨이 남지 않는다
- 분단 실험만 `iptables`가 든 작은 이미지 `tr-lab-nettools`를 빌드하고, 끝나면 지운다

## 준비

- Docker, 호스트의 bash와 python3(결과 요약용)
- 이미지: `redis:8-alpine`, `python:3-alpine`(클라이언트 실행), 분단 실험은 `alpine`. 앱과 같은 Redis 버전을 쓰려면 `LAB_REDIS_IMAGE`로 바꾼다
- 실험 사이에 서로의 타이밍을 흔들지 않도록 한 번에 하나씩 실행한다. 맥이 잠들지 않게 `caffeinate -dims`를 앞에 붙이는 것을 권한다

## 실험

| 스크립트 | 무엇을 보나 | 시간 |
|---|---|---|
| `exp1-lock.sh` | 락이 깨지는 상황: TTL보다 긴 작업, 남의 락 `DEL`, watchdog과 정지, fencing token, 앱의 좌석 스크립트 | 1분 |
| `exp2-sentinel.sh` | 마스터 1, 레플리카 2, Sentinel 3에서 마스터를 `docker kill`. 단계별 시간과 `WAIT`의 영향. 설정은 `CONFIGS="down-after:모드"` | 설정당 약 1분 x 반복 |
| `exp2-partition.sh` | 마스터가 살아 있는 채 레플리카와 Sentinel에서만 끊김. 보호 없음, `min-replicas-to-write`, `WAIT` 비교 | 회당 약 1.2분 |
| `exp3-bigkey.sh`, `exp3-scale.sh` | 큰 ZSET을 `DEL`과 `UNLINK`로 지울 때 다른 연결의 지연, 크기별 확장 | 6분, 6분 |
| `exp4-pipeline.sh` | `redis-benchmark`로 파이프라인 크기별 처리량 | 1분 |
| `exp5-oom.sh` | `noeviction`에서 메모리가 가득 찼을 때 앱의 Lua 스크립트 동작 | 1분 |
| `exp5-oom-http.sh` | 같은 OOM 상태에서 **실제 앱의 HTTP 응답**: 등록, 승격된 사용자의 홀드, 상태 조회, 헬스, 한도를 푼 뒤 회복. 앱 이미지를 빌드해 약 5분 | 5분 |
| `exp6-rdb.sh` | 앱의 Redis 플래그에서 SIGKILL, SIGTERM, 재생성 뒤 복원되는 상태 | 3분 |

환경변수로 규모를 바꾼다. 예: `N=1000000 REPS=5 ./exp3-bigkey.sh`, `CONFIGS="5000:plain 5000:wait" REPS=3 ./exp2-sentinel.sh`, `CONFIGS="plain minrep wait" PART_S=20 RUN_S=48 ./exp2-partition.sh`

## 해석할 때 주의

- 한 대의 컴퓨터 위 Docker라 실제 다중 노드의 네트워크 지연과 분단 양상은 다르다. 분단은 마스터 네임스페이스의 `iptables`로 흉내 낸 것이다
- 프로세스를 `docker kill`하는 시나리오는 이미 소켓으로 나간 복제 데이터를 커널이 전달하므로 비동기 복제 유실이 거의 드러나지 않는다. 유실을 보려면 분단 시나리오를 쓴다
- 분단 실험의 클라이언트는 연결된 마스터를 계속 쓰고 에러가 날 때만 Sentinel에 다시 묻는다(연결을 유지하는 일반 클라이언트의 동작을 가정)
- 락 실험의 임계구역은 공유 자원이 아니라 락을 얻고 놓은 시각으로 만든 구간이다. 정지 장면은 watchdog을 멈춰 흉내 낸 것이고, "둘 다 락을 가졌다고 믿은 시간"과 "실제로 둘 다 일한 시간"을 따로 낸다. 실제 데이터 유실은 공유 카운터 장면으로 본다
- 처리량 수치는 파이썬 클라이언트의 한계와 호스트 상태의 영향을 받는다. 절대값보다 설정 사이의 차이를 본다
