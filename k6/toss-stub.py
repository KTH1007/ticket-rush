#!/usr/bin/env python3
"""Toss Payments 호환 가짜 서버. 가짜 PG 어댑터는 서킷 차단기와 HTTP 타임아웃을 안 타서, 그 경로를 재려고 호스트에서 띄운다.
표준 라이브러리만 쓴다. 컨테이너의 앱이 host.docker.internal:<포트>로 부른다.

  python3 k6/toss-stub.py [포트]

제어(앱이 아니라 러너가 부른다)
  POST /__mode?delay_ms=<응답 전 대기>&status=<200 또는 오류 상태 코드>   이후 요청의 동작을 바꾼다
  GET  /__stats                                                           호출 수와 초별 호출 수
  POST /__reset                                                           호출 수와 승인 기록을 지운다

승인 요청은 대기가 끝난 뒤에 기록하므로, 앱이 타임아웃으로 먼저 끊어도 Toss에서는 승인된 상태가 된다(응답을 놓친 승인).
이 경우 회수 스케줄러의 조회가 DONE을 받아야 한다.
"""
import json
import sys
import threading
import time
from collections import Counter
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

lock = threading.Lock()
mode = {"delay_ms": 0, "status": 200}
payments = {}  # paymentKey -> {"orderId", "amount", "status"}
calls = Counter()  # confirm / inquire / cancel
per_second = {"confirm": Counter(), "inquire": Counter(), "cancel": Counter()}


def now_iso():
    return datetime.now(timezone(timedelta(hours=9))).isoformat(timespec="seconds")


framing = Counter()


def record_framing(kind):
    with lock:
        framing[kind] += 1


def record(kind):
    with lock:
        calls[kind] += 1
        per_second[kind][int(time.time())] += 1


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, code, body):
        raw = json.dumps(body).encode()
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 앱이 타임아웃으로 먼저 끊은 경우

    def read_body(self):
        # HttpURLConnection은 길이를 모르면 chunked로 보낸다. Content-Length만 읽으면 본문이 비어 보인다
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            record_framing("chunked")
            raw = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                raw += self.rfile.read(size)
                self.rfile.readline()
        else:
            record_framing("length")
            raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        return json.loads(raw) if raw else {}

    def current_mode(self):
        with lock:
            return mode["delay_ms"], mode["status"]

    def fail_or_none(self, status):
        if status >= 400:
            self.send_json(status, {"code": "PROVIDER_ERROR", "message": "stub 오류 응답"})
            return True
        return False

    def do_POST(self):
        url = urlparse(self.path)
        path = url.path
        if path == "/__mode":
            q = parse_qs(url.query)
            with lock:
                mode["delay_ms"] = int(q.get("delay_ms", [mode["delay_ms"]])[0])
                mode["status"] = int(q.get("status", [mode["status"]])[0])
            return self.send_json(200, dict(mode))
        if path == "/__reset":
            with lock:
                calls.clear()
                framing.clear()
                payments.clear()
                for c in per_second.values():
                    c.clear()
            return self.send_json(200, {"reset": True})

        body = self.read_body()
        delay_ms, status = self.current_mode()
        if path == "/v1/payments/confirm":
            record("confirm")
            time.sleep(delay_ms / 1000)
            if self.fail_or_none(status):
                return
            key = body.get("paymentKey")
            with lock:
                payments[key] = {"orderId": body.get("orderId"), "amount": body.get("amount"), "status": "DONE"}
            return self.send_json(200, {"paymentKey": key, "status": "DONE", "approvedAt": now_iso(),
                                        "orderId": body.get("orderId"), "totalAmount": body.get("amount")})
        if path.startswith("/v1/payments/") and path.endswith("/cancel"):
            record("cancel")
            time.sleep(delay_ms / 1000)
            if self.fail_or_none(status):
                return
            key = path.split("/")[3]
            with lock:
                if key in payments:
                    payments[key]["status"] = "CANCELED"
            return self.send_json(200, {"paymentKey": key, "status": "CANCELED"})
        self.send_json(404, {"code": "NOT_FOUND", "message": path})

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/__stats":
            with lock:
                return self.send_json(200, {
                    "calls": dict(calls),
                    "framing": dict(framing),
                    "mode": dict(mode),
                    "approved": len(payments),
                    "per_second": {k: {str(t): n for t, n in sorted(v.items())} for k, v in per_second.items()},
                })
        if path.startswith("/v1/payments/"):
            record("inquire")
            delay_ms, status = self.current_mode()
            time.sleep(delay_ms / 1000)
            if self.fail_or_none(status):
                return
            key = path.split("/")[3]
            with lock:
                payment = payments.get(key)
            if payment is None:
                return self.send_json(404, {"code": "NOT_FOUND_PAYMENT", "message": "없는 결제"})
            return self.send_json(200, {"paymentKey": key, "status": payment["status"], "approvedAt": now_iso(),
                                        "orderId": payment["orderId"], "totalAmount": payment["amount"], "balanceAmount": payment["amount"]})
        self.send_json(404, {"code": "NOT_FOUND", "message": path})


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 1024


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18090
    Server(("127.0.0.1", port), Handler).serve_forever()
