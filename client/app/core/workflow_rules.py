# -*- coding: utf-8 -*-
"""客户端防误操作规则；服务端必须独立验证权限、数据范围与状态。"""


def approved_destroy_archives(detail: dict) -> list:
    """只接收已批准申请保存的档案明细，失败不得回退全库列表。"""
    if detail.get("status") != "approved":
        raise ValueError("申请已不处于批准状态，请关闭窗口后刷新")
    archives = detail.get("archives") or []  # 本申请实际获批的档案集合。
    if not isinstance(archives, list) or not archives:
        raise ValueError("申请缺少获批档案明细，不能执行销毁")
    ids = [archive.get("id") for archive in archives]  # 与申请保存的 ID 集合交叉核对。
    if any(not isinstance(value, int) or isinstance(value, bool) or value <= 0 for value in ids):
        raise ValueError("获批档案标识无效，不能执行销毁")
    if len(set(ids)) != len(ids) or set(ids) != set(detail.get("archive_ids") or []):
        raise ValueError("申请档案范围与明细不一致，不能执行销毁")
    return archives


def destroy_execution_ids(archives: list, selected_rows: list) -> list:
    """采用整单执行，只有手动选中全部获批档案才能提交。"""
    if not archives:
        raise ValueError("没有可执行的获批档案")
    if set(selected_rows) != set(range(len(archives))):
        raise ValueError("本申请须整单执行，请核对并选择全部获批档案")
    return [archive["id"] for archive in archives]


def can_checkout_borrow(row: dict) -> bool:
    """只有已批准的实物或纸电结合借阅才办理实物借出。"""
    return row.get("status") == "approved" and row.get("borrow_mode") in {"physical", "both"}


def can_return_borrow(row: dict) -> bool:
    """实物必须先借出；纯电子借阅可以结束已批准的使用记录。"""
    return row.get("status") == "borrowed" or (
        row.get("status") == "approved" and row.get("borrow_mode") == "electronic"
    )
