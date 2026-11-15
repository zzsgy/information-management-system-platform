import { ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import type { Row } from "./model";

/** 用于用户主动操作的防重入和错误反馈；失败返回 false，调用方不得关闭失败表单。 */
export function useAction() {
  const busy = ref(false); // 当前组件的提交锁，不影响其他只读区域。
  async function run(
    task: () => Promise<void>,
    success?: string,
  ): Promise<boolean> {
    if (busy.value) return false;
    busy.value = true;
    try {
      await task();
      if (success) ElMessage.success(success);
      return true;
    } catch (error) {
      if (error !== "cancel" && error !== "close")
        ElMessage.error(error instanceof Error ? error.message : "操作失败");
      return false;
    } finally {
      busy.value = false;
    }
  }
  return { busy, run };
}
/** 不可撤销业务操作需要用户明确确认对象和影响，取消不发出请求。 */
export async function confirmAction(message: string): Promise<void> {
  await ElMessageBox.confirm(message, "请确认", {
    type: "warning",
    confirmButtonText: "确认",
    cancelButtonText: "取消",
  });
}
/** CSV 单元格防公式注入并保留逗号、换行及双引号。 */
export function csvCell(value: unknown): string {
  let text = String(value ?? ""); // 从业务数据转换的纯文本单元格。
  if (/^[\s]*[=+@\-]/.test(text) || /^[\t\r\n]/.test(text)) text = `'${text}`;
  return `"${text.replaceAll('"', '""')}"`;
}
/** CSV 导出添加 BOM，中文 Excel 默认可以正确识别；列为固定白名单。 */
export function csv(rows: Row[], columns: Record<string, string>): Blob {
  return new Blob(
    [
      "\ufeff",
      Object.values(columns).map(csvCell).join(","),
      "\r\n",
      ...rows.map(
        (row) =>
          Object.keys(columns)
            .map((key) => csvCell(row[key]))
            .join(",") + "\r\n",
      ),
    ],
    { type: "text/csv;charset=utf-8" },
  );
}
