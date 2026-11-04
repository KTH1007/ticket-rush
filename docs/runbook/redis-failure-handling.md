# Redis 장애 대응

Redis에는 대기열(순번, 토큰, 활성 키)과 좌석 선점의 1차 필터가 있다. 대기열 상태는 DB에 없고 Redis에만 있다. 좌석의 최종 판정은 DB(`UPDATE ... WHERE status = AVAILABLE`)가 하므로 Redis가 비거나 죽어도 이중 판매는 생기지 않는다. 잃는 것은 대기열 순번과 선점 표시다.

## 현재 설정

`docker-compose.yml`의 Redis 기준이다. 측정용 `k6/docker-compose.measure.yml`도 같은 값을 쓴다.

| 설정 | 값 | 이유 |
|---|---|---|
| 영속성 | AOF `appendfsync everysec`, `--save ""` | 순번과 토큰이 Redis에만 있어 재시작 뒤에도 줄이 남게 한다. AOF가 있으면 시작 때 RDB는 복원에 쓰이지 않아 자동 스냅샷은 끈다 |
| 볼륨 | `redis-data` (실제 이름 `ticket-rush_redis-data`) | 컨테이너를 다시 만들어도 AOF가 남는다 |
| 메모리 | `maxmemory 512mb`, `noeviction` | evict되면 좌석 홀드가 조용히 사라진다. 차라리 쓰기를 실패시킨다 |
| lazyfree | `lazyfree-lazy-user-del yes`, `lazyfree-lazy-expire yes` | 큰 키를 `DEL`이나 TTL 만료로 지워도 서버가 멈추지 않게 한다 |

## 증상별 대응

### 1. Redis가 재시작됐다

| 상황 | 대기열 | 비고 |
|---|---|---|
| 프로세스 재시작(crash, `docker restart`) | **남는다**. AOF로 복원되어 줄 선 사람의 순번이 유지된다 | 프로세스 crash 시험에서는 확인받은 쓰기 유실이 0건이었다. OS나 전원 장애는 재현하지 못했고 `everysec`라 마지막 약 1초가 사라질 수 있다 |
| 컨테이너 재생성(`down` 뒤 `up`, 이미지 교체) | **남는다**. 이름 있는 볼륨에 있다 | 볼륨이 없던 컨테이너는 재생성하면 비어서 시작한다 |
| 볼륨 삭제(`down -v`, `docker volume rm`) | **사라진다**. 사용자는 다시 줄을 선다 | 좌석 이중 판매는 없다(DB가 판정) |
| 시작 직후 `LOADING` | 503과 `Retry-After`로 거절된다 | 50만 명 규모의 로드는 약 0.3~0.6초였다 |

### 2. 메모리가 가득 찼다 (OOM)

- **증상**: 새 줄서기 등록과 좌석 홀드가 **503 `TEMPORARILY_UNAVAILABLE`** 과 `Retry-After` 5~10초로 거절된다. 순번 조회는 200으로 계속된다. **헬스는 계속 `UP`** 이라 헬스로는 알 수 없다.
- **확인**: `redis-cli INFO memory`의 `used_memory`와 `maxmemory`, `DBSIZE`, `INFO keyspace`.
- **알아 둘 점**: 승격은 OOM에서도 계속되지만 홀드는 막힌다(첫 홀드의 `SET`이 거부됨). 승격된 사용자가 홀드하지 못한 채 활성 시간(`active-ttl` 2분)을 소진할 수 있다. 승격을 멈추는 처리는 없다.
- **회복**: 승격은 메모리를 줄이지 못한다. 한도 아래로 충분히 내려와야 하고 토큰 TTL(15분)과 활성 TTL(2분)이 지나야 줄어든다. 급하면 `CONFIG SET maxmemory`로 올리면 즉시 회복된다(컨테이너 `mem_limit` 1g 안에서, 재시작하면 원래 값으로 돌아간다).
- **예방**: 대기열 길이 상한(아래 3)이 토큰 키 수를 막는다.

### 3. 등록이 `QUEUE_FULL` 503으로 거절된다

- 공연 하나의 대기열이 상한(`ticket-rush.queue.max-waiting`, 기본 50만)에 닿았다는 뜻이고 의도된 거절이다. 응답에 `Retry-After` 5~10초가 붙는다.
- 응답에는 `Cache-Control: max-age=1`도 붙는다. nginx가 같은 공연의 줄서기 거절을 앱에 묻지 않고 그대로 돌려준다(응답 헤더 `X-Cache-Status: HIT`). nginx가 만료를 초 단위 시계로 판정해 실제로는 1~2초(실측 약 1.4초) 유지된다. 그래서 대기열에 자리가 나도 그만큼은 계속 거절될 수 있고, 거절 응답의 `Retry-After`는 그 시간 안의 요청이 모두 같은 값이다. nginx를 거치지 않는 구성(ALB만 있는 경우)에서는 이 재사용이 없다.
- 거절 횟수는 Micrometer 카운터 `queue.register.rejected`(태그 `reason=full`)로 센다. 장애로 인한 503과 구분하려고 따로 두었다.
- 상한은 설정 `ticket-rush.queue.max-waiting`(환경 변수 `TICKET_RUSH_QUEUE_MAX_WAITING`)이고 바꾸려면 재배포가 필요하다.
- `waiting`에는 만료된 토큰이 승격 때까지 남아 `ZCARD`가 실제 대기자보다 클 수 있다. 상한이 예상보다 일찍 닿으면 이것을 의심한다(유령을 지우는 처리는 아직 없다).

### 4. 큰 키를 지워야 한다

- `DEL` 대신 **`UNLINK`** 를 쓴다. 50만 멤버 ZSET의 `DEL`은 약 28ms 동안 서버 전체를 멈추고 `UNLINK`는 약 0.3ms다.
- lazyfree 설정이 켜져 있어 `DEL`도 멈추지 않지만, 설정이 없는 환경(개발, 다른 인스턴스)에서는 멈춘다.
- `waiting` ZSET에는 TTL을 걸지 않는다. 정리 스케줄러가 `UNLINK`로 지운다.

### 5. 공연 시작 뒤 대기열 정리

- 정리 스케줄러(1분 주기)가 공연 시작 시각 + 1시간(`ticket-rush.queue.cleanup.grace-after-start`)이 지난 공연의 `waiting`, `seq`, `lastPromotedSeq`를 `UNLINK`로 지우고 활성 공연 집합에서 뺀다. 정리 표시 `queue:{공연}:cleaned`는 24시간(`retention`) 남는다.
- 정리된 공연의 **조회는 401 `INVALID_QUEUE_TOKEN`**, **새 등록은 409 `QUEUE_CLOSED`** 다. 정리 뒤에도 대기자의 token 키는 남지만 정리 표시 때문에 무효로 본다.
- 정리를 되돌려 공연을 다시 열어야 할 때(공연 시각을 늦춘 경우): DB에서 시각을 바꾼 뒤 `DEL queue:{공연}:cleaned queue:{공연}:opensAt queue:{공연}:startsAt`. 일정 캐시가 최대 10분 이전 시각을 들고 있어서 함께 지운다. 코드를 읽어 정리한 절차이고 실제로 해 보지는 않았다.

### 6. 승격 속도를 올리고 싶다

승격 스크립트는 끊김 없이 실행되어 길수록 그동안 모든 요청이 기다린다.

| 한 번에 승격하는 수 | 스크립트 실행 시간 |
|---|---|
| 100 (지금 설정) | 2~6ms |
| 1,000 | 11~19ms |
| 5,000 | 약 42ms |
| 20,000 | 약 100ms |

- 지금 설정(초당 100명, 1초 주기)은 문제가 없다.
- 속도를 올릴 때는 한 번의 배치를 키우지 말고 주기를 짧게 나눈다(예: 100ms마다 초당 예산의 1/10). 예산은 초 단위 키로 세므로 합계는 유지되겠지만 올리기 전에 코드로 확인한다.

### 7. 지연이 잠깐 튄다

- **대량 만료**: 토큰 50만 개가 같은 순간에 만료되면 약 2초 동안 PING 최대가 28~31ms(평소 1~3ms)까지 오른다. 실패는 없었다. 토큰 TTL은 15분이고 조회마다 연장되어 보통은 흩어진다.
- **AOF rewrite와 CPU 한도**: Redis 컨테이너에 CPU 한도를 **1개**로 걸면 rewrite가 fork한 자식이 같은 할당량을 쓰면서 메인 스레드가 45~58ms 멈춘다. 한도를 걸지 않거나 2개 이상으로 둔다. 운영 compose는 `mem_limit`만 있다. 쿠버네티스의 `resources.limits.cpu`도 같다.
- **확인 지표**: `INFO stats`의 `expired_keys` 증가율, `expire_cycle_cpu_milliseconds`, `expired_time_cap_reached_count`, `redis-cli --latency`. 능동 만료가 `SLOWLOG`에 나오는지는 확인하지 않았다.

## RDB로 돌던 인스턴스를 AOF로 바꾸기

- **함정**: 기존 인스턴스에 `--appendonly yes`만 주고 재시작하면 AOF 파일이 없어 **빈 상태로 시작한다**. 같은 디렉터리의 `dump.rdb`는 복원에 쓰이지 않는다.
- **절차**: (1) 떠 있는 인스턴스에서 `redis-cli CONFIG SET appendonly yes` (2) `redis-cli INFO persistence`에서 `aof_rewrite_in_progress:0`, `aof_last_bgrewrite_status:ok` 확인 (3) 새 설정으로 컨테이너를 다시 만든다. (1)과 (3)의 순서로 값이 보존되는 것을 확인했고, (2)는 rewrite가 끝났는지 보는 확인 단계다.
- 볼륨이 없던 컨테이너는 (3)에서 데이터가 사라진다. 개발 스택의 `tr-redis`가 이 경우이므로 새 compose를 적용하면 비워진다.

## 진단 명령

| 명령 | 보는 것 | 주의 |
|---|---|---|
| `SLOWLOG GET 10` | 오래 걸린 명령 | 명령 실행 시간만 잰다. 네트워크와 큐 대기는 포함하지 않는다 |
| `INFO commandstats` | 명령별 호출 수와 호출당 시간 | 서버 실행 시간만이다. `EVALSHA`의 호출당 시간이 스크립트 길이를 보여 준다 |
| `INFO cpu` | 메인 스레드 CPU(`used_cpu_*_main_thread`) | 누적값이라 두 시점의 차이를 경과 시간으로 나눠 쓴다 |
| `redis-cli --latency`, `--latency-history` | PING 왕복 | 긴 명령이 메인 스레드를 막으면 같이 올라간다 |
| `MEMORY USAGE <키>` | 키 하나의 메모리 | 50만 멤버 ZSET은 약 52MiB다 |
| `INFO stats` | 만료, 초당 명령 수 | `expired_keys`, `instantaneous_ops_per_sec` |

실험 스크립트와 사용법은 `scripts/redis-lab/README.md`에 있다.
