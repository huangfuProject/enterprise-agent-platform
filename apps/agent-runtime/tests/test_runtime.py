"""
Runtime 生命周期测试：用独立临时目录和显式 Fake 配置隔离进程、存储与模型。
"""

import time

import pytest
from fastapi.testclient import TestClient
from test_agent import request

from agent_runtime.api import create_app
from agent_runtime.config import ModelConfig
from agent_runtime.contracts import Status
from agent_runtime.runtime import Runtime
from agent_runtime.store import Conflict, RuntimeStore


def wait(store, execution_id, timeout=20):
    """
    有限轮询运行快照直到终态；用 monotonic 避免系统校时影响测试期限。
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot = store.get(execution_id)
        if snapshot.status in {Status.SUCCEEDED, Status.FAILED, Status.CANCELLED}:
            return snapshot
        time.sleep(0.05)
    pytest.fail("runtime did not terminate")


def test_http_submission_dedup_events(tmp_path):
    """
    通过真实应用生命周期验证重复受理、异载荷冲突、事件连续性和参数错误。
    """
    with TestClient(create_app(str(tmp_path), ModelConfig())) as client:
        req = request().model_dump(mode="json")
        assert client.post("/internal/v1/executions", json=req).status_code == 202
        assert client.post("/internal/v1/executions", json=req).status_code == 202
        changed = {**req, "input": {"message": "different", "values": [1]}}
        assert client.post("/internal/v1/executions", json=changed).status_code == 409
        result = wait(client.app.state.runtime.store, req["execution_id"])
        assert result.status == Status.SUCCEEDED
        assert result.checkpoint_id
        events = client.get("/internal/v1/executions/exec-1/events").json()
        assert sum(e["event_type"] == "execution_started" for e in events) == 1
        assert events[-1]["event_type"] == "execution_completed"
        assert [e["sequence"] for e in events] == list(range(1, len(events) + 1))
        assert client.get("/internal/v1/executions/missing").status_code == 404
        assert client.post("/internal/v1/executions", json={}).status_code == 422


def test_crash_before_start_and_runtime_reopen(tmp_path):
    """
    只写 ACCEPTED 而不启动进程，模拟受理后崩溃；重启必须识别未知执行。
    """
    store = RuntimeStore(str(tmp_path))
    store.accept(request())
    runtime = Runtime(str(tmp_path), ModelConfig())
    assert store.get("exec-1").error_code == "WORKER_LOST"
    runtime.close()


def test_process_killed_during_execution(tmp_path):
    """
    强杀真实子进程，监督线程必须把遗留执行置为 WORKER_LOST。
    """
    runtime = Runtime(str(tmp_path), ModelConfig())
    runtime.submit(request())
    with runtime.lock:
        process, _ = runtime.children["exec-1"]
        process.kill()
    result = wait(runtime.store, "exec-1")
    assert result.status == Status.FAILED
    assert result.error_code == "WORKER_LOST"
    runtime.close()


def test_cancel_and_timeout(tmp_path):
    """
    分别验证取消信号和进程总超时；延迟模型让超时场景可重复。
    """
    runtime = Runtime(str(tmp_path / "cancel"), ModelConfig())
    runtime.submit(request())
    runtime.store.cancel("exec-1")
    assert wait(runtime.store, "exec-1").status == Status.CANCELLED
    runtime.close()
    runtime = Runtime(str(tmp_path / "timeout"), ModelConfig(fake_delay_seconds=5))
    runtime.submit(request(limits={"timeout_seconds": 1}))
    snapshot = wait(runtime.store, "exec-1")
    assert snapshot.status == Status.FAILED
    assert snapshot.error_code == "EXECUTION_TIMEOUT"
    runtime.close()


def test_thread_collision_rejected(tmp_path):
    """
    不同任务复用同一 checkpoint thread 时拒绝，防止跨任务图状态串用。
    """
    store = RuntimeStore(str(tmp_path))
    store.accept(request())
    with pytest.raises(Conflict):
        store.accept(request(task_id="other", execution_id="other", idempotency_key="other"))


def test_cancel_before_delayed_submission(tmp_path):
    """
    先到取消请求必须形成持久墓碑，后到提交不能创建执行子进程。
    """
    with TestClient(create_app(str(tmp_path), ModelConfig())) as client:
        assert client.post("/internal/v1/executions/exec-1/cancel").status_code == 202
        response = client.post("/internal/v1/executions", json=request().model_dump(mode="json"))
        assert response.json()["status"] == "CANCELLED"
        assert client.app.state.runtime.children == {}
        assert [
            e["event_type"] for e in client.get("/internal/v1/executions/exec-1/events").json()
        ] == ["execution_cancelled"]


def test_terminal_state_and_event_are_immutable(tmp_path):
    """
    取消先持久化时压过成功，迟到失败也不能再增加互相矛盾的终态事件。
    """
    store = RuntimeStore(str(tmp_path))
    req = request()
    store.accept(req)
    store.cancel(req.execution_id)
    store.finish(req, Status.SUCCEEDED)
    store.finish(req, Status.FAILED, error_code="LATE_TIMEOUT")
    assert store.get(req.execution_id).status == Status.CANCELLED
    assert len(store.events(req.execution_id)) == 1


def test_runtime_directory_has_single_owner(tmp_path):
    """
    验证目录锁独占以及正常关闭后新实例能够接管。
    """
    first = Runtime(str(tmp_path), ModelConfig())
    with pytest.raises(RuntimeError, match="ALREADY_OWNED"):
        Runtime(str(tmp_path), ModelConfig())
    first.close()
    second = Runtime(str(tmp_path), ModelConfig())
    second.close()


def test_model_endpoint_unavailable(tmp_path, monkeypatch):
    """
    使用本地 HTTP503 端点走真实适配器错误路径，不访问外部付费模型。
    """
    import http.server
    import threading

    class Unavailable(http.server.BaseHTTPRequestHandler):
        """
        只实现拒绝响应的本地假 Provider，用于可重复的基础设施故障测试。
        """

        def do_POST(self):
            """
            所有模型请求都返回503，触发统一运行失败处理。
            """
            self.send_response(503)
            self.end_headers()

        def log_message(self, *args):
            """
            禁止测试服务默认输出请求日志，避免测试凭证意外进入输出。
            """

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Unavailable)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("P0_TEST_MODEL_KEY", "test-only-placeholder")
    config = ModelConfig(
        provider="openai-compatible",
        model="test",
        api_key_env="P0_TEST_MODEL_KEY",
        base_url=f"http://127.0.0.1:{server.server_address[1]}/v1",
        timeout=2,
    )
    runtime = Runtime(str(tmp_path), config)
    try:
        runtime.submit(request())
        snapshot = wait(runtime.store, "exec-1")
        assert snapshot.status == Status.FAILED
        assert snapshot.error_code == "AGENT_FAILED"
        assert "test-only-placeholder" not in snapshot.model_dump_json()
    finally:
        runtime.close()
        server.shutdown()
