# Redis 실험 (redis-lab)

Redis의 동시성과 장애 상황을 일회용 컨테이너에서 재현하는 스크립트 모음. 제품 코드는 건드리지 않는다.

## 안전 장치

- `exp5-oom-http.sh`, `app-*.sh`, `bottleneck-app.sh`를 뺀 모든 컨테이너는 `tr-lab-` 접두어와 전용 네트워크 `tr-lab-net`만 쓴다. 이 스크립트들은 앱이 필요해 측정 스택(`tr-measure-*`)을 쓰며, 이미 같은 이름의 컨테이너가 있으면 거부하고 끝날 때 `down -v`로 지운다. 둘 다 개발 스택과는 별개다 `common.sh`가 그 밖의 컨테이너 이름을 거부하므로 개발 스택(`tr-redis`, `tr-postgres`)을 건드리지 않는다
- 각 스크립트는 종료할 때 자기가 만든 컨테이너와 네트워크를 지운다(`trap`). 컨테이너는 `docker rm -fv`로 지워 익명 볼륨도 함께 지운다. `redis:8-alpine` 이미지는 `docker image inspect`로 확인한 결과 `VOLUME`을 선언하지 않지만, `LAB_REDIS_IMAGE`로 다른 이미지를 쓰면 생길 수 있다. 스크립트가 강제 종료되어 정리가 안 되면 `docker rm -fv $(docker ps -aq --filter name=tr-lab-)`와 `docker network rm tr-lab-net`으로 직접 지운다
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

### 영속성과 정리 (AOF, lazyfree, 정리 스크립트)

모드는 `off`(영속성 없음), `rdb`(기본 스냅샷 규칙), `aof-no`, `aof-everysec`, `aof-always`. 공통 함수는 `aof-common.sh`.

| 스크립트 | 무엇을 보나 | 시간 |
|---|---|---|
| `aof-crash.sh` | 쓰기 중 SIGKILL 뒤 확인받은 쓰기가 몇 건 사라지나. 쓰기마다 고유 키를 쓰고 되살린 뒤 `MGET`으로 검증 | 약 10분 |
| `aof-throughput.sh` | 모드별 `SET` 처리량과 지연(`redis-benchmark`) | 약 5분 |
| `aof-load-rewrite.sh` | 대기열 규모 데이터의 재시작 로드 시간, 쓰기 부하 중 AOF rewrite와 `BGSAVE`의 지연과 컨테이너 메모리, 한도 근처에서의 fork | 약 6분 |
| `aof-recreate.sh` | 컨테이너 재생성(볼륨 유무)과 RDB에서 AOF로 전환하는 절차 | 1분 |
| `aof-lazyfree.sh` | lazyfree 설정별로 큰 ZSET을 `DEL`, `UNLINK`, TTL 만료로 지울 때 서버가 멈추는 시간 | 4분 |
| `aof-cleanup-script.sh` | 실제 정리 Lua 안에서 `UNLINK`와 `DEL`의 정지 비교, 50만 멤버 ZSET의 `MEMORY USAGE` | 3분 |
| `app-aof.sh` | 실제 앱에서 Redis를 SIGKILL로 죽였다 살렸을 때 줄 선 사람의 순번이 남는지(새 설정과 옛 설정 비교) | 5분 |
| `app-cleanup.sh` | 실제 앱에서 공연 시작 뒤 정리, 정리된 대기자의 401, 대기열이 비면 순번 키에 TTL | 5분 |
| `app-cap.sh` | 실제 앱에서 대기열 상한에 닿으면 503 `QUEUE_FULL`, 순번과 IP 발급 수를 쓰지 않는지, 정리된 공연의 등록이 닫히는지 | 5분 |

### 병목 분석 (Redis가 싱글스레드로 병목이 되는지)

| 스크립트 | 무엇을 보나 | 시간 |
|---|---|---|
| `bottleneck-redis-limit.sh` | 앱 없이 `redis-benchmark`가 실제 등록, 상태 조회 스크립트를 `EVALSHA`로 보내 한 코어의 상한을 잰다. `sampler.py`가 같은 시각에 서버 안쪽(메인 스레드 CPU, 호출당 시간, PING 지연)을 읽는다 | 약 8분 |
| `bottleneck-promote.sh` | 승격 배치 크기별 스크립트 한 번의 실행 시간과 그동안 다른 연결의 PING 지연 | 약 5분 |
| `bottleneck-rate.sh` | 같은 초당 조회 속도에서 대기자 규모만 바꿨을 때 메인 스레드와 컨테이너 전체 CPU | 약 3분 |
| `bottleneck-app.sh` | 하네스(`k6/measure-queue.sh`)의 단계를 돌리는 동안 측정 스택의 Redis를 관측 | 단계에 따라 10~20분 |
| `bottleneck-summarize.py` | `bottleneck-redis-limit.sh`의 산출물을 표로 요약 | 즉시 |
| `expire-storm.sh` | 토큰 키 50만 개가 같은 순간에 만료될 때(`MODES=aligned`)와 60초에 퍼져 만료될 때(`spread`)의 PING 지연, 메인 스레드 CPU, 만료에 걸린 시간, 만료 처리 CPU, AOF 증가. `expire-summarize.py`로 표를 만든다 | 약 15분 |
| `expire-summarize.py` | `expire-storm.sh`의 산출물을 표로 요약 | 즉시 |

변형은 환경 변수로 준다: `CPUS=2`(컨테이너 CPU 할당량), `EXTRA="--io-threads 2"`(서버 인자 추가), `MODES="aof aofno off"`, `WORKLOADS="register status"`, `CLIENTS`, `THREADS`.

환경변수로 규모를 바꾼다. 예: `N=1000000 REPS=5 ./exp3-bigkey.sh`, `CONFIGS="5000:plain 5000:wait" REPS=3 ./exp2-sentinel.sh`, `CONFIGS="plain minrep wait" PART_S=20 RUN_S=48 ./exp2-partition.sh`

## 해석할 때 주의

- `aof-crash.sh`는 프로세스 crash만 재현한다. 프로세스가 죽어도 `write()`까지 한 데이터는 운영체제가 들고 있어서 `appendfsync`의 `everysec`와 `always` 차이는 드러나지 않는다. OS나 전원 장애는 재현하지 못한다
- CPU 할당량이 1개인 컨테이너에서 AOF rewrite나 `BGSAVE`처럼 fork하는 작업이 돌면 자식이 같은 할당량을 나눠 써서 메인 스레드가 CFS 스로틀링으로 수십 ms 멈춘다. `bottleneck-redis-limit.sh`는 컨테이너의 `cpu.stat`(스로틀 횟수와 시간)을 함께 남기고 `CPUS=2`로 올리면 사라지는지 볼 수 있다
- 요청 하나가 메인 스레드에서 쓰는 시간은 부하에 따라 달라진다(낮은 부하에서 크고 포화에서 작다). 낮은 부하의 CPU 사용률을 선형으로 늘려 상한을 추정하지 말고 `bottleneck-redis-limit.sh`처럼 포화시켜 재야 한다
- `sampler.py`는 서버 CPU 할당량을 쓰지 않으려고 다른 컨테이너에서 `INFO`를 읽고, `INFO cpu`의 메인 스레드 값은 누적이라 두 시점의 차이를 경과 시간으로 나눠 쓴다
- `bottleneck-app.sh`와 `app-*.sh`는 앱 이미지를 빌드하고 측정 스택을 올렸다 지운다. 이미 `tr-measure-` 컨테이너가 있으면 거부한다
- `expire-storm.sh`는 요청 부하 없이 PING 하나로만 지연을 본다. 만료가 요청과 겹칠 때의 지연은 따로 재지 않고, AOF가 64MB 최소선을 넘으면 rewrite가 돌아 AOF 크기가 줄어 보이므로 증가량을 보려면 `EXTRA="--auto-aof-rewrite-percentage 0"`으로 rewrite를 끈다. `DBSIZE`는 아직 지우지 않은 만료 키도 센다

- 한 대의 컴퓨터 위 Docker라 실제 다중 노드의 네트워크 지연과 분단 양상은 다르다. 분단은 마스터 네임스페이스의 `iptables`로 흉내 낸 것이다
- 프로세스를 `docker kill`하는 시나리오는 이미 소켓으로 나간 복제 데이터를 커널이 전달하므로 비동기 복제 유실이 거의 드러나지 않는다. 유실을 보려면 분단 시나리오를 쓴다
- 분단 실험의 클라이언트는 연결된 마스터를 계속 쓰고 에러가 날 때만 Sentinel에 다시 묻는다(연결을 유지하는 일반 클라이언트의 동작을 가정)
- 락 실험의 임계구역은 공유 자원이 아니라 락을 얻고 놓은 시각으로 만든 구간이다. 정지 장면은 watchdog을 멈춰 흉내 낸 것이고, "둘 다 락을 가졌다고 믿은 시간"과 "실제로 둘 다 일한 시간"을 따로 낸다. 실제 데이터 유실은 공유 카운터 장면으로 본다
- 처리량 수치는 파이썬 클라이언트의 한계와 호스트 상태의 영향을 받는다. 절대값보다 설정 사이의 차이를 본다
