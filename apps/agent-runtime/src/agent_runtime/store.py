"""Runtime 本地持久化：执行去重、快照和事件；与 LangGraph Checkpoint 文件分开。"""

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path

from .contracts import ExecutionEvent, ExecutionStatus, Status, TaskExecutionRequest, now


class Conflict(ValueError):
    """请求键/载荷冲突或容量限制；由内部 HTTP 适配为409，不表示平台业务授权。"""


class RuntimeStore:
    """短连接 SQLite 存储；跨线程/进程依靠数据库事务串行化写入。

    此处状态描述 Runtime 观察，Java Task 状态仍是业务权威。删除本库会失去去重证据。"""

    def __init__(self, directory: str):
        """显式准备目录与元数据表；普通模块导入不会执行这些 IO。"""
        self.directory = directory
        Path(directory).mkdir(parents=True, exist_ok=True)
        self.path = str(Path(directory) / "executions.db")
        with self.connection() as conn:
            # 独立取消表允许先取消、后提交，抵抗 HTTP 到达顺序颠倒。
            conn.execute("CREATE TABLE IF NOT EXISTS cancellation (execution_id TEXT PRIMARY KEY)")
            conn.execute("""CREATE TABLE IF NOT EXISTS execution (
                execution_id TEXT PRIMARY KEY, task_id TEXT NOT NULL,
                idempotency_key TEXT UNIQUE NOT NULL, thread_id TEXT UNIQUE NOT NULL,
                request TEXT NOT NULL, snapshot TEXT NOT NULL, cancel INTEGER NOT NULL DEFAULT 0)""")
            conn.execute("""CREATE TABLE IF NOT EXISTS event (
                execution_id TEXT NOT NULL, sequence INTEGER NOT NULL, payload TEXT NOT NULL,
                PRIMARY KEY(execution_id, sequence))""")

    @contextmanager
    def connection(self):
        """提供单次事务连接，成功提交、异常回滚，并在所有路径关闭句柄。"""
        conn = sqlite3.connect(self.path, timeout=5)
        # WAL 降低读写互阻；busy_timeout 为锁竞争设置有限等待，不等同于高可用。
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=5000")
        try:
            with conn:
                yield conn
        finally:
            conn.close()

    def accept(self, req: TaskExecutionRequest) -> tuple[ExecutionStatus, bool]:
        """原子检查去重、容量与预先取消标记，持久化初始快照。

        Returns:
            (快照, 是否启动进程)：重复请求或已预先取消的请求都不能再启动。
        Raises:
            Conflict: 任一唯一标识冲突但请求不同，或活跃执行已达到四个。"""
        raw = req.model_dump_json()
        with self.connection() as conn:
            # 在查询和写入之间保持写锁，避免两个并发请求都判断为“首次受理”。
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT request,snapshot FROM execution WHERE execution_id=? OR "
                "idempotency_key=? OR thread_id=?",
                (req.execution_id, req.idempotency_key, req.checkpoint_thread_id),
            ).fetchone()
            if row:
                # 按解析后的对象比较，忽略 JSON 键顺序；任一标识复用都要求同一完整请求。
                if json.loads(row[0]) != json.loads(raw):
                    raise Conflict("IDEMPOTENCY_CONFLICT")
                return ExecutionStatus.model_validate_json(row[1]), False
            if (
                conn.execute(
                    "SELECT count(*) FROM execution WHERE json_extract(snapshot, '$.status') "
                    "IN ('ACCEPTED','RUNNING')"
                ).fetchone()[0]
                >= 4
            ):
                raise Conflict("RUNTIME_CAPACITY")
            cancelled = (
                conn.execute(
                    "SELECT 1 FROM cancellation WHERE execution_id=?", (req.execution_id,)
                ).fetchone()
                is not None
            )
            snapshot = ExecutionStatus(
                task_id=req.task_id,
                execution_id=req.execution_id,
                checkpoint_thread_id=req.checkpoint_thread_id,
                status=Status.CANCELLED if cancelled else Status.ACCEPTED,
                updated_at=now(),
            )
            conn.execute(
                "INSERT INTO execution VALUES (?,?,?,?,?,?,0)",
                (
                    req.execution_id,
                    req.task_id,
                    req.idempotency_key,
                    req.checkpoint_thread_id,
                    raw,
                    snapshot.model_dump_json(),
                ),
            )
            if cancelled:
                self._emit(conn, req, "execution_cancelled")
            return snapshot, not cancelled

    def get(self, execution_id: str) -> ExecutionStatus:
        """读取并校验执行快照；不存在时抛 KeyError，交由协议层返回404。"""
        with self.connection() as conn:
            row = conn.execute(
                "SELECT snapshot FROM execution WHERE execution_id=?", (execution_id,)
            ).fetchone()
        if not row:
            raise KeyError("EXECUTION_NOT_FOUND")
        return ExecutionStatus.model_validate_json(row[0])

    def update(self, execution_id: str, **updates):
        """更新非终态快照；用于开始执行等进度信息，终态必须通过 finish 原子提交。"""
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT snapshot FROM execution WHERE execution_id=?", (execution_id,)
            ).fetchone()
            snapshot = ExecutionStatus.model_validate_json(row[0])
            if snapshot.status in {Status.SUCCEEDED, Status.FAILED, Status.CANCELLED}:
                return
            updated = snapshot.model_copy(update={**updates, "updated_at": now()})
            conn.execute(
                "UPDATE execution SET snapshot=? WHERE execution_id=?",
                (updated.model_dump_json(), execution_id),
            )

    def emit(self, req: TaskExecutionRequest, kind: str, **details):
        """在独立写事务中追加普通执行事件；不携带原始模型输入输出。"""
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            self._emit(conn, req, kind, **details)

    def _emit(self, conn, req, kind, **details):
        """复用调用方写事务生成运行内连续序号；调用前必须已取得写事务。"""
        sequence = conn.execute(
            "SELECT coalesce(max(sequence),0)+1 FROM event WHERE execution_id=?",
            (req.execution_id,),
        ).fetchone()[0]
        event = ExecutionEvent(
            task_id=req.task_id,
            execution_id=req.execution_id,
            trace_id=req.trace_id,
            sequence=sequence,
            event_type=kind,
            timestamp=now(),
            **details,
        )
        conn.execute(
            "INSERT INTO event VALUES (?,?,?)",
            (req.execution_id, sequence, event.model_dump_json()),
        )

    def finish(self, req: TaskExecutionRequest, status: Status, **updates):
        """在同一事务中提交唯一终态和终态事件。

        先提交的终态不可被迟到完成/超时覆盖；若取消意图先持久化，则丢弃成功结果。"""
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT snapshot,cancel FROM execution WHERE execution_id=?", (req.execution_id,)
            ).fetchone()
            snapshot = ExecutionStatus.model_validate_json(row[0])
            if snapshot.status in {Status.SUCCEEDED, Status.FAILED, Status.CANCELLED}:
                return
            # 取消事务先取得写锁时，不能再把迟到的成功结果发布为 SUCCEEDED。
            if row[1]:
                status = Status.CANCELLED
                updates = {}
            kind = {
                Status.SUCCEEDED: "execution_completed",
                Status.FAILED: "execution_failed",
                Status.CANCELLED: "execution_cancelled",
            }[status]
            # 事件和状态共用连接/事务，避免已完成状态搭配 execution_failed 事件。
            self._emit(conn, req, kind)
            final = snapshot.model_copy(update={**updates, "status": status, "updated_at": now()})
            conn.execute(
                "UPDATE execution SET snapshot=? WHERE execution_id=?",
                (final.model_dump_json(), req.execution_id),
            )

    def events(self, execution_id: str, after: int = 0):
        """按排他游标查询最多500条事件；调用者以最后一个 sequence 继续拉取。"""
        self.get(execution_id)
        with self.connection() as conn:
            return [
                json.loads(row[0])
                for row in conn.execute(
                    "SELECT payload FROM event WHERE execution_id=? AND sequence>? "
                    "ORDER BY sequence LIMIT 500",
                    (execution_id, after),
                )
            ]

    def cancel(self, execution_id: str):
        """幂等保存取消意图，允许记录尚不存在；后续 accept 会读取取消墓碑。"""
        with self.connection() as conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                "INSERT INTO cancellation VALUES (?) ON CONFLICT DO NOTHING", (execution_id,)
            )
            conn.execute("UPDATE execution SET cancel=1 WHERE execution_id=?", (execution_id,))

    def cancelled(self, execution_id: str):
        """读取已存在执行的取消标志，供监控循环与执行结束检查使用。"""
        with self.connection() as conn:
            return bool(
                conn.execute(
                    "SELECT cancel FROM execution WHERE execution_id=?", (execution_id,)
                ).fetchone()[0]
            )

    def recover(self):
        # Runtime 目录锁保证恢复时没有另一个所有者；保留请求/Checkpoint 供人工核对。
        """把上次 Runtime 遗留的未知执行失败关闭；有取消标记时由 finish 收敛为取消。

        不据 Checkpoint 自动重放，因为无法证明外部副作用是否已经执行。"""
        with self.connection() as conn:
            requests = [
                TaskExecutionRequest.model_validate_json(r[0])
                for r in conn.execute(
                    "SELECT request FROM execution WHERE json_extract(snapshot,'$.status') "
                    "IN ('ACCEPTED','RUNNING')"
                )
            ]
        for req in requests:
            self.finish(
                req,
                Status.FAILED,
                error_code="WORKER_LOST",
                error_message="Runtime restarted; execution requires manual reconciliation",
            )
