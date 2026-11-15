/** 序号对应清单里的纸质记录，不按纸质份数复制电子文件。 */
export interface ChecklistLine {
  id: string;
  itemNo: number;
  title: string;
}

/** 统一文件扩展名、全半角和空白；保留正文标点，避免相似标题误配。 */
function nameKey(value: string): string {
  return value
    .normalize("NFKC")
    .replace(/\.(pdf|docx?|xlsx?|pptx?)$/i, "")
    .replace(/\s+/g, "")
    .toLowerCase();
}

/** 序号和名称同时存在时必须一致；冲突、同名及重复分配均交给用户选择。 */
export function matchChecklistFiles(names: string[], lines: ChecklistLine[]) {
  const matches = names.map((name) => {
    const stem = name
      .normalize("NFKC")
      .replace(/\.(pdf|docx?|xlsx?|pptx?)$/i, "")
      .trim();
    const prefix =
      /^(?:第\s*)?(\d+)(?:\s*[._、\-）)]\s*|\s+|\s*号\s*)(.+)$/.exec(stem);
    if (prefix) {
      const candidates = lines.filter(
        (line) =>
          line.itemNo === Number(prefix[1]) &&
          nameKey(line.title) === nameKey(prefix[2]),
      );
      return {
        recordId: candidates.length === 1 ? candidates[0].id : "",
        reason:
          candidates.length === 1
            ? "序号与名称一致"
            : "序号或名称不一致，请选择",
      };
    }
    const candidates = lines.filter(
      (line) => nameKey(line.title) === nameKey(stem),
    );
    return {
      recordId: candidates.length === 1 ? candidates[0].id : "",
      reason:
        candidates.length === 1
          ? "名称唯一对应"
          : candidates.length > 1
            ? "存在同名条目，请选择"
            : "未找到对应条目，请选择",
    };
  });
  const counts = new Map<string, number>();
  for (const match of matches)
    if (match.recordId)
      counts.set(match.recordId, (counts.get(match.recordId) || 0) + 1);
  return matches.map((match) =>
    counts.get(match.recordId)! > 1
      ? { recordId: "", reason: "多个文件对应同一条目，请选择一份" }
      : match,
  );
}

/** 柜、列、层带单位组合；历史位置在没有三段登记值时保留。 */
export function archiveLocation(
  values: Record<string, unknown>,
  fallback = "",
) {
  return (
    [
      ["cabinetNo", "柜"],
      ["columnNo", "列"],
      ["layerNo", "层"],
    ]
      .filter(([key]) => String(values[key] ?? "").trim())
      .map(([key, unit]) => `${String(values[key]).trim()}${unit}`)
      .join(" / ") ||
    fallback ||
    "未登记"
  );
}
