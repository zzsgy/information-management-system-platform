import MarkdownIt from "markdown-it";
import { normalizeMarkdownNumbering } from "./markdownNumbering";

/** 图片标识在编辑期间引用本地文件，提交后按对应正文查询。 */
export interface MarkdownImageDraft {
  id: string;
  dataUrl: string;
}

const imageReference =
  /^(?:inline|feedback)-image\/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/;
const markdown = new MarkdownIt({ html: false, breaks: true });
const renderImage = markdown.renderer.rules.image!;

/** 只渲染当前消息的内联截图，外部图片降级为替代文本。 */
markdown.renderer.rules.image = (tokens, index, options, context, renderer) => {
  const token = tokens[index]!;
  const id = imageReference.exec(String(token.attrGet("src") ?? ""))?.[1];
  const imageUrl = context?.imageUrl as
    | ((id: string) => string | undefined)
    | undefined;
  const url = id && imageUrl?.(id);
  if (!url) return markdown.utils.escapeHtml(token.content);
  token.attrSet("src", url);
  token.attrSet("loading", "lazy");
  return renderImage(tokens, index, options, context, renderer);
};

/** 预览与已提交正文共用解析，由调用页面确定图片所在范围。 */
export function renderMarkdownWithImages(
  value: string,
  imageUrl: (id: string) => string | undefined,
  numbered = false,
) {
  return markdown.render(numbered ? normalizeMarkdownNumbering(value) : value, {
    imageUrl,
  });
}

/** 只取能被 Markdown 解析的内联图片，代码中的示例链接不参与提交。 */
export function markdownImageKeys(value: string, numbered = false) {
  return new Set(
    markdown
      .parse(numbered ? normalizeMarkdownNumbering(value) : value, {})
      .flatMap((token) => token.children ?? [])
      .filter((token) => token.type === "image")
      .map(
        (token) => imageReference.exec(String(token.attrGet("src") ?? ""))?.[1],
      ),
  );
}

/** 从正文移除图片引用时，该图片随即不参与提交。 */
export function referencedImages(
  value: string,
  images: MarkdownImageDraft[],
  numbered = false,
) {
  const references = markdownImageKeys(value, numbered);
  return images.filter((image) => references.has(image.id));
}

/** 正文只保存 Markdown 引用，上传时单独提交其关联图片字节。 */
export function markdownImageUploads(
  value: string,
  images: MarkdownImageDraft[],
  numbered = false,
) {
  return referencedImages(value, images, numbered).map(({ id, dataUrl }) => ({
    id,
    data: dataUrl.slice(dataUrl.indexOf(",") + 1),
  }));
}
