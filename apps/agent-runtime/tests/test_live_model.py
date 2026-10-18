"""
显式启用的真实模型测试；仅存在密钥不会自动触发外部调用或费用。
"""

import os
import threading

import pytest
from langgraph.checkpoint.sqlite import SqliteSaver
from test_agent import request

from agent_runtime.config import ModelConfig
from agent_runtime.harness import build_agent, parse_result


@pytest.mark.live_model
@pytest.mark.skipif(os.getenv("RUN_LIVE_MODEL") != "true", reason="RUN_LIVE_MODEL not enabled")
def test_live_model_tools_and_structured_result(tmp_path):
    """
    要求主/子工具真实完成、结果一致且 Checkpoint 已生成，不能仅凭最终文本判定通过。
    """
    config = ModelConfig.from_env()
    assert config.provider == "openai-compatible"
    events = []
    with SqliteSaver.from_conn_string(str(tmp_path / "live.db")) as saver:
        graph = build_agent(
            request(),
            config,
            saver,
            threading.Event(),
            lambda kind, **details: events.append((kind, details)),
        )
        result = parse_result(
            graph.invoke(
                {"messages": [("user", "计算 [1,2,3] 并独立核验")]},
                {"configurable": {"thread_id": "live"}},
            )
        )
        assert result.summary.total == 6
        assert result.specialist is not None
        assert result.specialist.total == 6
        assert result.specialist_error is None
        completed = [details for kind, details in events if kind == "tool_completed"]
        assert {details["tool"] for details in completed if details["agent"] == "main"} == {
            "get_current_context",
            "calculate_summary",
            "task",
        }
        assert any(
            details["agent"] == "specialist" and details["tool"] == "calculate_summary"
            for details in completed
        )
        assert graph.get_state({"configurable": {"thread_id": "live"}}).config["configurable"][
            "checkpoint_id"
        ]
