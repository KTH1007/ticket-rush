# ZADD key score member를 RESP로 만들어 redis-cli --pipe에 넘긴다. 변수: n(개수), k(키)
BEGIN {
  for (i = 1; i <= n; i++) {
    m = sprintf("member:%08d", i)
    printf "*4\r\n$4\r\nZADD\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n$%d\r\n%s\r\n", length(k), k, length(i ""), i, length(m), m
  }
}
