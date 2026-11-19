import { api, ApiError } from "./api";

/** 服务器决定的档案表示，安全题名不含秘密摘要或候选版本。 */
export interface ArchiveItem {
  /** 当前字段表示。 */ representation: "MANAGE" | "METADATA" | "TITLE_ONLY";
  /** 只有必要管理/使用权限才返回的目录。 */ record?: ArchiveRecord;
  /** 经批准的安全检索摘要。 */ summary?: {
    /** 文件标识。 */ objectId: string;
    /** 安全题名。 */ safeTitle: string;
    /** 当前可申请电子使用。 */ canApply: boolean;
    /** 已关联纸质原件且当前可以申请。 */ canBorrowPaper: boolean;
    /** 已批准借出的纸质原件当前仍占用。 */ paperOccupied?: boolean;
  };
}
/** 已获权目录或候选管理视图，不包含原件路径与实体键。 */
export interface ArchiveRecord {
  /** 实际载体；纯纸质目录不挂接电子正文。 */ carrierType:
    | "ELECTRONIC"
    | "PAPER"
    | "MIXED";
  /** 文件标识。 */ id: string;
  /** 独立文件号。 */ fileNo: string;
  /** 所属盒标识。 */ boxId: string;
  /** 盒号。 */ boxNo: string;
  /** 文件名称。 */ title: string;
  /** 安全题名。 */ safeTitle: string | null;
  /** 所属部门。 */ departmentId: string;
  /** 保密等级。 */ classification: string;
  /** 当前业务状态。 */ state: string;
  /** 记录并发版本。 */ rowVersion?: number;
  /** 当前批准目录修订。 */ currentRevisionId: string | null;
  /** 当前批准PDF。 */ currentPdfVersionId: string | null;
  /** 本人目前实际查看权，管理身份不能推导该值。 */ canView: boolean;
  /** 独立下载批准，查看和管理均不能推导。 */ canDownload: boolean;
  /** 独立打印批准且当前尚有累计份数额度。 */ canPrint: boolean;
  /** 当前纸质原件占用情况。 */ paperOccupied?: boolean;
  /** 本人经总经理批准的无水印使用动作。 */ unwatermarkedActions?: string[];
  /** 待审目录修订，仅管理视图返回。 */ candidateRevisionId?: string;
  /** 待审PDF，仅管理视图返回。 */ candidatePdfVersionId?: string | null;
  /** 候选实测页数。 */ candidatePageCount?: number;
  /** 固定候选的人工质量确认。 */ qualityCheckId?: string | null;
  /** 当前归档复核实例。 */ reviewContextId?: string | null;
}
/** 本人水印使用回执，技术任务与业务批准分开。 */
export interface ArchiveUsage {
  /** 当前实际固定PDF版本号。 */ versionNo: number;
  /** 本人阅读会话。 */ usageId: string;
  /** 页数处理任务。 */ jobId: string;
  /** 准备状态。 */ state: string;
  /** 真实页数。 */ pageCount: number;
  /** 当前截止时刻。 */ expiresAt: string;
}
/** 有限后台轮询不续期登录，页面销毁后不能继续发布或打开晚到的结果。 */
export async function waitArchiveJob<T>(
  id: string,
  active: () => boolean,
): Promise<T | null> {
  for (let attempt = 0; attempt < 90 && active(); attempt++) {
    const task = await api<{
      state: string;
      errorCode: string | null;
      errorMessage?: string;
      requestId?: string;
      result: T | null;
    }>(`/jobs/${id}`, {}, false);
    if (task.state === "SUCCEEDED") return task.result;
    if (["FAILED", "CANCELLED", "UNKNOWN"].includes(task.state))
      throw new ApiError(task.errorCode || "FILE_PROCESSING_FAILED",
        `${task.errorMessage || "文件处理未完成，请联系 IT 管理员核查任务"}\n任务编号：${id}`, task.requestId);

    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error("文件仍在处理中，请稍后查询处理结果。");
}
/** 浏览器整文件摘要上限50MiB，分片只在受控短请求中交付，服务端仍重新测量。 */
export async function uploadArchivePdf(
  record: string,
  file: File,
  active: () => boolean,
  progress: (message: string) => void,
  /** 凭据复用分片检测链，但不允许挂接为正式档案。 */ purpose:
    | "ARCHIVE_PDF"
    | "EVIDENCE"
    | "IMPORT_XLSX"
    | "ARCHIVE_CHECKLIST"
    | "ARCHIVE_REGISTRATION_CHECKLIST"
    | "ARCHIVE_ATTACHMENT" = "ARCHIVE_PDF",
  /** 导入所属模块；普通档案和凭据仍固定为 ARCHIVE。 */ module:
    | "ARCHIVE"
    | "PLATFORM" = "ARCHIVE",
) {
  const workbook = [
    "IMPORT_XLSX",
    "ARCHIVE_CHECKLIST",
    "ARCHIVE_REGISTRATION_CHECKLIST",
  ].includes(purpose);
  if (
    file.size < 1 ||
    file.size > 50 * 1024 * 1024 ||
    !(workbook
      ? file.name.toLowerCase().endsWith(".xlsx")
      : purpose === "ARCHIVE_ATTACHMENT"
        ? /\.(pdf|docx?|xlsx?|pptx?)$/i.test(file.name)
        : purpose === "EVIDENCE"
          ? /\.(pdf|jpe?g|png|mp3|m4a|wav|amr)$/i.test(file.name)
          : file.name.toLowerCase().endsWith(".pdf"))
  )
    throw new Error("请选择符合用途且不超过50 MB的文件。");
  async function sha(blob: Blob) {
    const bytes = new Uint8Array(
      await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()),
    );
    return Array.from(bytes, (value) =>
      value.toString(16).padStart(2, "0"),
    ).join("");
  }
  progress("正在校验文件");
  const hash = await sha(file);
  const session = await api<{ uploadId: string; partSize: number }>(
    "/storage/upload-sessions",
    {
      method: "POST",
      headers: { "X-Operation-Id": crypto.randomUUID() },
      body: JSON.stringify({
        module,
        purpose,
        businessContextId: record,
        originalName: file.name,
        expectedSize: file.size,
        expectedSha256: hash,
        expectedMediaType: workbook
          ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          : ["EVIDENCE", "ARCHIVE_ATTACHMENT"].includes(purpose)
            ? "application/octet-stream"
            : "application/pdf",
      }),
    },
  );
  const count = Math.ceil(file.size / session.partSize);
  for (let number = 1; number <= count; number++) {
    if (!active()) throw new Error("页面已关闭，候选尚未挂接。");
    const offset = (number - 1) * session.partSize,
      part = file.slice(offset, Math.min(offset + session.partSize, file.size));
    progress(`正在上传 ${Math.round((number / count) * 100)}%`);
    await api(`/storage/upload-sessions/${session.uploadId}/parts/${number}`, {
      method: "PUT",
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Range": `bytes ${offset}-${offset + part.size - 1}/${file.size}`,
        "X-Content-SHA256": await sha(part),
      },
      body: part,
    });
  }
  progress("正在检测文件");
  const task = await api<{ jobId: string }>(
    `/storage/upload-sessions/${session.uploadId}/complete`,
    {
      method: "POST",
      headers: { "X-Operation-Id": crypto.randomUUID() },
      body: JSON.stringify({ partCount: count, finalSha256: hash }),
    },
  );
  await waitArchiveJob(task.jobId, active);
  return session.uploadId;
}
/** 所有状态采用已确认业务代码，页面只翻译名称。 */
export const archiveStates: Record<string, string> = {
  DRAFT: "草稿",
  PENDING_REVIEW: "归档复核中",
  PUBLISHED: "已归档",
  WITHDRAWN: "已撤回",
  PENDING: "审批中",
  APPROVED: "已批准",
  REJECTED: "已驳回",
  PARTIAL: "部分已办理",
  CLOSED: "已结束",
  ACTIVE: "有效",
  REVOKED: "已撤权",
  EXPIRED: "已到期",
};
export const classifications: Record<string, string> = {
  GENERAL: "一般",
  IMPORTANT: "重要保密",
  CORE: "核心保密",
};
