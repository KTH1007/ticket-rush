# 토큰 키 n개를 SET ... PXAT 로 만드는 RESP 스트림 (redis-cli --pipe 입력)
# mode=aligned: 모든 키가 base(밀리초 절대 시각)에 만료. mode=spread: base부터 spread_ms 사이에 고르게 만료
BEGIN {
  srand(1)
  for (i = 0; i < n; i++) {
    k = sprintf("queue:1:token:%012d", i); v = i + 1
    ts = sprintf("%.0f", base + (mode == "spread" ? int(rand() * spread_ms) : 0))
    printf "*5\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n$4\r\nPXAT\r\n$%d\r\n%s\r\n", length(k), k, length(v ""), v, length(ts), ts
  }
}
