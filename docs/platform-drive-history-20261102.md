# 公盘文件人员与编辑保存记录

文件详情显示首次上传人和上传时间；历史版本显示各版创建人。上传人与后续在线保存人分开显示，追加版本不会改写首次上传人。

协作记录分为两个页签，均由服务端分页，每页默认 10 条，按时间及稳定 ID 倒序，支持上一页、下一页、直接跳页和总数。列表区域最高 400px，展开多人参与时间或翻查几百条记录时不会无限拉长详情。

- 编辑记录显示参与人、批次发起时间和状态，展开参与人可查看每人加入本批次的时间。
- 保存记录显示申请保存/原生最终保存署名、保存发起时间、正式发布时间、结果和版本号；人工处理另列处理人、处理时刻和接受/舍弃结论。
- 进入编辑并不等于修改了内容。这里展示批次参与时间与保存时刻，不声称记录每次键盘输入或文字修改差异。

沿用 `drive.document.uploaded_by`、`document_version.created_by`、`edit_participant.joined_at`、`save_receipt.requested_by/created_at/resolved_by/resolved_at`，没有补造历史事件，不改变保存链路或权限。负责人查看文档全部记录，普通参与人仅能查询本人参与的批次及其保存记录；每次查询均重验当前 VIEW 权限。响应不含编辑器密钥、内部对象路径、登录会话或凭据。

`GET /api/v1/drive/documents/{id}/office-history` 接受 `sessionPage`、`receiptPage`（1–100000）和 `pageSize`（1–100，默认 10），分别返回页内 sessions/receipts、sessionTotal/receiptTotal、当前正式版本 ID 和授权范围内是否有在途批次。轮询判断及版本刷新不依赖当前历史页，翻旧记录不会误报新版本。

## 验证

使用独立 PostgreSQL 55450 / HTTPS 18456，未往日常预览写入测试人员或记录。

- `OfficeConversionIntegrationTest#documentHistoryPagesKeepPeopleAndAllRecords`：251 条编辑批次及 251 条保存回执，逐页核对 26 页无遗漏、无重复，姓名及参与时间正确，非法页码/页大小拒绝，普通参与人仅看到 2 条，撤回 VIEW 后拒绝查询。
- `OfficeConversionIntegrationTest#saveHistoryShowsPublishedAuthorAndResolution`：在线保存署名、正式版本及发布时间、人工处理人和时间、首次上传人不随版本创建人变化、翻旧页仍能感知当前版本及在途状态。
- Vue TypeScript 检查、16 项现有前端单测和 Vite 平台构建通过。
- Chrome 实际验证上传人、版本创建人、展开参与时间、两个页签翻页/跳到第 26 页、末页各剩 1 条、切换页签保留各自页码。

现场证据位于工作区 `tmp/drive_history_20261102`。发布使用现有后台预览入口，发布前备份，保留现有人员、部门、权限、业务数据库进程及原文件。
