# 대기열과 비슷한 데이터를 RESP로 만들어 redis-cli --pipe에 넘긴다. 변수: n(대기자 수)
# waiting ZSET에 (점수=순번, 멤버=36자 토큰)과, 토큰마다 TTL이 있는 토큰 키를 함께 넣는다
BEGIN {
  zk = "queue:1:waiting"
  for (i = 1; i <= n; i++) {
    t = sprintf("%08d-1111-4222-8333-%012d", i, i)
    tk = "queue:1:token:" t
    printf "*4\r\n$4\r\nZADD\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n$%d\r\n%s\r\n", length(zk), zk, length(i ""), i, length(t), t
    printf "*5\r\n$3\r\nSET\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n$2\r\nEX\r\n$3\r\n900\r\n", length(tk), tk, length(i ""), i
  }
}
