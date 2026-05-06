package com.kb.service;

import com.kb.model.KbDocument;
import com.kb.parser.DocumentParser;
import com.kb.parser.ParserFactory;
import com.kb.parser.UrlFetcher;
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
 * <p>
 * 支持三种录入方式：
 * <ul>
 *   <li>{@link #uploadAndProcess} — 传统文件上传（multipart）</li>
 *   <li>{@link #ingestFromUrl} — URL 抓取（HTTP GET + HTML 正文提取）</li>
 *   <li>{@link #ingestFromRpc} — RPC 接口录入（外部系统直接推送文本内容）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final UrlFetcher urlFetcher;
    private final ParserFactory parserFactory;
    private final VectorStore vectorStore;
    private final DocumentMetaRepository documentMetaRepository;
    private final RagService ragService;

    @Value("${kb.document.chunk-size:800}")
    private int chunkSize;

    @Value("${kb.document.chunk-overlap:200}")
    private int chunkOverlap;

    // ─── 公开方法：三种录入方式 ────────────────────────────────────────────────

    /**
     * 【URL 录入】通过 HTTP 抓取目标 URL 的内容并入库
     * <p>
     * 流程：HTTP GET 抓取 → Jsoup HTML 正文提取 → SHA-256 去重 → 切块 → 向量化入库
     *
     * @param url            目标 URL（支持 http/https）
     * @param timeoutSeconds HTTP 请求超时（秒）
     * @return 文档元数据记录
     */
    @Transactional
    public KbDocument ingestFromUrl(String url, int timeoutSeconds) throws Exception {
        log.info("[DocumentService] URL 录入开始: {}", url);

        // 1. 抓取 URL 内容
        UrlFetcher.FetchResult fetchResult = urlFetcher.fetch(url, timeoutSeconds);
        String rawText = fetchResult.content();
        String title = fetchResult.title();

        if (rawText == null || rawText.isBlank()) {
            throw new IllegalStateException("URL 内容为空或无法提取正文: " + url);
        }

        // 2. SHA-256 去重（对内容哈希，允许同一 URL 内容更新后重新录入）
        byte[] contentBytes = rawText.getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256Hex(contentBytes);
        if (documentMetaRepository.findByFileHash(fileHash).isPresent()) {
            log.warn("[DocumentService] URL 内容已存在（内容未变化），跳过重复入库: {}", url);
            return documentMetaRepository.findByFileHash(fileHash).get();
        }

        // 3. 保存元数据（PROCESSING 状态）
        KbDocument doc = KbDocument.builder()
                .filename(title.length() > 512 ? title.substring(0, 512) : title)
                .fileHash(fileHash)
                .fileSize((long) contentBytes.length)
                .fileType("url")
                .status(KbDocument.DocumentStatus.PROCESSING)
                .sourceType(KbDocument.SourceType.URL)
                .sourceUrl(url.length() > 2048 ? url.substring(0, 2048) : url)
                .build();
        doc = documentMetaRepository.save(doc);

        try {
            // 4. 切块
            List<Document> chunks = splitIntoChunks(rawText, title, doc.getId());
            log.info("[DocumentService] URL 录入切块完成: {} 个 chunk（URL: {}）", chunks.size(), url);

            // 5. 向量化入库
            vectorStore.add(chunks);
            log.info("[DocumentService] URL 向量入库完成: {}", url);

            // 6. 更新元数据为 DONE
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc = documentMetaRepository.save(doc);

        } catch (Exception e) {
            log.error("[DocumentService] URL 录入失败: {}", url, e);
            doc.setStatus(KbDocument.DocumentStatus.ERROR);
            doc.setErrorMsg(e.getMessage());
            documentMetaRepository.save(doc);
            throw e;
        }

        // 7. 清空向量缓存
        ragService.invalidateCache();

        log.info("[DocumentService] URL 录入完成: title='{}', chunks={}", title, doc.getChunkCount());
        return doc;
    }

    /**
     * 【RPC 录入】接收外部系统通过接口推送的文本内容并入库
     * <p>
     * 流程：接收文本 → SHA-256 去重 → 切块 → 向量化入库
     * <p>
     * 适用场景：CRM/ERP/OA 等内部系统实时推送文档、数据管道批量同步、自动化脚本调用。
     *
     * @param title        文档标题（作为 filename 存储）
     * @param content      文档正文内容（UTF-8 文本）
     * @param sourceSystem 来源系统标识（可选，如 "crm-system"）
     * @return 文档元数据记录
     */
    @Transactional
    public KbDocument ingestFromRpc(String title, String content, String sourceSystem) throws Exception {
        log.info("[DocumentService] RPC 录入开始: title='{}', system='{}'", title, sourceSystem);

        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("RPC 录入：content 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("RPC 录入：title 不能为空");
        }

        // 1. SHA-256 去重（基于内容哈希）
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256Hex(contentBytes);
        if (documentMetaRepository.findByFileHash(fileHash).isPresent()) {
            log.warn("[DocumentService] RPC 内容已存在，跳过重复入库: title='{}'", title);
            return documentMetaRepository.findByFileHash(fileHash).get();
        }

        // 2. 保存元数据（PROCESSING 状态）
        String safeTitle = title.length() > 512 ? title.substring(0, 512) : title;
        KbDocument doc = KbDocument.builder()
                .filename(safeTitle)
                .fileHash(fileHash)
                .fileSize((long) contentBytes.length)
                .fileType("rpc")
                .status(KbDocument.DocumentStatus.PROCESSING)
                .sourceType(KbDocument.SourceType.RPC)
                .sourceSystem(sourceSystem)
                .build();
        doc = documentMetaRepository.save(doc);

        try {
            // 3. 切块
            List<Document> chunks = splitIntoChunks(content, safeTitle, doc.getId());
            log.info("[DocumentService] RPC 录入切块完成: {} 个 chunk（title='{}'）", chunks.size(), title);

            // 4. 向量化入库
            vectorStore.add(chunks);
            log.info("[DocumentService] RPC 向量入库完成: title='{}'", title);

            // 5. 更新元数据为 DONE
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc = documentMetaRepository.save(doc);

        } catch (Exception e) {
            log.error("[DocumentService] RPC 录入失败: title='{}'", title, e);
            doc.setStatus(KbDocument.DocumentStatus.ERROR);
            doc.setErrorMsg(e.getMessage());
            documentMetaRepository.save(doc);
            throw e;
        }

        // 6. 清空向量缓存
        ragService.invalidateCache();

        log.info("[DocumentService] RPC 录入完成: title='{}', chunks={}", title, doc.getChunkCount());
        return doc;
    }

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
     * 【URL 录入 → SOP 库】抓取 URL 内容并存入 sopVectorStore
     */
    @Transactional
    public KbDocument ingestFromUrlToSop(String url, int timeoutSeconds, SopRagService sopRagService) throws Exception {
        log.info("[DocumentService] URL→SOP 录入开始: {}", url);

        UrlFetcher.FetchResult fetchResult = urlFetcher.fetch(url, timeoutSeconds);
        String rawText = fetchResult.content();
        String title = fetchResult.title();

        if (rawText == null || rawText.isBlank()) {
            throw new IllegalStateException("URL 内容为空或无法提取正文: " + url);
        }

        byte[] contentBytes = rawText.getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256Hex(contentBytes);
        if (documentMetaRepository.findByFileHash(fileHash).isPresent()) {
            log.warn("[DocumentService] URL→SOP 内容已存在，跳过: {}", url);
            return documentMetaRepository.findByFileHash(fileHash).get();
        }

        KbDocument doc = KbDocument.builder()
                .filename(title.length() > 512 ? title.substring(0, 512) : title)
                .fileHash(fileHash)
                .fileSize((long) contentBytes.length)
                .fileType("sop-url")
                .status(KbDocument.DocumentStatus.PROCESSING)
                .sourceType(KbDocument.SourceType.URL)
                .sourceUrl(url.length() > 2048 ? url.substring(0, 2048) : url)
                .build();
        doc = documentMetaRepository.save(doc);

        try {
            List<Document> chunks = splitIntoChunks(rawText, title, doc.getId());
            sopRagService.addDocuments(chunks);
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc = documentMetaRepository.save(doc);
        } catch (Exception e) {
            log.error("[DocumentService] URL→SOP 录入失败: {}", url, e);
            doc.setStatus(KbDocument.DocumentStatus.ERROR);
            doc.setErrorMsg(e.getMessage());
            documentMetaRepository.save(doc);
            throw e;
        }
        log.info("[DocumentService] URL→SOP 录入完成: title='{}', chunks={}", title, doc.getChunkCount());
        return doc;
    }

    /**
     * 【RPC 录入 → SOP 库】接收外部推送文本并存入 sopVectorStore
     */
    @Transactional
    public KbDocument ingestFromRpcToSop(String title, String content, String sourceSystem,
                                          SopRagService sopRagService) throws Exception {
        log.info("[DocumentService] RPC→SOP 录入开始: title='{}'", title);

        if (content == null || content.isBlank()) throw new IllegalArgumentException("content 不能为空");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title 不能为空");

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String fileHash = sha256Hex(contentBytes);
        if (documentMetaRepository.findByFileHash(fileHash).isPresent()) {
            log.warn("[DocumentService] RPC→SOP 内容已存在，跳过: title='{}'", title);
            return documentMetaRepository.findByFileHash(fileHash).get();
        }

        String safeTitle = title.length() > 512 ? title.substring(0, 512) : title;
        KbDocument doc = KbDocument.builder()
                .filename(safeTitle)
                .fileHash(fileHash)
                .fileSize((long) contentBytes.length)
                .fileType("sop-rpc")
                .status(KbDocument.DocumentStatus.PROCESSING)
                .sourceType(KbDocument.SourceType.RPC)
                .sourceSystem(sourceSystem)
                .build();
        doc = documentMetaRepository.save(doc);

        try {
            List<Document> chunks = splitIntoChunks(content, safeTitle, doc.getId());
            sopRagService.addDocuments(chunks);
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc = documentMetaRepository.save(doc);
        } catch (Exception e) {
            log.error("[DocumentService] RPC→SOP 录入失败: title='{}'", title, e);
            doc.setStatus(KbDocument.DocumentStatus.ERROR);
            doc.setErrorMsg(e.getMessage());
            documentMetaRepository.save(doc);
            throw e;
        }
        log.info("[DocumentService] RPC→SOP 录入完成: title='{}', chunks={}", title, doc.getChunkCount());
        return doc;
    }

    /**
     * 上传并处理 SOP 文档（入库到 sopVectorStore）
     * <p>
     * 复用相同的文件解析和分块逻辑，但将向量写入 sopVectorStore（而非默认的 docVectorStore）。
     * 元数据记录仍存到 kb_document 表，status 字段用于状态追踪。
     *
     * @param file          上传的 SOP 文档文件
     * @param sopRagService SopRagService，负责向 sopVectorStore 添加文档
     * @return 文档元数据记录
     */
    @Transactional
    public KbDocument uploadAndProcessToSop(MultipartFile file, SopRagService sopRagService) throws Exception {
        String filename = file.getOriginalFilename();
        log.info("[DocumentService] 开始处理 SOP 文档: {}", filename);

        // 1. 校验文件类型
        if (!parserFactory.isSupported(filename)) {
            throw new IllegalArgumentException("不支持的文件类型: " + filename);
        }

        // 2. 计算文件 Hash，去重检查
        byte[] fileBytes = file.getBytes();
        String fileHash = sha256Hex(fileBytes);
        if (documentMetaRepository.findByFileHash(fileHash).isPresent()) {
            log.warn("[DocumentService] SOP 文件已存在，跳过重复入库: {}", filename);
            return documentMetaRepository.findByFileHash(fileHash).get();
        }

        // 3. 保存元数据记录（标记 file_type 为 SOP 类型）
        String fileType = getFileExtension(filename);
        KbDocument doc = KbDocument.builder()
                .filename(filename)
                .fileHash(fileHash)
                .fileSize(file.getSize())
                .fileType("sop-" + fileType)   // 标记为 SOP 文档
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
                throw new IllegalStateException("SOP 文档内容为空: " + filename);
            }

            // 5. 文本切块（使用相同的 TokenTextSplitter 逻辑）
            List<Document> chunks = splitIntoChunks(rawText, filename, doc.getId());
            log.info("[DocumentService] SOP 文档 {} 切块完成: {} 个 chunk", filename, chunks.size());

            // 6. 向量化并存入 sopVectorStore（通过 SopRagService）
            sopRagService.addDocuments(chunks);
            log.info("[DocumentService] SOP 向量入库完成: {}", filename);

            // 7. 更新元数据状态为 DONE
            doc.setChunkCount(chunks.size());
            doc.setStatus(KbDocument.DocumentStatus.DONE);
            doc = documentMetaRepository.save(doc);

        } catch (Exception e) {
            log.error("[DocumentService] SOP 文档处理失败: {}", filename, e);
            doc.setStatus(KbDocument.DocumentStatus.ERROR);
            doc.setErrorMsg(e.getMessage());
            documentMetaRepository.save(doc);
            throw e;
        }

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
