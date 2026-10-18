"""
真实模型端到端验收；必须显式 RUN_LIVE_MODEL，使用独立 live 数据库避免 Fake 竞争。
"""

import contextlib
import os
import subprocess
import sys
import uuid
from pathlib import Path

import httpx
import psycopg
import pytest
from conftest import ROOT, free_port, poll


@pytest.mark.skipif(
    os.getenv("RUN_LIVE_MODEL") != "true", reason="RUN_LIVE_MODEL not enabled"
)
def test_live_model_java_runtime_chain(tmp_path):
    """
    启动自建 Java/Runtime 进程，验证指定真实模型的工具、子 Agent、结果和事件。

    只保留有限结构化证据，不把环境、HTTP 认证头或模型密钥写入报告；
    结束后回收本测试启动的进程，不删除数据库或影响开发服务。
    """
    settings = dict(
        line.split("=", 1)
        for line in (ROOT / ".env").read_text().splitlines()
        if line and not line.startswith("#") and "=" in line
    )
    env = {**os.environ, **settings}
    with psycopg.connect(
        host=env["POSTGRES_HOST"],
        port=env["POSTGRES_PORT"],
        dbname=env["POSTGRES_DB"],
        user=env["POSTGRES_USER"],
        password=env["POSTGRES_PASSWORD"],
        connect_timeout=5,
        autocommit=True,
    ) as db:
        if not db.execute(
            "SELECT 1 FROM pg_database WHERE datname='eap_p0_live_test'"
        ).fetchone():
            db.execute("CREATE DATABASE eap_p0_live_test")
    # Explicit test invocation selects the provider; merely storing a key never enables it.
    env.update(
        {
            "MODEL_PROVIDER": "openai-compatible",
            "MODEL_NAME": os.getenv(
                "MODEL_NAME", settings.get("MODEL_NAME", "qwen-plus")
            ),
            "POSTGRES_DB": "eap_p0_live_test",
            "RUNTIME_DATA_DIR": str(tmp_path / "runtime"),
        }
    )
    runtime_port, api_port = free_port(), free_port()
    env.update(
        {"API_PORT": str(api_port), "RUNTIME_URL": f"http://127.0.0.1:{runtime_port}"}
    )
    processes = []
    with (
        open(tmp_path / "runtime.log", "w") as runtime_log,
        open(tmp_path / "java.log", "w") as java_log,
    ):
        try:
            processes.append(
                subprocess.Popen(
                    [
                        sys.executable,
                        "-m",
                        "uvicorn",
                        "agent_runtime.api:create_app",
                        "--factory",
                        "--port",
                        str(runtime_port),
                    ],
                    cwd=ROOT,
                    env=env,
                    stdout=runtime_log,
                    stderr=subprocess.STDOUT,
                )
            )
            processes.append(
                subprocess.Popen(
                    [
                        "java",
                        "-jar",
                        str(ROOT / "apps/platform-api/target/platform-api-0.1.0.jar"),
                    ],
                    cwd=ROOT,
                    env=env,
                    stdout=java_log,
                    stderr=subprocess.STDOUT,
                )
            )
            base = f"http://127.0.0.1:{api_port}"
            poll(
                lambda: (
                    httpx.get(base + "/actuator/health", timeout=2).status_code == 200
                )
            )
            poll(
                lambda: (
                    httpx.get(
                        f"http://127.0.0.1:{runtime_port}/health", timeout=2
                    ).status_code
                    == 200
                )
            )
            response = httpx.post(
                base + "/api/v1/tasks",
                json={
                    "idempotency_key": "live-" + uuid.uuid4().hex,
                    "input": {
                        "message": "请统计输入数据并委派专家核验",
                        "values": [3, 6, 9],
                    },
                    "limits": {"max_steps": 20, "timeout_seconds": 120},
                },
                timeout=10,
            )
            assert response.status_code == 202
            task = response.json()
            url = base + "/api/v1/tasks/" + task["task_id"]

            def completed():
                """
                查询 Java 业务终态；尚未完成时返回空以继续有限轮询。
                """
                snapshot = httpx.get(url, timeout=5).json()
                return (
                    snapshot
                    if snapshot["state"] in {"SUCCEEDED", "FAILED", "CANCELLED"}
                    else None
                )

            result = poll(completed, timeout=125)
            assert result["state"] == "SUCCEEDED", result.get("error_code")
            assert result["result"]["summary"] == {"count": 3, "total": 18, "mean": 6}
            assert result["result"]["specialist"] == result["result"]["summary"]
            assert result["result"]["specialist_error"] is None
            assert result["checkpoint_id"]
            events = httpx.get(url + "/events", timeout=5).json()
            completed_tools = [
                e["payload"] for e in events if e["event_type"] == "tool_completed"
            ]
            assert {e["tool"] for e in completed_tools if e["agent"] == "main"} == {
                "get_current_context",
                "calculate_summary",
                "task",
            }
            assert any(
                e["agent"] == "specialist" and e["tool"] == "calculate_summary"
                for e in completed_tools
            )
            # Persist only bounded, non-sensitive verification evidence.
            import json

            evidence = {
                "provider": "openai-compatible",
                "model": env["MODEL_NAME"],
                "task_id": task["task_id"],
                "execution_id": task["execution_id"],
                "state": result["state"],
                "summary": result["result"]["summary"],
                "specialist": result["result"]["specialist"],
                "checkpoint_id": result["checkpoint_id"],
                "event_count": len(events),
                "completed_tools": completed_tools,
            }
            (Path(tmp_path) / "evidence.json").write_text(
                json.dumps(evidence, ensure_ascii=False, indent=2)
            )
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    process.terminate()
                    with contextlib.suppress(subprocess.TimeoutExpired):
                        process.wait(timeout=10)
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=5)
