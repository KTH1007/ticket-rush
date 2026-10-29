# 실험 5: maxmemory(noeviction)에 닿았을 때 앱의 Lua 스크립트가 어떻게 되는지. 사용: oom_demo.py 호스트 포트
# 환경변수 PROMOTE_LUA, REGISTER_LUA, STATUS_LUA, SEAT_CLAIM_LUA에 앱 스크립트 내용을 넘긴다
import json, os, socket, sys

HOST, PORT = sys.argv[1], int(sys.argv[2])


class R:
    def __init__(self):
        self.s = socket.create_connection((HOST, PORT))
        self.f = self.s.makefile("rb")

    def cmd(self, *args):
        out = b"*%d\r\n" % len(args)
        for a in args:
            b = str(a).encode()
            out += b"$%d\r\n%s\r\n" % (len(b), b)
        self.s.sendall(out)
        return self._read()

    def try_cmd(self, *args):
        try:
            return self.cmd(*args)
        except RuntimeError as e:
            return f"오류: {e}"

    def _read(self):
        line = self.f.readline().rstrip(b"\r\n")
        t, rest = line[:1], line[1:]
        if t == b"+":
            return rest.decode()
        if t == b"-":
            raise RuntimeError(rest.decode())
        if t == b":":
            return int(rest)
        if t == b"$":
            n = int(rest)
            return None if n < 0 else self.f.read(n + 2)[:-2].decode()
        if t == b"*":
            n = int(rest)
            return None if n < 0 else [self._read() for _ in range(n)]
        raise RuntimeError(f"알 수 없는 응답: {line!r}")


def out(title, **kw):
    print(json.dumps({"항목": title, **kw}, ensure_ascii=False))


def mem(r):
    info = r.cmd("INFO", "memory")
    used = int([l for l in info.splitlines() if l.startswith("used_memory:")][0].split(":")[1])
    return round(used / 1048576, 2)


def count(r, pattern):
    n, cur = 0, "0"
    while True:
        cur, keys = r.cmd("SCAN", cur, "MATCH", pattern, "COUNT", 1000)
        n += len(keys)
        if cur == "0":
            return n


promote, register = os.environ["PROMOTE_LUA"], os.environ["REGISTER_LUA"]
status, claim = os.environ["STATUS_LUA"], os.environ["SEAT_CLAIM_LUA"]
r = R()
r.cmd("FLUSHALL")
maxmem = int(r.cmd("CONFIG", "GET", "maxmemory")[1]) / 1048576

# 대기열을 앱과 같은 모양으로 심는다: waiting ZSET, 토큰 키, 활성 이벤트 집합
W = 3000
for i in range(1, W + 1):
    r.cmd("ZADD", "queue:1:waiting", i, f"tok{i:05d}")
    r.cmd("SET", f"queue:1:token:tok{i:05d}", i, "EX", 900)
r.cmd("SADD", "queue:activeEventIds", 1)
r.cmd("SET", "queue:1:opensAt", 1, "EX", 900)       # register 스크립트의 일정 캐시 키가 있는 상태
r.cmd("SET", "queue:1:startsAt", 99999999999999, "EX", 900)
out("심은 상태", waiting=r.cmd("ZCARD", "queue:1:waiting"), used_mb=mem(r), maxmemory_mb=maxmem)

# 메모리를 가득 채운다(만료 없는 큰 문자열). 쓰기가 OOM으로 거부될 때까지
blob = "x" * 1024
i = 0
while True:
    res = r.try_cmd("SET", f"filler:{i}", blob)
    if isinstance(res, str) and res.startswith("오류"):
        out("가득 참", 거부된_쓰기=res, filler_keys=i, used_mb=mem(r))
        break
    i += 1

# OOM 상태에서 앱 스크립트들. 승격은 초당 예산(100) 때문에 같은 초에 연달아 부르면 0명이 되므로 1.1초 간격으로 부른다
import time
KEYS_PROMOTE = ["queue:1:waiting", "queue:1:lastPromotedSeq", "queue:activeEventIds"]
def promote_tick(label):
    before = r.cmd("ZCARD", "queue:1:waiting")
    res = r.try_cmd("EVAL", promote, 3, *KEYS_PROMOTE, 100, 120, 1, 100, 5, "queue:promotionBudget:")
    after = r.cmd("ZCARD", "queue:1:waiting")
    out(label, 결과=res if isinstance(res, str) else f"{len(res)}명 승격", waiting_전=before, waiting_후=after,
        활성_키_수=count(r, "queue:1:active:*"), lastPromotedSeq=r.cmd("GET", "queue:1:lastPromotedSeq"), used_mb=mem(r))
    return before - after
for tick in range(1, 7):
    promote_tick(f"승격 스크립트 {tick}번째 틱(OOM, 1.1초 간격)")
    time.sleep(1.1)

reg_keys = ["queue:1:seq", "queue:1:waiting", "queue:1:token:newtok", "queue:activeEventIds", "queue:1:opensAt", "queue:1:startsAt", "queue:1:ipIssued:1.2.3.4"]
zc = r.cmd("ZCARD", "queue:1:waiting")
res = r.try_cmd("EVAL", register, 7, *reg_keys, "newtok", 900, 1, 600, "", "", 100, 60)
out("등록 스크립트(OOM)", 결과=res, waiting_변화=r.cmd("ZCARD", "queue:1:waiting") - zc, seq_키=r.cmd("GET", "queue:1:seq"))
res = r.try_cmd("EVAL", status, 3, "queue:1:token:tok00500", "queue:1:active:tok00500", "queue:1:lastPromotedSeq", 900)
out("상태 조회 스크립트(OOM)", 결과=res)
res = r.try_cmd("EVAL", claim, 1, "seat:hold:1:1", "tokenA", 5000)
out("좌석 선점 스크립트(OOM)", 결과=res)

# 사람이 개입하지 않으면 회복하는가: 큐를 끝까지 승격시킨 뒤에도 메모리가 한도 아래로 내려오는지
for tick in range(7, 40):
    if r.cmd("ZCARD", "queue:1:waiting") == 0:
        break
    promote_tick(f"승격 스크립트 {tick}번째 틱(OOM)") if tick % 6 == 0 else None
    if tick % 6 != 0:
        r.try_cmd("EVAL", promote, 3, *KEYS_PROMOTE, 100, 120, 1, 100, 5, "queue:promotionBudget:")
    time.sleep(1.1)
out("큐를 모두 승격한 뒤", 틱=tick, waiting=r.cmd("ZCARD", "queue:1:waiting"), 활성_키_수=count(r, "queue:1:active:*"), used_mb=mem(r), maxmemory_mb=maxmem)
res = r.try_cmd("EVAL", register, 7, *reg_keys, "newtok", 900, 1, 600, "", "", 100, 60)
out("그 뒤 등록 스크립트", 결과=res, used_mb=mem(r))
r.cmd("DEL", *[f"filler:{k}" for k in range(0, 200)])
res = r.try_cmd("EVAL", register, 7, *reg_keys, "newtok2", 900, 1, 600, "", "", 100, 60)
out("filler 200개를 지운 뒤 등록 스크립트", 결과=res, used_mb=mem(r))
