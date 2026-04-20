package com.kb.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 纯文本 / Markdown 文档解析器
 * <p>
 * 支持 .txt 和 .md 格式，直接读取文本内容，不做额外转换。
 */
@Slf4j
@Component
public class TextParser implements DocumentParser {

    @Override
    public String parse(InputStream inputStream, String filename) {
        log.info("[TextParser] 开始解析文本文件: {}", filename);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String text = reader.lines().collect(Collectors.joining("\n"));
            log.info("[TextParser] 解析完成: {} 字符", text.length());
            return text;
        } catch (Exception e) {
            throw new DocumentParseException("文本文件解析失败: " + filename, e);
        }
    }

    @Override
    public boolean supports(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".markdown") || lower.endsWith(".text");
    }
}
