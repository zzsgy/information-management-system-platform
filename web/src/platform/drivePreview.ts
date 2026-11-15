/** 阅读能力按已检测媒体类型判断，历史版本不能借用当前文件后缀。 */
export function isDrivePreviewable(mediaType: string): boolean {
  return [
    "application/pdf",
    "image/png",
    "image/jpeg",
    "image/tiff",
    "application/msword",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
  ].includes(mediaType);
}
