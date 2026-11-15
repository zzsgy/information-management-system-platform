import { ApiError, profile, setProfile } from "./api";

/** 不可变凭据原件元数据；内部存储键从不返回。 */
export interface EvidenceFile {
  /** 核验附件标识。 */ id: string;
  /** 原始文件名称。 */ originalName: string;
  /** 服务器实测媒体类型。 */ format: string;
  /** 原件字节数。 */ size: number;
  /** 提交时固定摘要。 */ digest: string;
}
/** 申请人提交的固定同意事实；核验不得修改既有范围。 */
export interface EvidenceSubmission {
  /** 提交批次。 */ id: string;
  /** 固定审批节点。 */ stepId: string;
  /** 原同意人标识。 */ originalApproverId: string;
  /** 原同意人姓名。 */ approverName: string;
  /** 申请人姓名。 */ applicantName: string;
  /** 必要节点职责。 */ dutyCode: string;
  /** 实际同意时间。 */ agreedAt: string;
  /** 申请人的事实说明。 */ description: string;
  /** 核验状态。 */ state: string;
  /** 核验意见。 */ reviewNote?: string;
  /** 实际提交时间。 */ createdAt: string;
  /** 当前节点版本。 */ currentStepVersion: number;
  /** 当前节点状态。 */ stepState: string;
  /** 所属流程状态。 */ workflowState: string;
  /** 提交自身的并发版本。 */ rowVersion: number;
  /** 明确提交给核验人的业务范围，不能扩展查询其他对象。 */ scope: Record<
    string,
    unknown
  >;
  /** 原始附件清单。 */ evidence: EvidenceFile[];
}
/** 固定业务名称不使用后端任意字符串作为 HTML。 */
export const evidenceStates: Record<string, string> = {
  PENDING: "待核验",
  USED: "已补录",
  REJECTED: "核验退回",
  CANCELLED: "已撤回",
};
/** 每次取回原件都携带当前会话并核对提交时摘要；失败清除过期身份。 */
export async function evidenceBlob(item: EvidenceFile): Promise<Blob> {
  const response = await fetch(`/api/v1/workflow/evidence/${item.id}/content`, {
    credentials: "same-origin",
    cache: "no-store",
    headers: {
      "X-Request-Id": crypto.randomUUID(),
      "X-CSRF-Token": profile.value?.csrfToken || "",
      "X-User-Activity": "1",
    },
  });
  if (!response.ok) {
    const result = await response.json();
    if (result.code === "SESSION_INVALID") {
      setProfile(null);
      window.dispatchEvent(new Event("ims-session-expired"));
    }
    throw new ApiError(
      result.code,
      result.message || "凭据原件读取失败",
      result.requestId,
    );
  }
  if (response.headers.get("Content-Type")?.split(";")[0] !== item.format)
    throw new Error("凭据格式与提交记录不一致");
  const blob = await response.blob();
  const digest = Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", await blob.arrayBuffer()),
    ),
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
  if (blob.size !== item.size || digest !== item.digest)
    throw new Error("凭据完整性核验失败");
  return blob;
}
