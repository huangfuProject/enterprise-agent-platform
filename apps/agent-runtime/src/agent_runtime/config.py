"""运行时模型配置；仅定义配置与显式读取入口，导入模块不会连接外部服务。"""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class ModelConfig:
    """不可变的模型适配配置。

    api_key_env 保存环境变量名称，不携带密钥值；timeout 是单次模型调用时限，
    与 TaskExecutionRequest 的整次执行时限分别生效。fake_delay_seconds 仅供故障测试。"""

    provider: str = "fake"
    model: str = "p0-deterministic"
    base_url: str | None = None
    api_key_env: str = "MODEL_API_KEY"
    timeout: float = 30
    temperature: float = 0
    fake_delay_seconds: float = 0  # Deterministic fault injection; never used by live providers.

    @classmethod
    def from_env(cls) -> "ModelConfig":
        """在应用启动或显式测试时读取环境变量。

        不自动加载 .env，避免导入或普通单测意外启用真实模型；启动方负责导出配置。
        未指定 provider 时使用 Fake，温度沿用配置类默认值。"""
        return cls(
            provider=os.getenv("MODEL_PROVIDER", "fake"),
            model=os.getenv("MODEL_NAME", "p0-deterministic"),
            base_url=os.getenv("MODEL_BASE_URL"),
            api_key_env=os.getenv("MODEL_API_KEY_ENV", "MODEL_API_KEY"),
            timeout=float(os.getenv("MODEL_TIMEOUT_SECONDS", "30")),
            fake_delay_seconds=float(os.getenv("P0_FAKE_DELAY_SECONDS", "0")),
        )
