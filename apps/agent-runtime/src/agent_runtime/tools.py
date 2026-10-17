"""P0 受控工具注册；只有有限上下文与纯数值统计，不提供文件、网络或 Shell 能力。"""

from collections.abc import Callable

from langchain_core.tools import StructuredTool
from pydantic import Field

from .contracts import Contract, Summary, TaskExecutionRequest


# 空输入表示模型无法自行指定其他任务或用户的上下文标识。
class ContextInput(Contract):
    pass


# 仅输出任务/运行标识与用途，不返回主对话、凭证或用户原始数据。
class ContextOutput(Contract):
    task_id: str
    execution_id: str
    purpose: str


# 与 Contract 一起拒绝额外字段及非有限浮点数，限制一次计算的数据规模。
class SummaryInput(Contract):
    values: list[float] = Field(min_length=1, max_length=100)


def registered_tools(request: TaskExecutionRequest) -> list[StructuredTool]:
    """为本次执行创建绑定上下文的工具实例。

    Args:
        request: 已验证的任务请求；上下文工具只公开本次请求的有限标识。
    Returns:
        带显式名称、输入 Schema 和固定描述的工具列表，交由框架 ToolNode 调度。"""

    def get_current_context() -> str:
        """投影有限任务上下文，返回经过输出模型校验的 JSON 字符串。"""
        return ContextOutput(
            task_id=request.task_id,
            execution_id=request.execution_id,
            purpose="bounded-p0-statistics",
        ).model_dump_json()

    def calculate_summary(values: list[float]) -> str:
        """校验数值数量与有限性，执行纯统计，再校验输出以拒绝溢出等异常结果。"""
        checked = SummaryInput(values=values)
        # 计算属于确定性代码；模型只提出参数，不执行任意 Python 表达式。
        total = sum(checked.values)
        return Summary(count=len(values), total=total, mean=total / len(values)).model_dump_json()

    def make(name: str, fn: Callable, schema: type[Contract], description: str):
        """将受控函数与输入 Schema 注册为工具；固定 description，避免 docstring 改变模型提示。"""
        return StructuredTool.from_function(
            fn, name=name, args_schema=schema, description=description
        )

    # 描述是模型可见的协议内容；本次仅增加代码注释，保留原描述与工具参数。
    return [
        make(
            "get_current_context",
            get_current_context,
            ContextInput,
            "Return only bounded identifiers for this execution.",
        ),
        make(
            "calculate_summary",
            calculate_summary,
            SummaryInput,
            "Calculate deterministic count, total and mean for finite numbers.",
        ),
    ]
