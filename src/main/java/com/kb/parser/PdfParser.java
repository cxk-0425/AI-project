package com.kb.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * PDF 文档解析器
 * <p>
 * 使用 Apache PDFBox 3.x 提取 PDF 文本内容，支持多页自动合并。
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

    @Override
    public String parse(InputStream inputStream, String filename) {
        log.info("[PdfParser] 开始解析 PDF: {}", filename);
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            log.info("[PdfParser] 解析完成: {} 页，共 {} 字符", document.getNumberOfPages(), text.length());
            return text;
        } catch (Exception e) {
            throw new DocumentParseException("PDF 解析失败: " + filename, e);
        }
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }
}
