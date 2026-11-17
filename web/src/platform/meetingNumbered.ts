import type { ObjectDirective } from "vue";

/** 在手写编号段落末尾回车时续号，空编号段回车时结束列表。 */
function nextLine(line: string) {
  const match = /^([ \t]*)(\d{1,8})([.、])([ \t]*)(.*)$/.exec(line);
  if (!match) return;
  const [, indent, number, punctuation, spacing, content] = match;
  return content.trim() || !spacing
    ? "\n" + indent + (Number(number) + 1) + punctuation + (spacing || " ")
    : "";
}

function continueNumbering(event: KeyboardEvent) {
  if (
    event.key !== "Enter" || event.isComposing || event.shiftKey ||
    event.ctrlKey || event.altKey || event.metaKey
  ) return;
  const editor = event.currentTarget;
  if (editor instanceof HTMLTextAreaElement) {
    const caret = editor.selectionStart;
    if (caret !== editor.selectionEnd || !["", "\n"].includes(editor.value.charAt(caret))) return;
    const start = editor.value.lastIndexOf("\n", caret - 1) + 1;
    const insert = nextLine(editor.value.slice(start, caret));
    if (insert === undefined || (editor.maxLength >= 0 && editor.value.length + insert.length > editor.maxLength)) return;
    event.preventDefault();
    editor.setRangeText(insert, insert ? caret : start, caret, "end");
    editor.dispatchEvent(new Event("input", { bubbles: true }));
  } else if (editor instanceof HTMLElement && editor.isContentEditable) {
    const selection = document.getSelection();
    const node = selection?.anchorNode;
    if (!node || node.nodeType !== Node.TEXT_NODE || !selection?.isCollapsed) return;
    const text = node.textContent ?? "";
    if (selection.anchorOffset !== text.length || node.parentElement?.closest("li")) return;
    const line = text.split("\n").at(-1) ?? "";
    const insert = nextLine(line);
    if (insert === undefined) return;
    event.preventDefault();
    if (insert) document.execCommand("insertText", false, insert);
    else {
      selection.setBaseAndExtent(node, text.length - line.length, node, text.length);
      document.execCommand("delete");
    }
  }
}

export const vNumbered: ObjectDirective<HTMLElement> = {
  mounted(element) {
    element.addEventListener("keydown", continueNumbering);
  },
  unmounted(element) {
    element.removeEventListener("keydown", continueNumbering);
  },
};
