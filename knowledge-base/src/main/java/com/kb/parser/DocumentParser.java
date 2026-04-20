package com.kb.parser;

import java.io.InputStream;

/**
 * 文档解析器接口
 * <p>
 * 所有文档格式解析器均实现此接口，返回纯文本内容供后续切块和向量化使用。
 */
public interface DocumentParser {

    /**
     * 解析文档，返回纯文本内容。
     *
     * @param inputStream 文档输入流
     * @param filename    原始文件名（用于格式判断和日志）
     * @return 解析后的纯文本内容
     * @throws DocumentParseException 解析失败时抛出
     */
    String parse(InputStream inputStream, String filename);

    /**
     * 判断当前解析器是否支持该文件类型。
     *
     * @param filename 文件名
     * @return true=支持
     */
    boolean supports(String filename);

    /** 解析异常 */
    class DocumentParseException extends RuntimeException {
        public DocumentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
