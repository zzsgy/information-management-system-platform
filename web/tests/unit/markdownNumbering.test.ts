import { describe, expect, it } from "vitest";
import {
  markdownNumberingEdit,
  normalizeMarkdownNumbering,
} from "../../src/platform/markdownNumbering";
import { renderMarkdownWithImages } from "../../src/platform/markdownImages";

function press(value: string, key: "Enter" | "Tab", shift = false) {
  const edit = markdownNumberingEdit(value, value.length, key, shift);
  return edit
    ? value.slice(0, edit.start) + edit.text + value.slice(edit.end)
    : value;
}

describe("周报自动编号", () => {
  it("回车续号、Tab 创建子级、Shift+Tab 返回上一级", () => {
    const next = press("1. 上周工作", "Enter");
    expect(next).toBe("1. 上周工作\n2. ");
    const child = press(next, "Tab");
    expect(child).toBe("1. 上周工作\n  1.1. ");
    expect(press(child + "核对数据", "Enter")).toBe(
      "1. 上周工作\n  1.1. 核对数据\n  1.2. ",
    );
    expect(press(child, "Tab", true)).toBe(next);
    expect(press(child, "Enter")).toBe(next);
    expect(press(next, "Enter")).toBe("1. 上周工作\n");
  });

  it("仅在列表内响应快捷键，并保留代码块原文", () => {
    expect(markdownNumberingEdit("普通段落", 4, "Tab", false)).toBeUndefined();
    const fenced = "```text\n  1.1. 样例";
    expect(press(fenced, "Enter")).toBe(fenced);
    expect(normalizeMarkdownNumbering(fenced)).toBe(fenced);
  });

  it("预览渲染嵌套列表和内联图片", () => {
    const id = "00000000-0000-0000-0000-000000000001";
    const rendered = renderMarkdownWithImages(
      "1. 上周工作\n  1.1. 核对 ![图片](inline-image/" + id + ")",
      () => "/allowed-image",
      true,
    );
    expect(rendered.match(/<ol>/g)).toHaveLength(2);
    expect(rendered).toContain('src="/allowed-image"');
  });
});
