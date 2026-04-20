package com.kb.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Word 文档解析器（.docx）
 * <p>
 * 使用 Apache POI 5.x 提取 .docx 文本内容。
 * 暂不支持旧版 .doc（Binary Word），如需支持可引入 poi-scratchpad 模块。
 */
@Slf4j
@Component
public class WordParser implements DocumentParser {

    @Override
    public String parse(InputStream inputStream, String filename) {
        log.info("[WordParser] 开始解析 Word 文档: {}", filename);
        try (XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            log.info("[WordParser] 解析完成: {} 字符", text.length());
            return text;
        } catch (Exception e) {
            throw new DocumentParseException("Word 文档解析失败: " + filename, e);
        }
    }

    @Override
    public boolean supports(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".doc");
    }
}
