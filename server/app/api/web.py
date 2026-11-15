# -*- coding: utf-8 -*-
"""
app/api/web.py
===============
轻量 Web 访问：通过浏览器访问系统的只读档案浏览界面。
- /web/login        登录页（表单）
- /web/logout       退出
- /web/archives     档案列表（支持关键词检索、分页）
- /web/archives/{id} 档案详情
认证采用与 API 一致的 JWT，存于 HttpOnly Cookie；未登录访问自动跳转登录页。
"""
import html
from typing import Optional

from fastapi import APIRouter, Request, Depends, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.core.config import settings
from app.core.security import create_access_token, decode_access_token
from app.services.auth import authenticate
from app.services import archive as archive_svc
from app.models.org import User

router = APIRouter(prefix="/web", tags=["Web访问"])

COOKIE = "archive_token"


def _esc(v) -> str:
    if v is None:
        return ""
    return html.escape(str(v))


# 档案状态中文映射（与客户端一致）
_STATUS_CN = {
    "draft": "草稿", "pending": "待归档", "archive": "已归档",
    "borrowed": "借阅中", "locked": "封存", "damaged": "破损",
    "destroy_pending": "待销毁", "destroyed": "已销毁",
}


def _status_cn(code) -> str:
    if code is None:
        return ""
    return _STATUS_CN.get(str(code), str(code))


def _web_loc(it: dict) -> str:
    """与客户端一致的存放位置展示：优先 physical_location，否则 柜号-列号-层号。"""
    loc = it.get("physical_location")
    if loc:
        return loc
    parts = [it.get("cabinet_no"), it.get("column_no"), it.get("layer_no")]
    if any(parts):
        return "-".join(str(p) for p in parts if p)
    return ""


def _current_user(request: Request, db: Session) -> Optional[User]:
    token = request.cookies.get(COOKIE)
    if not token:
        return None
    try:
        payload = decode_access_token(token)
        return db.query(User).filter_by(id=int(payload.get("sub"))).first()
    except Exception:
        return None


BASE_CSS = """
<style>
  body{font-family:'Microsoft YaHei',sans-serif;margin:0;background:#f3f5f8;color:#2f3b4c;}
  .top{background:#2f3b4c;color:#fff;padding:12px 20px;display:flex;justify-content:space-between;align-items:center;}
  .top a{color:#cfe0f5;text-decoration:none;margin-left:14px;}
  .wrap{max-width:1080px;margin:20px auto;background:#fff;padding:20px;border-radius:8px;box-shadow:0 1px 4px rgba(0,0,0,.08);}
  h2{margin:0 0 14px;}
  form.inline{display:inline;}
  input[type=text],input[type=password]{padding:7px 10px;border:1px solid #ccd3dc;border-radius:4px;width:260px;}
  button{padding:7px 16px;border:none;border-radius:4px;background:#2f7fd1;color:#fff;cursor:pointer;}
  table{border-collapse:collapse;width:100%;margin-top:10px;font-size:13px;}
  th,td{border:1px solid #e1e6ec;padding:8px 10px;text-align:left;}
  th{background:#f0f4f9;}
  .msg{color:#c0392b;margin:8px 0;}
  .pager{margin-top:12px;}
  .pager a{margin-right:8px;color:#2f7fd1;text-decoration:none;}
  .kv{margin:6px 0;}
  .kv b{display:inline-block;width:110px;color:#6b7785;}
</style>
"""


def _login_page(error: str = "") -> str:
    return f"""<!DOCTYPE html><html><head><meta charset="utf-8"><title>档案系统登录</title>{BASE_CSS}</head>
<body><div class="wrap">
  <h2>档案管理系统 · Web 登录</h2>
  {f'<p class="msg">{_esc(error)}</p>' if error else ''}
  <form method="post" action="/web/login">
    <div class="kv"><b>用户名</b><input type="text" name="username" autofocus></div>
    <div class="kv"><b>密码</b><input type="password" name="password"></div>
    <div class="kv"><button type="submit">登录</button></div>
  </form>
</div></body></html>"""


@router.get("/login", response_class=HTMLResponse)
def web_login_page():
    return HTMLResponse(_login_page())


@router.post("/login")
def web_login(
    request: Request,
    username: str = Form(...),
    password: str = Form(...),
    db: Session = Depends(get_db),
):
    user = authenticate(db, username, password)
    if not user:
        return HTMLResponse(_login_page("用户名或密码错误"), status_code=200)
    token = create_access_token(user.id, user.username, [])
    resp = RedirectResponse(url="/web/archives", status_code=303)
    resp.set_cookie(
        key=COOKIE, value=token, httponly=True,
        max_age=settings.ACCESS_TOKEN_EXPIRE_MINUTES * 60,
    )
    return resp


@router.get("/logout")
def web_logout():
    resp = RedirectResponse(url="/web/login", status_code=303)
    resp.delete_cookie(COOKIE)
    return resp


@router.get("/", response_class=HTMLResponse)
def web_root(request: Request, db: Session = Depends(get_db)):
    if not _current_user(request, db):
        return RedirectResponse(url="/web/login", status_code=303)
    return RedirectResponse(url="/web/archives", status_code=303)


@router.get("/archives", response_class=HTMLResponse)
def web_archives(
    request: Request,
    db: Session = Depends(get_db),
    keyword: str = "",
    page: int = 1,
):
    user = _current_user(request, db)
    if not user:
        return RedirectResponse(url="/web/login", status_code=303)
    page = max(1, page)
    page_size = 50
    total, rows = archive_svc.query_archives(
        db, keyword=keyword or None, page=page, page_size=page_size
    )
    items = [archive_svc.to_out_dict(db, r) for r in rows]
    tr = (total + page_size - 1) // page_size
    rows_html = "".join(
        f"<tr><td><a href='/web/archives/{it['id']}'>{_esc(it.get('archive_no'))}</a></td>"
        f"<td>{_esc(it.get('archive_name'))}</td>"
        f"<td>{_esc(it.get('file_type'))}</td>"
        f"<td>{_esc(it.get('dept_name'))}</td>"
        f"<td>{_esc(it.get('owner_name'))}</td>"
        f"<td>{_esc(_web_loc(it))}</td>"
        f"<td>{_esc(it.get('storage_type'))}</td>"
        f"<td>{_esc(it.get('copy_count'))}</td>"
        f"<td>{_esc(it.get('security_name'))}</td>"
        f"<td>{_esc(it.get('archive_date'))}</td>"
        f"<td>{_esc(it.get('validity_months'))}</td>"
        f"<td>{_esc(it.get('expiry_date'))}</td>"
        f"<td>{_esc(_status_cn(it.get('status')))}</td></tr>"
        for it in items
    ) or "<tr><td colspan='13' style='text-align:center;color:#999'>暂无数据</td></tr>"
    pager = ""
    if tr > 1:
        parts = []
        if page > 1:
            parts.append(f"<a href='/web/archives?keyword={_esc(keyword)}&page={page-1}'>上一页</a>")
        parts.append(f"<span>第 {page}/{tr} 页</span>")
        if page < tr:
            parts.append(f"<a href='/web/archives?keyword={_esc(keyword)}&page={page+1}'>下一页</a>")
        pager = "<div class='pager'>" + " ".join(parts) + "</div>"

    html_page = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><title>档案列表</title>{BASE_CSS}</head>
<body>
  <div class="top"><span>档案管理系统 · Web 端</span>
    <span>当前用户：{_esc(user.real_name or user.username)}<a href="/web/logout">退出</a></span></div>
  <div class="wrap">
    <h2>档案列表（共 {total} 条）</h2>
    <form method="get" action="/web/archives" class="inline">
      <input type="text" name="keyword" value="{_esc(keyword)}" placeholder="档案编号/名称/关键词">
      <button type="submit">检索</button>
    </form>
    <table>
      <tr><th>档号</th><th>名称</th><th>文件类型</th><th>所属部门</th><th>负责人</th><th>存放位置</th><th>保存类型</th><th>份数</th><th>密级</th><th>存档日期</th><th>有效期(月)</th><th>到期日期</th><th>状态</th></tr>
      {rows_html}
    </table>
    {pager}
    <p style="margin-top:14px;"><a href="/web/login">重新登录</a></p>
  </div>
</body></html>"""
    return HTMLResponse(html_page)


@router.get("/archives/{archive_id}", response_class=HTMLResponse)
def web_archive_detail(
    request: Request, archive_id: int, db: Session = Depends(get_db)
):
    user = _current_user(request, db)
    if not user:
        return RedirectResponse(url="/web/login", status_code=303)
    try:
        arch = archive_svc.get_archive(db, archive_id)
    except ValueError:
        return HTMLResponse("<p class='msg'>档案不存在</p>")
    it = archive_svc.to_out_dict(db, arch)
    rows = "".join(
        f"<div class='kv'><b>{_esc(k)}</b>{_esc(v)}</div>"
        for k, v in [
            ("档号", it.get("archive_no")),
            ("名称", it.get("archive_name")),
            ("分类", it.get("category_name")),
            ("部门", it.get("dept_name")),
            ("状态", _status_cn(it.get("status"))),
            ("文件类型", it.get("file_type")),
            ("档案负责人", it.get("owner_name")),
            ("柜号/列号/层号", _web_loc(it)),
            ("保存类型", it.get("storage_type")),
            ("保存份数", it.get("copy_count")),
            ("密级", it.get("security_name")),
            ("存档日期", it.get("archive_date")),
            ("有效期(月)", it.get("validity_months")),
            ("到期日期", it.get("expiry_date")),
            ("文件数", it.get("file_count")),
            ("备注", it.get("remark")),
        ]
    )
    html_page = f"""<!DOCTYPE html><html><head><meta charset="utf-8"><title>档案详情</title>{BASE_CSS}</head>
<body>
  <div class="top"><span>档案管理系统 · Web 端</span>
    <span><a href="/web/archives">返回列表</a><a href="/web/logout">退出</a></span></div>
  <div class="wrap">
    <h2>档案详情</h2>
    {rows}
  </div>
</body></html>"""
    return HTMLResponse(html_page)
