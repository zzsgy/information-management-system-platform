import { ApiError, profile, responseError, requestFetch } from "./api";

/** 导出二进制请求固定操作号，自动轮询和后台页加载不延长用户空闲会话。 */
export async function usageBlob(
  path: string,
  operation: string,
  activity = false,
): Promise<Blob> {
  const response = await requestFetch(`/api/v1${path}`, {
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      "X-Operation-Id": operation,
      "X-Request-Id": crypto.randomUUID(),
      "X-CSRF-Token": profile.value?.csrfToken || "",
      ...(activity ? { "X-User-Activity": "1" } : {}),
    },
  });
  if (!response.ok) throw await responseError(response, "文件读取失败");
  if (
    !/^(application\/(pdf|zip)|image\/png)/.test(
      response.headers.get("Content-Type") || "",
    )
  )
    throw new Error("副本格式异常");
  const blob = await response.blob();
  if (blob.size < 1) throw new Error("副本内容为空，未保存文件");
  const expected = response.headers.get("X-Content-SHA256");
  if (expected) {
    const actual = Array.from(
      new Uint8Array(
        await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()),
      ),
      (byte) => byte.toString(16).padStart(2, "0"),
    ).join("");
    if (actual !== expected) throw new Error("副本完整性核验失败，未保存文件");
  }
  return blob;
}
/** 服务端冻结的使用任务，字段不包含存储路径或审批凭据。 */
export interface ExportUsage {
  /** 本人副本任务号。 */ usageId: string;
  /** 技术任务号。 */ jobId: string;
  /** 独立批准的动作。 */ action: "DOWNLOAD" | "PRINT";
  /** 服务器根据有效批准固定的输出模式。 */ unwatermarked?: boolean;
  /** 副本处理状态。 */ state: string;
  /** 实际输出页数，已展开打印份数。 */ pageCount: number;
  /** 当前服务端使用截止。 */ expiresAt: string;
  /** 每份文件的固定版本及范围。 */ items: {
    fileNo: string;
    pages: number[];
    copies: number;
  }[];
}
