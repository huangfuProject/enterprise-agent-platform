"""独立审批图 PoC：验证持久化 interrupt/resume，不将其作为业务任务恢复 API。"""

from typing import TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.types import interrupt


class ApprovalState(TypedDict):
    """仅属于验证图的内部状态；task_id/execution_id 只负责与业务记录关联。"""

    task_id: str
    execution_id: str
    prepared: int
    approved: bool


def approval_graph(saver):
    """在调用者提供的 Checkpointer 上编译验证图；新实例仍须使用同一个 thread_id。"""

    def prepare(state: ApprovalState):
        """记录已经完成的准备节点，用计数断言恢复不会重跑上一个已完成节点。"""
        return {"prepared": state.get("prepared", 0) + 1}

    def approve(state: ApprovalState):
        """在安全位置中断；重入本节点时 interrupt 消费 Command(resume) 提供的值。"""
        # 恢复会重新进入中断节点；不要在 interrupt 前安排不可幂等的外部副作用。
        return {"approved": bool(interrupt({"task_id": state["task_id"]}))}

    graph = StateGraph(ApprovalState)
    graph.add_node("prepare", prepare)
    graph.add_node("approve", approve)
    graph.add_edge(START, "prepare")
    graph.add_edge("prepare", "approve")
    graph.add_edge("approve", END)
    # Checkpoint 只保存图状态，不能据此推断 Java Task 已完成或外部副作用可重放。
    return graph.compile(checkpointer=saver)
