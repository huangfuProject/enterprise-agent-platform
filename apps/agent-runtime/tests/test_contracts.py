"""跨语言边界 Schema 回归：防止 Python 模型变化后正式契约快照没有同步。"""

import json
from pathlib import Path

import jsonschema
from test_agent import request

from agent_runtime import contracts


def test_request_schema_snapshot():
    """比较已提交 Schema 与当前边界模型，并用实际请求通过 JSON Schema 校验。"""
    schema = json.loads(
        (
            Path(__file__).resolve().parents[3]
            / "packages/contracts/v1/TaskExecutionRequest.schema.json"
        ).read_text()
    )
    assert schema == contracts.TaskExecutionRequest.model_json_schema()
    jsonschema.validate(request().model_dump(mode="json"), schema)


def test_event_schema_and_validation():
    """构造带关联 ID、序号和 UTC 时间的事件，验证其可作为正式边界数据。"""
    event = contracts.ExecutionEvent(
        task_id="task",
        execution_id="run",
        trace_id="trace",
        sequence=1,
        event_type="execution_started",
        timestamp=contracts.now(),
    )
    jsonschema.validate(event.model_dump(mode="json"), contracts.ExecutionEvent.model_json_schema())
