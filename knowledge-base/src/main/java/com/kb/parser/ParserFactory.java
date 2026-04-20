package com.kb.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析器工厂
 * <p>
 * 根据文件名自动选择合适的解析器，支持 Spring 自动注入所有 DocumentParser 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParserFactory {

    private final List<DocumentParser> parsers;

    /**
     * 根据文件名选择解析器。
     *
     * @param filename 文件名
     * @return 匹配的解析器
     * @throws IllegalArgumentException 若无匹配解析器
     */
    public DocumentParser getParser(String filename) {
        return parsers.stream()
                .filter(p -> p.supports(filename))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的文件类型: " + filename + "。支持格式: pdf, docx, doc, txt, md"));
    }

    /** 判断文件类型是否受支持 */
    public boolean isSupported(String filename) {
        return parsers.stream().anyMatch(p -> p.supports(filename));
    }
}
