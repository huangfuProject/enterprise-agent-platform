"""受控 Agent Harness：组合框架能力与确定性工具/委派限制，业务状态仍由 Java 管理。"""

import json
import threading
from collections.abc import Callable

from deepagents import create_deep_agent
from langchain.agents.middleware import AgentMiddleware
from langchain.agents.structured_output import ToolStrategy
from langchain_core.messages import ToolMessage

from .config import ModelConfig
from .contracts import AgentResult, TaskExecutionRequest
from .models import build_model
from .tools import registered_tools


class NoFilesystem(AgentMiddleware):
    """替换锁定 DeepAgents 版本的默认文件工具中间件。

    使用同名替换机制移除工具注入；不挂载宿主文件后端。升级框架须重跑白名单负测。"""

    name = "FilesystemMiddleware"


class ExecutionStopped(RuntimeError):
    """表示本次执行触及取消、预算或能力边界，由 Runtime 收敛为终态。"""


class Budget:
    """主/子 Agent 共用的一次执行预算；线程锁保护并行工具回调下的计数和委派标记。"""

    def __init__(self, limit: int, cancelled: threading.Event):
        """保存步骤上限和取消信号；每次新执行必须新建预算，不能跨任务复用。"""
        self.limit = limit
        self.used = 0
        self.delegated = False
        self.cancelled = cancelled
        self.lock = threading.Lock()

    def consume(self):
        """消耗一个模型/工具步骤；在任何实际调用前检查协作取消与预算。"""
        with self.lock:
            if self.cancelled.is_set():
                raise ExecutionStopped("CANCELLED")
            # 预算同时覆盖模型轮次和工具访问；子 Agent 不获得额外独立额度。
            self.used += 1
            if self.used > self.limit:
                raise ExecutionStopped("STEP_LIMIT")


class PolicyMiddleware(AgentMiddleware):
    """调用前强制检查工具与委派范围；不依赖模型遵守系统提示。"""

    def __init__(self, role: str, budget: Budget, values: list[float], emit: Callable):
        """将角色、共享预算、受控数据和事件出口绑定到一次图执行。"""
        self.role, self.budget, self.values, self.emit = role, budget, values, emit

    def wrap_model_call(self, request, handler):
        """在模型调用前消耗共享步骤；正在进行的调用由客户端与进程超时兜底。"""
        self.budget.consume()
        return handler(request)

    def wrap_tool_call(self, request, handler):
        """在实际工具执行前检查范围并发出事件。

        主 Agent 仅可调用两个业务工具和固定 task；子 Agent 仅可统计。委派只允许
        一次、一个固定模板，并重建子输入以避免自动复制主历史。子失败转显式错误
        工具消息供主 Agent 降级；其他工具异常继续交给 Runtime 处理。"""
        self.budget.consume()
        name = request.tool_call["name"]
        allowed = (
            {"calculate_summary"}
            if self.role == "specialist"
            else {"get_current_context", "calculate_summary", "task"}
        )
        if name not in allowed:
            raise ExecutionStopped("TOOL_DENIED")
        # task 也是受控能力：框架存在通用子 Agent，不意味着平台允许调用它。
        if name == "task":
            with self.budget.lock:
                if (
                    self.budget.delegated
                    or request.tool_call["args"].get("subagent_type") != "summary-specialist"
                ):
                    raise ExecutionStopped("DELEGATION_DENIED")
                self.budget.delegated = True
            # 只投影确定性数值；模型生成的 description 不能把主历史注入子上下文。
            call = {
                **request.tool_call,
                "args": {
                    "description": "独立核算数值统计，仅返回 count/total/mean JSON。数值："
                    + json.dumps(self.values),
                    "subagent_type": "summary-specialist",
                },
            }
            request = request.override(tool_call=call)
            self.emit("agent_started", agent="summary-specialist")
        self.emit("tool_started", agent=self.role, tool=name)
        try:
            result = handler(request)
        except Exception:
            if name != "task":
                raise
            # 显式保留错误结果，让主 Agent 识别降级，不能伪造成子 Agent 成功。
            result = ToolMessage(
                content="SUBAGENT_FAILED",
                tool_call_id=request.tool_call["id"],
                name=name,
                status="error",
            )
        self.emit("tool_completed", agent=self.role, tool=name)
        if name == "task":
            self.emit("agent_completed", agent="summary-specialist")
        return result


def build_agent(
    request: TaskExecutionRequest,
    config: ModelConfig,
    saver,
    cancelled,
    emit,
    main_model=None,
    child_model=None,
):
    """构造一次执行的 DeepAgents 图，不额外叠加第二层 Agent Loop。

    request 决定工具上下文、受控数值和预算；saver 由调用者管理连接生命周期。
    main_model/child_model 仅用于测试注入；真实模型最终响应由 ToolStrategy 约束。
    返回图需由调用者提供独立 thread_id，不能使用 Task 状态代替图状态。"""
    # 每次构建独立工具闭包与预算，避免不同执行共享可变消息/委派状态。
    tools = registered_tools(request)
    budget = Budget(request.limits.max_steps, cancelled)
    main_model = main_model or build_model(config, role="main", values=request.input.values)
    child_model = child_model or build_model(config, role="specialist", values=request.input.values)
    child = {
        "name": "summary-specialist",
        "description": "Independently verify numeric statistics",
        "system_prompt": "核算输入数值。调用 calculate_summary，仅返回其 JSON 结果。",
        "model": child_model,
        # 子 Agent 的能力是父级允许集合的子集，且不能继续委派。
        "tools": [tools[1]],
        "middleware": [
            NoFilesystem(),
            PolicyMiddleware("specialist", budget, request.input.values, emit),
        ],
    }
    return create_deep_agent(
        model=main_model,
        tools=tools,
        checkpointer=saver,
        # 使用实际验证过的函数工具结构化输出，不依赖模型自由文本总能返回裸 JSON。
        response_format=ToolStrategy(AgentResult) if config.provider != "fake" else None,
        system_prompt=(
            "本次 PoC 按以下步骤执行，不得省略："
            "1. 调用 get_current_context 获取任务标识；"
            "2. 调用 calculate_summary 统计本次输入数值；"
            "3. 使用 task 委派 summary-specialist 独立核算，subagent_type 必须为 summary-specialist；"
            "4. 整合实际工具结果。数值以此受控输入为准：" + json.dumps(request.input.values) + "。"
            "最后仅返回 JSON：summary={count,total,mean}, specialist={count,total,mean}"
            "或 null, specialist_error=null 或 SUBAGENT_FAILED, answer=简短说明。"
        ),
        subagents=[child],
        middleware=[NoFilesystem(), PolicyMiddleware("main", budget, request.input.values, emit)],
    )


def parse_result(state: dict) -> AgentResult:
    """校验最终输出；真实 Provider 读取结构化响应，Fake 解析严格 JSON。

    不剥离任意 Markdown、不接受未知字段；校验失败必须由上层记录为执行失败。"""
    if "structured_response" in state:
        return AgentResult.model_validate(state["structured_response"])
    return AgentResult.model_validate_json(state["messages"][-1].content)
