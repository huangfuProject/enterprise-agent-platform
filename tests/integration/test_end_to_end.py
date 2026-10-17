"""Fake 模型跨进程验收：通过真实 HTTP 和 PostgreSQL 测试状态、事件、故障和恢复边界。"""
import os
import signal
import subprocess
import time
import uuid

import httpx
from conftest import poll


def create(stack, **overrides):
    """以随机幂等键提交有限测试任务；不复用开发环境的业务请求。"""
    body = {"idempotency_key": "it-" + uuid.uuid4().hex,
            "input": {"message": "统计样例", "values": [2, 4, 6]},
            "limits": {"max_steps": 20, "timeout_seconds": 30}, **overrides}
    response = httpx.post(stack["api"] + "/api/v1/tasks", json=body, timeout=10)
    assert response.status_code == 202, response.text
    return body, response.json()


def get(stack, task):
    """经公共 Java API 读取业务权威快照，而非直接用 Runtime 状态替代它。"""
    return httpx.get(stack["api"] + "/api/v1/tasks/" + task["task_id"], timeout=10).json()


def final(stack, task):
    """有限等待业务终态，要求失败场景也能退出而不是无限保持运行。"""
    return poll(lambda: (s if (s := get(stack, task))["state"] in
                         {"SUCCEEDED", "FAILED", "CANCELLED"} else None), timeout=30)


def test_real_chain_with_submission_timeout(stack):
    """延迟响应后仍取得唯一执行结果，检查真实工具事件、幂等及非法游标。"""
    body, task = create(stack)
    result = final(stack, task)
    assert result["state"] == "SUCCEEDED", result
    assert result["result"]["summary"]["total"] == 12
    assert result["result"]["specialist"]["mean"] == 4
    assert result["checkpoint_id"]
    assert stack["submit_count"][task["execution_id"]] == 1
    repeat = httpx.post(stack["api"] + "/api/v1/tasks", json=body, timeout=10).json()
    assert repeat["task_id"] == task["task_id"]
    events = httpx.get(stack["api"] + "/api/v1/tasks/" + task["task_id"] + "/events", timeout=10).json()
    assert sum(e["event_type"] == "execution_started" for e in events) == 1
    assert {e["payload"].get("tool") for e in events if e["event_type"] == "tool_completed"} == {
        "get_current_context", "calculate_summary", "task"}
    assert events[-1]["event_type"] == "task_succeeded"
    assert len({e["sequence"] for e in events}) == len(events)
    assert httpx.get(stack["api"] + "/api/v1/tasks/" + task["task_id"] + "/events?after=-1").status_code == 400


def test_worker_killed_during_model_execution(stack):
    """等待真实执行开始后定位并强杀 Worker，确认平台可观察失败。"""
    _, task = create(stack)
    poll(lambda: httpx.get(stack["runtime"] + "/internal/v1/executions/" + task["execution_id"],
                           timeout=2).json().get("status") == "RUNNING")
    listing = subprocess.check_output(["ps", "-axo", "pid,ppid,command"], text=True)
    pids = [int(parts[0]) for line in listing.splitlines()[1:]
            if len(parts := line.strip().split(None, 2)) == 3
            and parts[1] == str(stack["runtime_process"].pid) and "spawn_main" in parts[2]]
    assert pids, "no real worker process found"
    for pid in pids:
        os.kill(pid, signal.SIGKILL)
    result = final(stack, task)
    assert result["state"] == "FAILED"
    assert result["error_code"] == "WORKER_LOST"


def test_execution_deadline_and_python_error(stack):
    """分别制造进程总超时和步骤预算错误，覆盖 Python 失败向 Java 的传播。"""
    _, task = create(stack, limits={"max_steps": 20, "timeout_seconds": 1})
    result = final(stack, task)
    assert result["state"] == "FAILED"
    assert result["error_code"] == "EXECUTION_TIMEOUT"
    _, task = create(stack, limits={"max_steps": 1, "timeout_seconds": 30})
    assert final(stack, task)["state"] == "FAILED"


def test_cancel_and_idempotency_conflict(stack):
    """验证公共取消链路及相同幂等键绑定不同消息时的409。"""
    body, task = create(stack)
    response = httpx.post(stack["api"] + "/api/v1/tasks/" + task["task_id"] + "/cancel", timeout=10)
    assert response.status_code == 202
    assert final(stack, task)["state"] == "CANCELLED"
    body["input"]["message"] = "changed"
    assert httpx.post(stack["api"] + "/api/v1/tasks", json=body, timeout=10).status_code == 409


def test_database_disconnect_returns_503_and_recovers(stack):
    """关闭数据库代理后断言503，恢复代理后同一任务又能读取。"""
    _, task = create(stack)
    final(stack, task)
    stack["db_allowed"].clear()
    time.sleep(.3)
    try:
        response = httpx.get(stack["api"] + "/api/v1/tasks/" + task["task_id"], timeout=15)
        assert response.status_code == 503, response.text
        assert response.json()["code"] == "DATABASE_UNAVAILABLE"
        assert response.headers["X-Trace-Id"]
    finally:
        stack["db_allowed"].set()
    poll(lambda: httpx.get(stack["api"] + "/api/v1/tasks/" + task["task_id"], timeout=6).status_code == 200)


def test_java_serialization_matches_python_contract():
    """读取 Java 测试实际输出，以 Pydantic 和 JSON Schema 双重校验跨语言编码。"""
    import json
    from pathlib import Path
    import jsonschema
    from agent_runtime.contracts import TaskExecutionRequest
    root = Path(__file__).resolve().parents[2]
    payload = json.loads((root / "apps/platform-api/target/contract/java-request.json").read_text())
    checked = TaskExecutionRequest.model_validate(payload)
    jsonschema.validate(payload, json.loads((root / "packages/contracts/v1/TaskExecutionRequest.schema.json").read_text()))
    assert checked.input.values == [1, 2, 3]


def test_z_runtime_server_killed_lease_expires(stack):
    """最后强杀 Runtime 服务；让 Java 以测试租约识别整个服务失联并失败关闭。"""
    _, task = create(stack)
    poll(lambda: httpx.get(stack["runtime"] + "/internal/v1/executions/" + task["execution_id"],
                           timeout=2).json().get("status") == "RUNNING")
    stack["runtime_process"].kill()
    stack["runtime_process"].wait(timeout=5)
    result = final(stack, task)
    assert result["state"] == "FAILED"
    assert result["error_code"] == "WORKER_LOST"
