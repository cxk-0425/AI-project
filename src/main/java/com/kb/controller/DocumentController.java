package com.kb.controller;

import com.kb.model.KbDocument;
import com.kb.model.RpcIngestRequest;
import com.kb.model.UrlIngestRequest;
import com.kb.service.DocumentService;
import com.kb.service.SopRagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档管理 Controller
 * <p>
 * 提供文档上传、URL 抓取、RPC 录入、列表查询、删除等 REST 接口。
 * <p>
 * 三种录入方式：
 * <ul>
 *   <li>POST /api/documents/upload         - 文件上传（multipart）到营销文档库（QUERY 路径）</li>
 *   <li>POST /api/documents/upload/sop     - 文件上传（multipart）到 SOP 文档库（CONFIG 路径）</li>
 *   <li>POST /api/documents/ingest/url     - URL 抓取录入（HTTP GET + HTML 正文提取）</li>
 *   <li>POST /api/documents/ingest/rpc     - RPC 接口录入（外部系统直接推送文本）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final DocumentService documentService;
    private final SopRagService sopRagService;

    // ─── 新录入方式 ───────────────────────────────────────────────────────────

    /**
     * 【URL 录入】通过 HTTP 抓取目标 URL 的内容入库
     * POST /api/documents/ingest/url
     *
     * <h3>请求示例（curl）</h3>
     * <pre>{@code
     * curl -X POST http://localhost:8080/api/documents/ingest/url \
     *   -H "Content-Type: application/json" \
     *   -d '{"url":"https://example.com/article","storeType":"DOC","timeoutSeconds":15}'
     * }</pre>
     */
    @PostMapping("/ingest/url")
    public ResponseEntity<?> ingestFromUrl(@Valid @RequestBody UrlIngestRequest req) {
        try {
            int timeout = req.getTimeoutSeconds() != null ? req.getTimeoutSeconds() : 15;
            KbDocument doc;
            if ("SOP".equalsIgnoreCase(req.getStoreType())) {
                // URL 录入到 SOP 知识库（通过 sopRagService 写入 sop_vector_store）
                doc = documentService.ingestFromUrlToSop(req.getUrl(), timeout, sopRagService);
            } else {
                doc = documentService.ingestFromUrl(req.getUrl(), timeout);
            }
            return ResponseEntity.ok(buildDocumentResponse(doc, "URL 录入成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[DocumentController] URL 录入失败: {}", req.getUrl(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "URL 录入失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 【RPC 录入】外部系统通过接口直接推送文本内容入库
     * POST /api/documents/ingest/rpc
     *
     * <h3>请求示例（curl）</h3>
     * <pre>{@code
     * curl -X POST http://localhost:8080/api/documents/ingest/rpc \
     *   -H "Content-Type: application/json" \
     *   -d '{"title":"2024年Q4营销策略","content":"本季度重点聚焦...","sourceSystem":"crm"}'
     * }</pre>
     */
    @PostMapping("/ingest/rpc")
    public ResponseEntity<?> ingestFromRpc(@Valid @RequestBody RpcIngestRequest req) {
        try {
            KbDocument doc;
            if ("SOP".equalsIgnoreCase(req.getStoreType())) {
                doc = documentService.ingestFromRpcToSop(
                        req.getTitle(), req.getContent(), req.getSourceSystem(), sopRagService);
            } else {
                doc = documentService.ingestFromRpc(
                        req.getTitle(), req.getContent(), req.getSourceSystem());
            }
            return ResponseEntity.ok(buildDocumentResponse(doc, "RPC 录入成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[DocumentController] RPC 录入失败: title={}", req.getTitle(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "RPC 录入失败: " + e.getMessage()
            ));
        }
    }

    // ─── 传统上传接口 ─────────────────────────────────────────────────────────

    /**
     * 上传并处理文档（入库到电商营销内部文档库，供 QUERY 路径使用）
     * POST /api/documents/upload
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "文件不能为空"
            ));
        }
        try {
            KbDocument doc = documentService.uploadAndProcess(file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "文档处理成功",
                    "document", Map.of(
                            "id",          doc.getId(),
                            "filename",    doc.getFilename(),
                            "fileType",    doc.getFileType(),
                            "fileSize",    doc.getFileSize(),
                            "chunkCount",  doc.getChunkCount(),
                            "status",      doc.getStatus()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[DocumentController] 文档上传失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "文档处理失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 上传 SOP / QA 文档（入库到 sopVectorStore，供 CONFIG 路径的 Agent 链使用）
     * POST /api/documents/upload/sop
     * <p>
     * 上传营销活动操作 SOP、QA 问答对等文档，由 SopRagService 检索后提供给 PlannerAgent 使用。
     * 文件解析仍使用 DocumentService（支持 PDF/DOCX/TXT/MD），但向量化入库走 sopVectorStore。
     */
    @PostMapping("/upload/sop")
    public ResponseEntity<?> uploadSopDocument(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "文件不能为空"
            ));
        }
        try {
            // 复用 DocumentService 解析文件内容并入库到 SOP VectorStore
            KbDocument doc = documentService.uploadAndProcessToSop(file, sopRagService);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "SOP 文档处理成功，已入库到活动 SOP 知识库",
                    "document", Map.of(
                            "id",         doc.getId(),
                            "filename",   doc.getFilename(),
                            "fileType",   doc.getFileType(),
                            "fileSize",   doc.getFileSize(),
                            "chunkCount", doc.getChunkCount(),
                            "status",     doc.getStatus(),
                            "storeType",  "SOP"
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[DocumentController] SOP 文档上传失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "SOP 文档处理失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询文档列表
     * GET /api/documents
     */
    @GetMapping
    public ResponseEntity<List<KbDocument>> listDocuments() {
        return ResponseEntity.ok(documentService.listDocuments());
    }

    /**
     * 删除文档
     * DELETE /api/documents/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteDocument(@PathVariable UUID id) {
        try {
            documentService.deleteDocument(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "文档已删除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("[DocumentController] 删除文档失败: {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "删除失败: " + e.getMessage()
            ));
        }
    }

    // ─── 私有辅助方法 ─────────────────────────────────────────────────────────

    /**
     * 构建统一的文档录入成功响应
     */
    private Map<String, Object> buildDocumentResponse(KbDocument doc, String message) {
        return Map.of(
                "success",  true,
                "message",  message,
                "document", Map.of(
                        "id",           doc.getId(),
                        "filename",     doc.getFilename(),
                        "fileType",     doc.getFileType(),
                        "fileSize",     doc.getFileSize(),
                        "chunkCount",   doc.getChunkCount(),
                        "status",       doc.getStatus(),
                        "sourceType",   doc.getSourceType(),
                        "sourceUrl",    doc.getSourceUrl() != null ? doc.getSourceUrl() : "",
                        "sourceSystem", doc.getSourceSystem() != null ? doc.getSourceSystem() : ""
                )
        );
    }
}
