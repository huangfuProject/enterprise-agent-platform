"""
Agent 机制测试：真实框架调度配合确定性模型，校验隔离、恢复与失败路径。
"""

import threading

import pytest
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.types import Command
from pydantic import ValidationError

from agent_runtime.checkpoint import approval_graph
from agent_runtime.config import ModelConfig
from agent_runtime.contracts import TaskExecutionRequest
from agent_runtime.harness import ExecutionStopped, build_agent, parse_result
from agent_runtime.models import DeterministicModel
from agent_runtime.tools import SummaryInput


def request(**changes):
    """
    构造统一的有限测试请求；主上下文哨兵用于检查子 Agent 是否意外继承历史。
    """
    fields = {
        "task_id": "task-1",
        "execution_id": "exec-1",
        "idempotency_key": "key-1",
        "trace_id": "trace-1",
        "request_id": "req-1",
        "checkpoint_thread_id": "task-1-exec-1",
        "input": {"message": "PRIVATE_MAIN_SENTINEL", "values": [1, 2, 3]},
    }
    fields.update(changes)
    return TaskExecutionRequest(**fields)


def test_agent_tools_subagent_isolation(tmp_path):
    """
    验证两个业务工具、原生 task 委派、统计整合及子模型消息隔离。
    """
    req = request()
    child = DeterministicModel(role="specialist")
    main = DeterministicModel()
    events = []
    with SqliteSaver.from_conn_string(str(tmp_path / "agent.db")) as saver:
        graph = build_agent(
            req,
            ModelConfig(),
            saver,
            threading.Event(),
            lambda event, **kw: events.append((event, kw)),
            main,
            child,
        )
        state = graph.invoke(
            {"messages": [("user", req.input.message)]},
            {"configurable": {"thread_id": req.checkpoint_thread_id}, "recursion_limit": 100},
        )
        result = parse_result(state)
        assert result.summary.total == 6
        assert result.specialist.mean == 2
        assert result.specialist_error is None
        assert "PRIVATE_MAIN_SENTINEL" not in str(child.seen)
        assert "PRIVATE_MAIN_SENTINEL" in str(main.seen)
        assert child.seen
        assert {v["tool"] for e, v in events if e == "tool_completed"} == {
            "get_current_context",
            "calculate_summary",
            "task",
        }
        assert saver.get_tuple({"configurable": {"thread_id": req.checkpoint_thread_id}})


def test_child_failure_is_observable(tmp_path):
    """
    注入子模型失败，主 Agent 必须返回明确降级字段而不是伪造子结果。
    """
    req = request()
    with SqliteSaver.from_conn_string(str(tmp_path / "child.db")) as saver:
        graph = build_agent(
            req,
            ModelConfig(),
            saver,
            threading.Event(),
            lambda *a, **k: None,
            child_model=DeterministicModel(role="specialist", fail_child=True),
        )
        result = parse_result(
            graph.invoke(
                {"messages": [("user", "analyze")]}, {"configurable": {"thread_id": "failure"}}
            )
        )
        assert result.specialist is None
        assert result.specialist_error == "SUBAGENT_FAILED"


def test_model_unavailable_and_budget(tmp_path):
    """
    分别验证模型不可用和共享步骤预算用尽；均应显式失败。
    """
    with SqliteSaver.from_conn_string(str(tmp_path / "fail.db")) as saver:
        graph = build_agent(
            request(),
            ModelConfig(),
            saver,
            threading.Event(),
            lambda *a, **k: None,
            main_model=DeterministicModel(unavailable=True),
        )
        with pytest.raises(ConnectionError):
            graph.invoke({"messages": [("user", "go")]}, {"configurable": {"thread_id": "bad"}})
        graph = build_agent(
            request(limits={"max_steps": 1}),
            ModelConfig(),
            saver,
            threading.Event(),
            lambda *a, **k: None,
        )
        with pytest.raises(ExecutionStopped, match="STEP_LIMIT"):
            graph.invoke({"messages": [("user", "go")]}, {"configurable": {"thread_id": "limit"}})


def test_checkpoint_reopen_resume_and_isolation(tmp_path):
    """
    关闭连接后重建图，再 resume；已完成节点不能重跑，其他 thread 不能读到状态。
    """
    db = str(tmp_path / "checkpoint.db")
    cfg = {"configurable": {"thread_id": "task-A-exec-A"}}
    with SqliteSaver.from_conn_string(db) as saver:
        graph = approval_graph(saver)
        result = graph.invoke({"task_id": "A", "execution_id": "exec-A"}, cfg)
        assert result["__interrupt__"]
        assert graph.get_state(cfg).values["prepared"] == 1
    with SqliteSaver.from_conn_string(db) as saver:
        graph = approval_graph(saver)
        assert graph.get_state(cfg).next == ("approve",)
        result = graph.invoke(Command(resume=True), cfg)
        assert result["approved"] is True
        assert result["prepared"] == 1
        assert graph.get_state({"configurable": {"thread_id": "other"}}).values == {}


@pytest.mark.parametrize("values", [[], [float("nan")], [float("inf")], [1] * 101])
def test_tools_reject_invalid_input(values):
    """
    验证空数组、超量数据和非有限浮点数都在 Schema 边界拒绝。
    """
    with pytest.raises(ValidationError):
        SummaryInput(values=values)


def test_policy_denies_undeclared_tools_and_child_escalation():
    """
    尝试文件、执行、额外上下文和再次委派，断言拒绝发生在 handler 被调用前。
    """
    from types import SimpleNamespace

    from agent_runtime.harness import Budget, PolicyMiddleware

    budget = Budget(20, threading.Event())
    middleware = PolicyMiddleware("specialist", budget, [1], lambda *a, **k: None)
    for name in ["read_file", "execute", "get_current_context", "task"]:
        with pytest.raises(ExecutionStopped, match="TOOL_DENIED"):
            middleware.wrap_tool_call(
                SimpleNamespace(tool_call={"name": name}),
                lambda _: pytest.fail("unauthorized handler invoked"),
            )
    middleware = PolicyMiddleware("main", budget, [1], lambda *a, **k: None)
    with pytest.raises(ExecutionStopped, match="DELEGATION_DENIED"):
        middleware.wrap_tool_call(
            SimpleNamespace(
                tool_call={"name": "task", "args": {"subagent_type": "general-purpose"}}
            ),
            lambda _: pytest.fail("escalated"),
        )


def test_structured_provider_response_is_validated():
    """
    校验 ToolStrategy 结果仍经过自有 Schema；额外字段不能绕过输出验证。
    """
    valid = {
        "summary": {"count": 3, "total": 6, "mean": 2},
        "specialist": {"count": 3, "total": 6, "mean": 2},
        "answer": "validated",
    }
    assert parse_result({"structured_response": valid}).summary.total == 6
    with pytest.raises(ValidationError):
        parse_result({"structured_response": {**valid, "undeclared": "untrusted"}})
