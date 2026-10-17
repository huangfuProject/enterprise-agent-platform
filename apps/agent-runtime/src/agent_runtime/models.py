"""模型适配边界：Fake 只产生工具请求，真实 Provider 的客户端创建集中在此模块。"""

import json
import os
import time
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field

from .config import ModelConfig


class DeterministicModel(BaseChatModel):
    """用于稳定机制测试的聊天模型，不直接调用任何业务工具。

    根据已经回传的 ToolMessage 决定下一条工具请求；seen 仅供隔离测试观察。
    unavailable/fail_child/delay_seconds 是显式故障注入参数。"""

    role: str = "main"
    values: list[float] = Field(default_factory=lambda: [1, 2, 3])
    seen: list[list[Any]] = Field(default_factory=list, exclude=True)
    unavailable: bool = False
    delay_seconds: float = 0
    fail_child: bool = False

    @property
    def _llm_type(self) -> str:
        """返回框架使用的模型类型标识，不表示真实模型或供应商能力。"""
        return "p0-deterministic"

    def bind_tools(self, tools: Any, **kwargs: Any) -> "DeterministicModel":
        """满足框架工具绑定接口；Fake 按固定协议生成调用，不依赖外部模型。"""
        return self

    def _generate(self, messages: list, stop: Any = None, run_manager: Any = None, **kwargs: Any):
        """按主/子角色推进固定回合，复用真实工具结果构造最后的结构化响应。"""
        self.seen.append(messages)
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        if self.unavailable or (self.role == "specialist" and self.fail_child):
            raise ConnectionError("MODEL_UNAVAILABLE")
        # 只在框架回传工具消息后推进，防止 Fake 绕过工具注册和实际执行路径。
        results = {m.name: m.content for m in messages if isinstance(m, ToolMessage)}
        call = None
        if self.role == "specialist":
            if "calculate_summary" not in results:
                call = ("calculate_summary", {"values": self.values})
            else:
                return self._reply(str(results["calculate_summary"]))
        elif "get_current_context" not in results:
            call = ("get_current_context", {})
        elif "calculate_summary" not in results:
            call = ("calculate_summary", {"values": self.values})
        elif "task" not in results:
            call = (
                "task",
                {
                    "description": "仅核算给定数值的统计结果：" + json.dumps(self.values),
                    "subagent_type": "summary-specialist",
                },
            )
        else:
            summary = json.loads(str(results["calculate_summary"]))
            try:
                specialist = json.loads(str(results["task"]))
                error = None
            except (ValueError, TypeError):
                specialist, error = None, "SUBAGENT_FAILED"
            return self._reply(
                json.dumps(
                    {
                        "summary": summary,
                        "specialist": specialist,
                        "specialist_error": error,
                        "answer": "示例统计已完成",
                    }
                )
            )
        name, args = call
        return self._reply(
            "", [{"name": name, "args": args, "id": f"{self.role}-{name}", "type": "tool_call"}]
        )

    @staticmethod
    def _reply(content: str, calls: list | None = None) -> ChatResult:
        """将固定内容和工具调用包装为 LangChain 标准 ChatResult。"""
        return ChatResult(
            generations=[ChatGeneration(message=AIMessage(content=content, tool_calls=calls or []))]
        )


def build_model(config: ModelConfig, *, role: str, values: list[float]) -> BaseChatModel:
    """创建指定角色使用的聊天模型。

    Args:
        config: Provider、端点、超时及密钥环境变量引用。
        role: 主 Agent 或统计专家；Fake 据此选择固定回合。
        values: 本次任务的有限样例数据，用于 Fake 的确定性请求。
    Returns:
        可由 DeepAgents 调用的 BaseChatModel；不会在创建时发起模型推理。
    Raises:
        ValueError: Provider 不支持或真实模型缺少密钥配置。"""
    if config.provider == "fake":
        return DeterministicModel(role=role, values=values, delay_seconds=config.fake_delay_seconds)
    if config.provider == "openai-compatible":
        from langchain_openai import ChatOpenAI

        key = os.environ.get(config.api_key_env)
        # 真正的密钥只交给客户端，不进入跨语言请求、提示词或日志。
        if not key:
            raise ValueError("MODEL_CREDENTIAL_MISSING")
        return ChatOpenAI(
            model=config.model,
            base_url=config.base_url,
            api_key=key,
            timeout=config.timeout,
            temperature=config.temperature,
            # Runtime 管理整个执行的边界；此处不做可能重复工具流程的隐式重试。
            max_retries=0,
        )
    raise ValueError("MODEL_PROVIDER_UNSUPPORTED")
