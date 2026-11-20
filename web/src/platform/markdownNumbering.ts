const numbered = /^([ \t]*)(\d{1,8}(?:\.\d{1,8})*)\.[ \t]+(.*)$/;

/** 围栏代码内保留原文，包括以数字开头的代码行。 */
function outsideFences(
  lines: string[],
  transform: (line: string, index: number) => string,
) {
  let fence = "";
  return lines.map((line, index) => {
    const delimiter = /^[ \t]*(`{3,}|~{3,})(.*)$/.exec(line);
    if (delimiter) {
      const marker = delimiter[1]!;
      if (!fence) fence = marker;
      else if (
        marker[0] === fence[0] &&
        marker.length >= fence.length &&
        !delimiter[2]!.trim()
      )
        fence = "";
      return line;
    }
    return fence ? line : transform(line, index);
  });
}

/** 编辑框保留完整编号；渲染时将 1.1. 转成 Markdown 的嵌套有序列表。 */
export function normalizeMarkdownNumbering(source: string) {
  return outsideFences(source.split("\n"), (line) => {
    const item = numbered.exec(line);
    const parts = item?.[2]?.split(".");
    if (!parts || parts.length < 2) return line;
    const indent = parts
      .slice(0, -1)
      .reduce((width, part) => width + part.length + 2, 0);
    return " ".repeat(indent) + parts.at(-1) + ". " + item![3];
  }).join("\n");
}

/** 收到列表快捷键时替换光标附近的编号，普通段落仍按浏览器原规则操作。 */
export function markdownNumberingEdit(
  value: string,
  caret: number,
  key: "Enter" | "Tab",
  shift: boolean,
) {
  const start = caret === 0 ? 0 : value.lastIndexOf("\n", caret - 1) + 1;
  const ending = value.indexOf("\n", caret);
  const end = ending < 0 ? value.length : ending;
  const line = value.slice(start, end);
  const current = numbered.exec(line);
  if (!current || caret < start + current[0].length - current[3]!.length)
    return;
  let inList = false;
  const preceding = (value.slice(0, start) + line).split("\n");
  outsideFences(preceding, (part, index) => {
    inList ||= index === preceding.length - 1;
    return part;
  });
  if (!inList) return;

  const numbers = current[2]!.split(".").map(Number);
  const next = (parts: number[]) => [...parts.slice(0, -1), parts.at(-1)! + 1];
  const prefix = (parts: number[]) =>
    "  ".repeat(parts.length - 1) + parts.join(".") + ". ";
  if (key === "Enter") {
    if (shift) return;
    if (current[3]!.trim())
      return { start: caret, end: caret, text: "\n" + prefix(next(numbers)) };
    const text = numbers.length > 1 ? prefix(next(numbers.slice(0, -1))) : "";
    return { start, end, text };
  }

  if (shift) {
    if (numbers.length === 1) return;
    return {
      start,
      end: start + current[0].length - current[3]!.length,
      text: prefix(next(numbers.slice(0, -1))),
    };
  }
  const previous =
    value
      .slice(0, Math.max(0, start - 1))
      .split("\n")
      .at(-1) ?? "";
  const parent = numbered.exec(previous)?.[2]?.split(".").map(Number);
  if (!parent || parent.length < numbers.length) return;
  const child = (parent[numbers.length] ?? 0) + 1;
  return {
    start,
    end: start + current[0].length - current[3]!.length,
    text: prefix([...parent.slice(0, numbers.length), child]),
  };
}
