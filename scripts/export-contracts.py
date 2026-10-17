"""导出自有边界 Schema 与合并 OpenAPI；不会启动 Runtime 或调用模型。"""

import json
from pathlib import Path

from agent_runtime import contracts
from agent_runtime.api import create_app
from agent_runtime.tools import ContextInput, ContextOutput, SummaryInput
from pydantic import Field


# 该类参与 Schema 导出，使用普通注释，避免注释变成正式 description 字段。
class CreateTask(contracts.Contract):
    idempotency_key: str = Field(min_length=1, max_length=100)
    input: contracts.TaskInput
    limits: contracts.Limits


root = Path(__file__).resolve().parents[1]
for model in [
    contracts.TaskExecutionRequest,
    contracts.ExecutionStatus,
    contracts.ExecutionEvent,
    contracts.AgentResult,
    ContextInput,
    ContextOutput,
    SummaryInput,
    contracts.Summary,
    CreateTask,
]:
    (root / "packages/contracts/v1" / f"{model.__name__}.schema.json").write_text(
        json.dumps(model.model_json_schema(), ensure_ascii=False, indent=2) + "\n"
    )

# 工厂创建应用但不进入 lifespan，因此不会获取目录锁、恢复任务或发起模型请求。
spec = create_app().openapi()
spec["info"] = {
    "title": "Enterprise Agent Platform P0",
    "version": "v1",
    "description": "Controlled development only. Public API :8080; Runtime internal :8000.",
}
for path in spec["paths"].values():
    for operation in path.values():
        operation["servers"] = [{"url": "http://127.0.0.1:8000"}]

# JSON Schema 的 null 表示字段允许空值，不能用字符串 "null" 替代。
nullable_string = {"type": ["string", "null"]}
spec["components"]["schemas"]["TaskView"] = {
    "type": "object",
    "required": [
        "contract_version",
        "task_id",
        "execution_id",
        "revision",
        "state",
        "run_status",
        "trace_id",
        "checkpoint_thread_id",
    ],
    "properties": {
        **{
            k: {"type": "string"}
            for k in [
                "contract_version",
                "task_id",
                "execution_id",
                "idempotency_key",
                "trace_id",
                "request_id",
                "checkpoint_thread_id",
                "run_status",
            ]
        },
        "revision": {"const": 1},
        "state": {
            "enum": [
                "QUEUED",
                "CLAIMED",
                "RUNNING",
                "CANCEL_REQUESTED",
                "SUCCEEDED",
                "FAILED",
                "CANCELLED",
            ]
        },
        **{
            k: nullable_string
            for k in ["started_at", "error_code", "error_message", "checkpoint_id"]
        },
        "updated_at": {"type": "string", "format": "date-time"},
        "result": {"anyOf": [{"$ref": "AgentResult.schema.json"}, {"type": "null"}]},
    },
}
spec["components"]["schemas"]["Error"] = {
    "type": "object",
    "required": ["code", "trace_id"],
    "properties": {"code": {"type": "string"}, "trace_id": {"type": "string"}},
}
spec["components"]["schemas"]["TaskEvent"] = {
    "type": "object",
    "required": [
        "contract_version",
        "task_id",
        "execution_id",
        "trace_id",
        "sequence",
        "event_type",
        "timestamp",
        "payload",
    ],
    "properties": {
        **{
            k: {"type": "string"}
            for k in [
                "contract_version",
                "task_id",
                "execution_id",
                "trace_id",
                "event_type",
            ]
        },
        "sequence": {"type": "integer", "minimum": 1},
        "timestamp": {"type": "string", "format": "date-time"},
        "payload": {"type": "object"},
    },
}


def operation(
    operation_id, response_schema, success="200", body=False, task=False, events=False
):
    """构造公共 API 的操作描述，保持默认错误、任务路径和分页参数的一致性。

    脚本顶层会写出契约快照，应作为导出命令执行；模型只引用边界 DTO，
    不导出 LangGraph State。"""
    result = {
        "operationId": operation_id,
        "servers": [{"url": "http://127.0.0.1:8080"}],
        "responses": {
            success: {
                "description": "Accepted" if success == "202" else "OK",
                "content": {"application/json": {"schema": response_schema}},
            }
        },
    }
    for code in ["400", "404", "409", "503"]:
        result["responses"][code] = {
            "description": "Structured error",
            "content": {
                "application/json": {"schema": {"$ref": "#/components/schemas/Error"}}
            },
        }
    if body:
        result["requestBody"] = {
            "required": True,
            "content": {
                "application/json": {"schema": {"$ref": "CreateTask.schema.json"}}
            },
        }
    if task:
        result["parameters"] = [
            {
                "name": "task_id",
                "in": "path",
                "required": True,
                "schema": {"type": "string"},
            }
        ]
    if events:
        result["parameters"].append(
            {
                "name": "after",
                "in": "query",
                "schema": {"type": "integer", "minimum": 0, "default": 0},
            }
        )
    return result


# 合并 Java 公共 API 与 Python 内部 API；每个操作保留不同的服务地址。
view = {"$ref": "#/components/schemas/TaskView"}
spec["paths"]["/api/v1/tasks"] = {
    "post": operation("createTask", view, "202", body=True)
}
spec["paths"]["/api/v1/tasks/{task_id}"] = {
    "get": operation("getTask", view, task=True)
}
spec["paths"]["/api/v1/tasks/{task_id}/cancel"] = {
    "post": operation("cancelTask", view, "202", task=True)
}
spec["paths"]["/api/v1/tasks/{task_id}/events"] = {
    "get": operation(
        "getTaskEvents",
        {"type": "array", "items": {"$ref": "#/components/schemas/TaskEvent"}},
        task=True,
        events=True,
    )
}
spec["paths"]["/actuator/health"] = {
    "get": operation("platformHealth", {"type": "object"})
}
(root / "packages/contracts/v1/openapi.json").write_text(
    json.dumps(spec, ensure_ascii=False, indent=2) + "\n"
)
