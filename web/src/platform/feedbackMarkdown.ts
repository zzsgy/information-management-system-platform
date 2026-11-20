import MarkdownIt from "markdown-it";

/** 截图标识在编辑期间引用本地内容，提交后按反馈和消息查询。 */
export interface FeedbackImageDraft {
  id: string;
  dataUrl: string;
}

const imageReference =
  /^feedback-image\/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$/;
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
export function renderFeedbackMarkdown(
  value: string,
  imageUrl: (id: string) => string | undefined,
) {
  return markdown.render(value, { imageUrl });
}

/** 从正文移除图片引用时，该图片随即不参与提交。 */
export function referencedImages(value: string, images: FeedbackImageDraft[]) {
  const references = new Set(
    markdown
      .parse(value, {})
      .flatMap((token) => token.children ?? [])
      .filter((token) => token.type === "image")
      .map(
        (token) => imageReference.exec(String(token.attrGet("src") ?? ""))?.[1],
      ),
  );
  return images.filter((image) => references.has(image.id));
}

/** 正文只保存 Markdown 引用，上传时单独提交其关联图片字节。 */
export function feedbackMarkdownPayload(
  value: string,
  images: FeedbackImageDraft[],
) {
  return {
    contentFormat: "MARKDOWN",
    images: referencedImages(value, images).map(({ id, dataUrl }) => ({
      id,
      data: dataUrl.slice(dataUrl.indexOf(",") + 1),
    })),
  };
}
