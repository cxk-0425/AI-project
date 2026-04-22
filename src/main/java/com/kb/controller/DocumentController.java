package com.kb.controller;

import com.kb.model.KbDocument;
import com.kb.service.DocumentService;
import com.kb.service.SopRagService;
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
 * 提供文档上传、列表查询、删除等 REST 接口。
 * <p>
 * 两类文档库：
 * <ul>
 *   <li>POST /api/documents/upload      - 上传到电商营销内部文档库（docVectorStore，用于 QUERY 路径）</li>
 *   <li>POST /api/documents/upload/sop  - 上传到营销 QA + SOP 文档库（sopVectorStore，用于 CONFIG 路径）</li>
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
}
