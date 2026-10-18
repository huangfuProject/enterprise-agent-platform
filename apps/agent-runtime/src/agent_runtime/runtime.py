"""
单 Runtime 的进程执行器：用持久去重、独立子进程和有界监控控制执行生命周期。
"""

import fcntl
import json
import logging
import multiprocessing as mp
import os
import threading
import time
from pathlib import Path

from langgraph.checkpoint.sqlite import SqliteSaver

from .config import ModelConfig
from .contracts import Status, TaskExecutionRequest, now
from .harness import build_agent, parse_result
from .store import RuntimeStore


def execute(directory: str, raw: dict, config: ModelConfig, cancelled):
    """
    子进程入口：创建图、执行 Agent、记录结果或安全失败。

    仅接收可序列化请求与不含密钥值的 ModelConfig；真实密钥按环境变量引用读取。
    SQLite Checkpoint 与执行元数据分别管理，不提供与 Java 数据库的分布式事务。
    """
    # spawn 后单独监控父身份；Java 另以租约识别整个 Runtime 服务的失联。
    parent_pid = os.getppid()

    def watch_parent():
        """
        检测 Runtime 父进程死亡，防止其被强杀后留下继续运行的无主执行。
        """
        while True:
            time.sleep(0.2)
            if os.getppid() != parent_pid:
                # 父服务已消失，直接退出，避免阻塞在第三方 SDK 的清理逻辑中。
                os._exit(72)

    threading.Thread(target=watch_parent, daemon=True).start()
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    req = TaskExecutionRequest.model_validate(raw)
    store = RuntimeStore(directory)

    def emit(kind, **kw):
        """
        先持久化事件，再输出有限关联元数据；不记录消息内容或模型凭证。
        """
        store.emit(req, kind, **kw)
        logging.getLogger("agent_runtime.execution").info(
            json.dumps(
                {
                    "event": kind,
                    "task_id": req.task_id,
                    "execution_id": req.execution_id,
                    "trace_id": req.trace_id,
                    **kw,
                }
            )
        )

    try:
        store.update(req.execution_id, status=Status.RUNNING, started_at=now())
        emit("execution_started")
        emit("agent_started", agent="main")
        with SqliteSaver.from_conn_string(str(Path(directory) / "checkpoints.db")) as saver:
            graph = build_agent(req, config, saver, cancelled, emit)
            # thread 是图状态命名空间，不能与业务 task_id 或单个 checkpoint_id 混用。
            cfg = {"configurable": {"thread_id": req.checkpoint_thread_id}, "recursion_limit": 200}
            state = graph.invoke({"messages": [("user", req.input.message)]}, cfg)
            result = parse_result(state)
            # 只有图执行并可读取快照之后才记录 checkpoint_id；受理请求不等于已保存它。
            checkpoint = graph.get_state(cfg).config["configurable"]["checkpoint_id"]
        if cancelled.is_set() or store.cancelled(req.execution_id):
            store.finish(req, Status.CANCELLED)
        else:
            emit("agent_completed", agent="main")
            store.finish(req, Status.SUCCEEDED, result=result, checkpoint_id=checkpoint)
    except Exception as exc:  # noqa: BLE001 - isolate provider errors without disclosing credentials
        # Only the exception class is safe metadata; never print provider error text or headers.
        logging.getLogger("agent_runtime.execution").warning(
            json.dumps(
                {
                    "event": "agent_failed",
                    "task_id": req.task_id,
                    "execution_id": req.execution_id,
                    "trace_id": req.trace_id,
                    "exception_type": type(exc).__name__,
                }
            )
        )
        # Provider exception text may contain URLs, headers or input. Never expose it.
        if cancelled.is_set():
            store.finish(req, Status.CANCELLED)
        else:
            store.finish(
                req,
                Status.FAILED,
                error_code="AGENT_FAILED",
                error_message="Agent failed; inspect controlled development diagnostics",
            )


class Runtime:
    """
    单进程 Runtime 所有者，管理目录锁、已启动子进程和故障监控。

    每目录只能有一个所有者；它是本地 PoC，不是分布式 Worker 调度器。
    """

    def __init__(self, directory: str, config: ModelConfig):
        """
        取得目录独占锁后恢复未知执行；不自动重放先前可能已发生的工具调用。
        """
        Path(directory).mkdir(parents=True, exist_ok=True)
        # 锁句柄必须贯穿 Runtime 生命周期，不能在构造结束时提前关闭。
        self._owner_lock = open(Path(directory) / "runtime.lock", "a")  # noqa: SIM115 - held until close()
        try:
            fcntl.flock(self._owner_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self._owner_lock.close()
            raise RuntimeError("RUNTIME_DIRECTORY_ALREADY_OWNED") from None
        self.store = RuntimeStore(directory)
        self.config = config
        self.children: dict[str, tuple] = {}
        self.lock = threading.Lock()
        self.closed = False
        self.store.recover()

    def submit(self, req: TaskExecutionRequest):
        """
        持久化并去重请求，仅为首次接受且未预先取消的执行启动一个 spawn 子进程。
        """
        with self.lock:
            if self.closed:
                raise RuntimeError("RUNTIME_CLOSED")
            snapshot, created = self.store.accept(req)
            if created:
                # spawn 避免继承父进程的数据库连接、线程锁和模型客户端内部状态。
                ctx = mp.get_context("spawn")
                event = ctx.Event()
                process = ctx.Process(
                    target=execute,
                    args=(self.store.directory, req.model_dump(mode="json"), self.config, event),
                )
                process.start()
                self.children[req.execution_id] = (process, event)
                threading.Thread(
                    target=self._monitor, args=(req, process, event), daemon=True
                ).start()
            return snapshot

    def _monitor(self, req, process, event):
        """
        监督子进程的协作取消、总期限和异常退出，最终由 Store 原子提交终态。

        使用 monotonic 计算期限；终止进程不代表能够回滚未来工具的外部副作用。
        """
        # 监控器负责整次执行时限；模型客户端超时只限制单次外部调用。
        deadline = time.monotonic() + req.limits.timeout_seconds
        while process.is_alive():
            if self.store.cancelled(req.execution_id):
                event.set()
                # 先给安全检查点短暂协作退出窗口，再回收仍存活的独立执行进程。
                process.join(0.5)
                if process.is_alive():
                    process.terminate()
                break
            if time.monotonic() >= deadline:
                event.set()
                process.terminate()
                self.store.finish(
                    req,
                    Status.FAILED,
                    error_code="EXECUTION_TIMEOUT",
                    error_message="Execution deadline exceeded",
                )
                break
            process.join(0.1)
        process.join(5)
        snapshot = self.store.get(req.execution_id)
        # 正常完成已写入终态；否则将进程消失收敛为取消或 WORKER_LOST。
        if snapshot.status in {Status.ACCEPTED, Status.RUNNING}:
            cancelled = self.store.cancelled(req.execution_id)
            self.store.finish(
                req,
                Status.CANCELLED if cancelled else Status.FAILED,
                error_code=None if cancelled else "WORKER_LOST",
            )
        with self.lock:
            self.children.pop(req.execution_id, None)

    def close(self):
        """
        禁止新提交并回收已知子进程，然后释放本 Runtime 持有的目录锁。
        """
        with self.lock:
            self.closed = True
            children = list(self.children.values())
        for process, event in children:
            event.set()
            if process.is_alive():
                process.terminate()
            process.join(5)
        # 先发送终止并有界等待，再释放目录所有权；这不是跨节点的进程存活证明。
        fcntl.flock(self._owner_lock, fcntl.LOCK_UN)
        self._owner_lock.close()
