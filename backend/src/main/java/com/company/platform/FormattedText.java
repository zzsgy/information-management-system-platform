package com.company.platform;

/** 给纯文本提醒和摘要读取富文本自带的预览，兼容历史普通文本。 */
public final class FormattedText {
    private static final String HEADER = "IMS-RICH-1\n";
    private FormattedText() {}

    public static String preview(String value) {
        if (value == null || !value.startsWith(HEADER)) return value;
        int end = value.indexOf('\n', HEADER.length());
        return end < 0 ? value : value.substring(HEADER.length(), end);
    }
}
