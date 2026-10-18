"""
Java/Python v1 边界模型；与 LangGraph 内部消息、State、Checkpoint 对象隔离。
"""

from datetime import UTC, datetime
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


# 使用普通注释说明模型，避免类 docstring 自动成为 JSON Schema description。
# 禁止未声明字段以及 NaN/Infinity，使模型输出和跨语言请求都经过显式边界校验。
class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


# 两个独立上限：主/子 Agent 共享步骤预算，整个执行还受进程级 deadline 约束。
class Limits(Contract):
    max_steps: int = Field(default=20, ge=1, le=100)
    timeout_seconds: int = Field(default=120, ge=1, le=300)


# 仅携带受限示例数据；后续大数据集应使用受权 Artifact 引用，避免灌入上下文。
class TaskInput(Contract):
    message: str = Field(min_length=1, max_length=4000)
    values: list[float] = Field(default_factory=lambda: [1, 2, 3], min_length=1, max_length=100)


class TaskExecutionRequest(Contract):
    contract_version: Literal["v1"] = "v1"
    # task_id 标识业务任务，execution_id 标识这次尝试；重试不能混用两者。
    task_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    execution_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    # 相同键必须对应同一请求载荷；实际去重由持久化存储的唯一约束完成。
    idempotency_key: str = Field(min_length=1, max_length=100)
    # trace_id 关联跨进程日志；request_id 在重复投递同一执行时保持稳定。
    trace_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    request_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    # 平台生成独立 thread，防止不同 Task/Run 误读或覆盖对方的图状态。
    checkpoint_thread_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,160}$")
    agent_definition: Literal["main-agent"] = "main-agent"
    input: TaskInput
    limits: Limits = Field(default_factory=Limits)


# 这是 Runtime 执行快照状态；Java 的 QUEUED/CLAIMED/CANCEL_REQUESTED 属于业务状态。
class Status(StrEnum):
    ACCEPTED = "ACCEPTED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


# 确定性统计工具的输出，也是主/子 Agent 结果的共同子结构。
class Summary(Contract):
    count: int = Field(ge=1, le=100)
    total: float
    mean: float


# 输出是非可信输入：必须验证结构；specialist 为 null 时可显式表达子任务失败。
class AgentResult(Contract):
    summary: Summary
    specialist: Summary | None
    specialist_error: str | None = None
    answer: str = Field(min_length=1, max_length=8000)


EventType = Literal[
    "execution_started",
    "agent_started",
    "tool_started",
    "tool_completed",
    "agent_completed",
    "execution_completed",
    "execution_failed",
    "execution_cancelled",
]


# sequence 只在一个 execution 内递增；Java 拉取后会另行分配任务事件序号。
class ExecutionEvent(Contract):
    contract_version: Literal["v1"] = "v1"
    task_id: str
    execution_id: str
    trace_id: str
    sequence: int = Field(ge=1)
    event_type: EventType
    timestamp: datetime
    agent: str | None = None
    tool: str | None = None


# 接收请求、开始执行和生成 Checkpoint 是不同事实，允许对应字段暂时为空。
class ExecutionStatus(Contract):
    contract_version: Literal["v1"] = "v1"
    task_id: str
    execution_id: str
    checkpoint_thread_id: str
    status: Status
    started_at: datetime | None = None
    updated_at: datetime
    error_code: str | None = None
    error_message: str | None = None
    result: AgentResult | None = None
    checkpoint_id: str | None = None


def now() -> datetime:
    """
    返回带 UTC 时区的事件时间。

    墙上时钟用于展示和关联；超时判断应使用 monotonic，跨机器事件排序依赖 sequence。
    """
    return datetime.now(UTC)
