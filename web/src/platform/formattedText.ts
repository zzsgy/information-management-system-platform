/** 历史纯文本保持原样。带样式的正文附带一行供提醒和列表使用的文字摘要。 */
const header = "IMS-RICH-1\n";
const formatTags = new Set(["B", "STRONG", "I", "EM", "U", "UL", "OL"]);
const allowed = new Set([...formatTags, "P", "DIV", "BR", "LI"]);

/** 从惰性模板重建基础格式，既不复制属性，也不加载外部资源。 */
export function reportMarkup(html: string) {
  const source = document.createElement("template");
  source.innerHTML = html;
  const result = document.createElement("div");
  function append(node: Node, parent: HTMLElement) {
    if (node.nodeType === Node.TEXT_NODE) {
      parent.append(document.createTextNode(node.textContent ?? ""));
    } else if (node instanceof Element) {
      if (["SCRIPT", "STYLE", "TEMPLATE"].includes(node.tagName)) return;
      const target = allowed.has(node.tagName)
        ? document.createElement(node.tagName)
        : parent;
      if (target !== parent) parent.append(target);
      node.childNodes.forEach((child) => append(child, target));
    }
  }
  source.content.childNodes.forEach((node) => append(node, result));
  return result.innerHTML;
}

function htmlStart(value: string) {
  return value.startsWith(header) ? value.indexOf("\n", header.length) + 1 : 0;
}

export function richMarkup(value: string): string | undefined {
  const start = htmlStart(value);
  return start ? reportMarkup(value.slice(start)) : undefined;
}

export function plainPreview(value: string): string {
  const start = htmlStart(value);
  return start ? value.slice(header.length, start - 1) : value;
}

/** 有实际格式才使用富文本封装，普通输入和旧客户端共用原字段契约。 */
export function formattedText(text: string, html: string) {
  if (!text.trim()) return "";
  const markup = reportMarkup(html);
  if (!/<(?:b|strong|i|em|u|ul|ol)>/i.test(markup)) return text;
  const preview = text.trim().replace(/\s+/g, " ").slice(0, 120);
  return header + preview + "\n" + markup;
}
