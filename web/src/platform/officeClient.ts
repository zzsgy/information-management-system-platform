/** 公盘和会议共用已配置文档服务的原生编辑器入口。 */
export async function loadEditorScript(url: string) {
  if (window.DocsAPI) return;
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement("script");
    const fail = () => {
      clearTimeout(timeout);
      script.remove();
      reject(
        Error(
          "无法加载 Office 编辑器，请检查文档服务连接及本机证书信任后重试。",
        ),
      );
    };
    const timeout = setTimeout(fail, 15000);
    script.src = url;
    script.onload = () => {
      clearTimeout(timeout);
      if (window.DocsAPI) resolve();
      else fail();
    };
    script.onerror = fail;
    document.head.appendChild(script);
  });
}
