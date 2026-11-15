import { api } from "./api";
export const driveAccept =
  ".doc,.docx,.xls,.xlsx,.ppt,.pptx,.pdf,.txt,.csv,.jpg,.jpeg,.png,.tif,.tiff,.zip,.rar,.7z,.dwg,.dxf";
/** 初次上传与版本上传复用分片链路，只有检测通过后才向业务层返回会话标识。 */
export async function uploadDrive(
  file: File,
  context: string,
  purpose: "DRIVE_FILE" | "DRIVE_VERSION",
  progress: (label: string, value: number | null) => void,
  signal: AbortSignal,
) {
  if (
    file.size < 1 ||
    file.size > 5 * 1024 ** 3 ||
    !driveAccept
      .split(",")
      .includes("." + file.name.split(".").at(-1)?.toLowerCase())
  )
    throw Error("请选择允许格式的文件，单件上限为5 GB。");
  const hash = await new Promise<string>((resolve, reject) => {
    const worker = new Worker(
      new URL("./driveHash.worker.ts", import.meta.url),
      { type: "module" },
    );
    const stop = () => {
      worker.terminate();
      reject(Error("上传已取消，文件尚未发布。"));
    };
    const cleanup = () => {
      worker.terminate();
      signal.removeEventListener("abort", stop);
    };
    if (signal.aborted) {
      stop();
      return;
    }
    signal.addEventListener("abort", stop, { once: true });
    worker.onmessage = (event) => {
      if (event.data.hash) {
        cleanup();
        resolve(event.data.hash);
      } else if (event.data.error) {
        cleanup();
        reject(Error(event.data.error));
      } else progress("正在计算文件摘要", event.data.progress);
    };
    worker.onerror = () => {
      cleanup();
      reject(Error("文件摘要计算失败。"));
    };
    worker.postMessage(file);
  });
  const session = await api<{ uploadId: string; partSize: number }>(
    "/storage/upload-sessions",
    {
      method: "POST",
      signal,
      headers: { "X-Operation-Id": crypto.randomUUID() },
      body: JSON.stringify({
        module: "DRIVE",
        purpose,
        businessContextId: context,
        originalName: file.name,
        expectedSize: file.size,
        expectedSha256: hash,
        expectedMediaType: "application/octet-stream",
      }),
    },
  );
  const count = Math.ceil(file.size / session.partSize);
  for (let part = 1; part <= count; part++) {
    const offset = (part - 1) * session.partSize,
      blob = file.slice(offset, offset + session.partSize),
      digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
    await api(`/storage/upload-sessions/${session.uploadId}/parts/${part}`, {
      method: "PUT",
      signal,
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Range": `bytes ${offset}-${offset + blob.size - 1}/${file.size}`,
        "X-Content-SHA256": Array.from(new Uint8Array(digest), (n) =>
          n.toString(16).padStart(2, "0"),
        ).join(""),
      },
      body: blob,
    });
    progress("正在上传", Math.round((part / count) * 100));
  }
  const task = await api<{ jobId: string }>(
    `/storage/upload-sessions/${session.uploadId}/complete`,
    {
      method: "POST",
      signal,
      headers: { "X-Operation-Id": crypto.randomUUID() },
      body: JSON.stringify({ partCount: count, finalSha256: hash }),
    },
  );
  // 100%只代表分片传输完成；合并和检测没有可用的百分比。
  progress("文件已上传，等待处理", null);
  for (let attempt = 0; attempt < 360; attempt++) {
    signal.throwIfAborted();
    const job = await api<{
      state: string;
      jobType: string;
      errorCode: string;
    }>(`/jobs/${task.jobId}`, { signal }, false);
    if (job.state === "SUCCEEDED") return session.uploadId;
    if (job.errorCode?.startsWith("SERVICE_IDENTITY_")) {
      progress(
        job.errorCode === "SERVICE_IDENTITY_EXPIRED"
          ? "检测服务授权已到期，请联系管理员；文件已接收，恢复后继续检测"
          : "检测服务授权配置未就绪，请联系管理员；文件已接收，恢复后继续检测",
        null,
      );
      await new Promise((resolve) => setTimeout(resolve, 2000));
      continue;
    }
    if (["FAILED", "CANCELLED", "UNKNOWN"].includes(job.state))
      throw Error(
        job.errorCode === "SCANNER_UNAVAILABLE"
          ? "检测服务暂不可用，文件尚未发布。"
          : "文件检测未通过，文件尚未发布。",
      );
    progress(
      job.jobType === "FILE_ASSEMBLY_CHECK"
        ? job.state === "RUNNING"
          ? "文件已上传，正在合并校验"
          : "文件已上传，等待合并校验"
        : job.state === "RUNNING"
          ? "文件已上传，正在检测"
          : "文件已上传，等待检测服务处理",
      null,
    );
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw Error("文件仍在处理中，请稍后查询处理结果。");
}
