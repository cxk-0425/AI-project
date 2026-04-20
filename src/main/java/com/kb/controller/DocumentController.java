package com.kb.controller;

import com.kb.model.KbDocument;
import com.kb.service.DocumentService;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传并处理文档
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
