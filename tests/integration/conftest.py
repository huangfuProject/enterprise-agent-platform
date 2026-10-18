"""
跨进程测试夹具：真实 Java/Python/PostgreSQL，通过本地代理注入通信故障。
"""
import contextlib
import http.server
import os
import select
import socket
import socketserver
import subprocess
import sys
import threading
import time
from pathlib import Path

import httpx
import psycopg
import pytest

ROOT = Path(__file__).resolve().parents[2]


def free_port():
    """
    向系统申请一个暂时空闲的本地端口；关闭探测 socket 后交给测试进程使用。
    """
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def poll(fn, timeout=30):
    """
    在有限期限内重试观察条件；只忽略预期的连接/暂缺数据，断言错误直接暴露。
    """
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            value = fn()
            if value:
                return value
        except (httpx.HTTPError, KeyError, psycopg.OperationalError):
            pass
        time.sleep(.05)
    pytest.fail("condition did not become true before deadline")


@pytest.fixture(scope="session")
def stack(tmp_path_factory):
    """
    启动隔离测试栈并在结束时回收自建进程/代理。

    使用专用 eap_p0_test，明确强制 Fake；不因开发机配置真实模型而产生意外调用。
    数据库故障只关闭本地转发连接，不停止远端 PostgreSQL 或已有服务。
    """
    temp = tmp_path_factory.mktemp("eap-stack")
    cfg = dict(line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines()
               if line and not line.startswith("#") and "=" in line)
    cfg.update({k: v for k, v in os.environ.items() if k.startswith(("POSTGRES_", "REDIS_", "LOCK_"))})
    cfg["POSTGRES_DB"] = "eap_p0_test"
    runtime_port, api_port = free_port(), free_port()
    # 一个开关同时影响现有代理连接与后续连接，模拟数据库断连而不触碰共享 DB。
    allowed = threading.Event()
    allowed.set()

    class PgProxy(socketserver.BaseRequestHandler):
        """
        本地 TCP 故障边界；allowed 清除后终止已有转发并拒绝新连接。
        """
        def handle(self):
            """
            双向转发 PostgreSQL 字节流；按短 select 周期检查故障开关。
            """
            if not allowed.is_set():
                return
            try:
                with socket.create_connection((cfg["POSTGRES_HOST"], int(cfg["POSTGRES_PORT"])), timeout=3) as upstream:
                    sockets = [self.request, upstream]
                    while allowed.is_set():
                        ready, _, _ = select.select(sockets, [], [], .05)
                        for source in ready:
                            data = source.recv(65536)
                            if not data:
                                return
                            target = upstream if source is self.request else self.request
                            target.sendall(data)
            except (OSError, ValueError):
                return

    pg_proxy = socketserver.ThreadingTCPServer(("127.0.0.1", 0), PgProxy)
    pg_proxy.daemon_threads = True
    threading.Thread(target=pg_proxy.serve_forever, daemon=True).start()
    submit_count = {}

    class RuntimeProxy(http.server.BaseHTTPRequestHandler):
        """
        转发真实 Runtime 请求，故意延迟提交响应以构造结果未知窗口。
        """
        def log_message(self, *args):
            """
            关闭默认 HTTP 日志；仅保存测试需要的有界证据。
            """
            pass

        def forward(self):
            """
            先把请求送达 Runtime，再延迟 Java 可见响应，证明客户端超时不等于执行停止。
            """
            payload = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            try:
                response = httpx.request(self.command, f"http://127.0.0.1:{runtime_port}{self.path}",
                                         content=payload, headers={"Content-Type": "application/json"}, timeout=5)
                if self.command == "POST" and self.path == "/internal/v1/executions":
                    import json
                    execution = json.loads(payload)["execution_id"]
                    submit_count[execution] = submit_count.get(execution, 0) + 1
                    # 先持久受理再延迟响应；重复启动次数必须仍为1，不能只验证 HTTP 返回码。
                    time.sleep(.5)
                self.send_response(response.status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(response.content)))
                self.end_headers()
                self.wfile.write(response.content)
            except (httpx.HTTPError, OSError):
                pass
        do_GET = forward
        do_POST = forward

    proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RuntimeProxy)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    env = {**os.environ, **cfg, "RUNTIME_DATA_DIR": str(temp / "runtime"),
           "P0_FAKE_DELAY_SECONDS": ".3", "MODEL_PROVIDER": "fake"}
    runtime_log = open(temp / "runtime.log", "w")
    api_log = open(temp / "api.log", "w")
    runtime = subprocess.Popen([sys.executable, "-m", "uvicorn", "agent_runtime.api:create_app", "--factory",
                               "--port", str(runtime_port)], cwd=ROOT, env=env,
                               stdout=runtime_log, stderr=subprocess.STDOUT)
    env.update({"POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": str(pg_proxy.server_address[1]),
                "API_PORT": str(api_port), "LEASE_SECONDS": "4", "DISPATCH_MS": "200",
                "RUNTIME_HTTP_TIMEOUT_MS": "250", "RUNTIME_URL": f"http://127.0.0.1:{proxy.server_address[1]}"})
    api = subprocess.Popen(["java", "-jar", str(ROOT / "apps/platform-api/target/platform-api-0.1.0.jar")],
                           cwd=ROOT, env=env, stdout=api_log, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{api_port}"
    runtime_base = f"http://127.0.0.1:{runtime_port}"
    try:
        poll(lambda: httpx.get(base + "/actuator/health", timeout=1).status_code == 200)
        poll(lambda: httpx.get(runtime_base + "/health", timeout=1).status_code == 200)
        yield {"api": base, "runtime": runtime_base, "runtime_process": runtime,
               "db_allowed": allowed, "submit_count": submit_count, "temp": temp, "db_config": cfg}
    finally:
        # 恢复故障开关再关进程/代理，保证失败用例也不会留下测试基础设施。
        allowed.set()
        for process in [api, runtime]:
            if process.poll() is None:
                process.terminate()
                with contextlib.suppress(subprocess.TimeoutExpired):
                    process.wait(timeout=10)
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
        proxy.shutdown()
        pg_proxy.shutdown()
        runtime_log.close()
        api_log.close()
