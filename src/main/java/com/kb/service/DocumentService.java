package com.kb.service;

import com.kb.model.KbDocument;
import com.kb.parser.DocumentParser;
import com.kb.parser.ParserFactory;
import com.kb.repository.DocumentMetaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档处理 Service
 * <p>
 * 负责：文件接收 → 格式解析 → 文本分块 → Embedding 向量化 → 存入 PGVector
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final ParserFactory parserFactory;
    private final VectorStore vectorStore;
    private final DocumentMetaRepository documentMetaRepository;
    private final RagService ragService;

    @Value("${kb.document.chunk-size:800}")
    private int chunkSize;

    @Value("${kb.document.chunk-overlap:200}")
    private int chunkOverlap;

    /**
     * 上传并处理文档：解析 → 分块 → 向量化入库
     *
     * @param file 上传的文件
     * @return 文档元数据记录
     */
    @Transactional
    public KbDocument uploadAndProcess(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        log.info("[DocumentService] 开始处理文档: {}", filename);

        // 1. 校验文件类型
        if (!parserFactory.isSupported(filename)) {
            throw new IllegalArgumentException("不支持的文件类型: " + filename);
        }

        // 2. 计算文件 Hash，去重检查
        byte[] fileBytes = file.getBytes();
        String fileHash = sha256Hex(fileBytes);
        if (documentMetaRepository.findByFileHash(fileHash).isPresent()) {
            log.warn("[DocumentService] 文件已存在，跳过重复入库: {}", filename);
            return documentMetaRepository.findByFileHash(fileHash).get();
        }

        // 3. 保存元数据记录（初始状态 PROCESSING）
        String fileType = getFileExtension(filename);
        KbDocument doc = KbDocument.builder()
                .filename(filename)
                .fileHash(fileHash)
                .fileSize(file.getSize())
                .fileType(fileType)
                .status(KbDocument.DocumentStatus.PROCESSING)
                .build();
        doc = documentMetaRepository.save(doc);

        try {
            // 4. 解析文档为纯文本
            DocumentParser parser = parserFactory.getParser(filename);
            String rawText;
            try (InputStream is = file.getInputStream()) {
                rawText = parser.parse(is, filename);
            }

            if (rawText == null || rawText.isBlank()) {
                throw new IllegalStateException("文档内容为空: " + filename);
            }

            // 5. 文本切块
            List<Document> chunks = splitIntoChunks(rawText, filename, doc.getId());
            log.info("[DocumentService] 文档 {} 切块完成: {} 个 chunk", filename, chunks.size());

            // 6. 向量化并存入 PGVector（Spring AI 自动调用 EmbeddingModel）
            vectorStore.add(chunks);
            log.info("[DocumentService] 向量入库完成: {}", filename);

            // 7. 更新元数据状态为 DONE
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc = documentMetaRepository.save(doc);

        } catch (Exception e) {
            log.error("[DocumentService] 文档处理失败: {}", filename, e);
            doc.setStatus(KbDocument.DocumentStatus.ERROR);
            doc.setErrorMsg(e.getMessage());
            documentMetaRepository.save(doc);
            throw e;
        }

        // 8. 清空向量缓存（新文档入库后旧缓存失效）
        ragService.invalidateCache();

        return doc;
    }

    /**
     * 查询所有已上传文档
     */
    public List<KbDocument> listDocuments() {
        return documentMetaRepository.findAll();
    }

    /**
     * 删除文档（元数据 + 向量数据）
     */
    @Transactional
    public void deleteDocument(UUID documentId) {
        KbDocument doc = documentMetaRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));

        // 删除 VectorStore 中对应的向量（按 metadata.source 过滤）
        vectorStore.delete(List.of(documentId.toString()));

        // 删除元数据
        documentMetaRepository.delete(doc);

        // 清空向量缓存
        ragService.invalidateCache();

        log.info("[DocumentService] 文档已删除: {}", doc.getFilename());
    }

    // ─── 私有方法 ─────────────────────────────────────────────────────────────

    /**
     * 将纯文本按 Token 切块，并附加 metadata
     */
    private List<Document> splitIntoChunks(String text, String filename, UUID docId) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();

        // 构造 Spring AI Document
        Document sourceDoc = new Document(text, Map.of(
                "source",    docId.toString(),
                "filename",  filename,
                "file_type", getFileExtension(filename)
        ));

        List<Document> chunks = splitter.apply(List.of(sourceDoc));

        // 为每个 chunk 注入 chunk_index
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = new java.util.HashMap<>(chunk.getMetadata());
            meta.put("chunk_index", i);
            meta.put("total_chunks", chunks.size());
            result.add(new Document(chunk.getText(), meta));
        }
        return result;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "unknown";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
