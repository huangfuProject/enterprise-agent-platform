"""
内部 HTTP 协议适配：工厂创建应用，lifespan 显式获取/释放 Runtime 资源。
"""

import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from .config import ModelConfig
from .contracts import ExecutionEvent, ExecutionStatus, TaskExecutionRequest
from .runtime import Runtime
from .store import Conflict


def create_app(directory: str | None = None, config: ModelConfig | None = None):
    """
    创建 FastAPI 应用而不启动执行资源。

    测试可传入独立目录和 Fake 配置；实际目录锁、恢复和进程管理在 lifespan 启动。
    """

    @asynccontextmanager
    async def lifespan(app):
        """
        在 ASGI 生命周期内创建唯一 Runtime，并在正常关闭时回收执行子进程。
        """
        app.state.runtime = Runtime(
            directory or os.getenv("RUNTIME_DATA_DIR", ".data/runtime"),
            config or ModelConfig.from_env(),
        )
        yield
        app.state.runtime.close()

    app = FastAPI(
        title="P0 Agent Runtime — controlled development only", version="v1", lifespan=lifespan
    )

    # 路由/异常处理器使用普通注释，防止 docstring 被 FastAPI 自动加入 OpenAPI。
    @app.exception_handler(Conflict)
    async def conflict(request: Request, exc: Conflict):
        return JSONResponse(status_code=409, content={"code": str(exc)})

    @app.exception_handler(KeyError)
    async def missing(request: Request, exc: KeyError):
        return JSONResponse(status_code=404, content={"code": "EXECUTION_NOT_FOUND"})

    @app.get("/health")
    # 仅报告 Runtime 进程存活，不承诺模型、Checkpoint 或 Java 数据库可用。
    def health():
        return {"status": "UP", "mode": "controlled-development"}

    @app.post("/internal/v1/executions", status_code=202, response_model=ExecutionStatus)
    # 接收确认发生在持久化之后；202 不表示 Agent 已开始或结果已生成。
    def submit(body: TaskExecutionRequest, request: Request):
        return request.app.state.runtime.submit(body)

    @app.get("/internal/v1/executions/{execution_id}", response_model=ExecutionStatus)
    # 返回持久执行快照；Java 仍独立决定业务任务的最终状态。
    def status(execution_id: str, request: Request):
        return request.app.state.runtime.store.get(execution_id)

    @app.get("/internal/v1/executions/{execution_id}/events", response_model=list[ExecutionEvent])
    # after 为排他游标，事件由 Store 按运行内 sequence 排序和限量读取。
    def events(execution_id: str, request: Request, after: int = Query(default=0, ge=0)):
        return request.app.state.runtime.store.events(execution_id, after)

    @app.post("/internal/v1/executions/{execution_id}/cancel", status_code=202)
    # 取消标记可先于执行记录到达，防止网络中迟到的提交重新启动已取消任务。
    def cancel(execution_id: str, request: Request):
        request.app.state.runtime.store.cancel(execution_id)
        return {"execution_id": execution_id, "cancel_requested": True}

    return app
