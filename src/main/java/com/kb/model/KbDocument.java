package com.kb.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档元数据实体
 * <p>
 * 存储上传文档的基本信息和处理状态，与 vector_store 表通过 metadata.source 关联。
 */
@Entity
@Table(name = "kb_document")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocument {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** 原始文件名 */
    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    /** 文件内容 SHA-256，用于去重 */
    @Column(name = "file_hash", nullable = false, unique = true, length = 64)
    private String fileHash;

    /** 文件大小（字节） */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 文件类型：pdf / docx / txt / md */
    @Column(name = "file_type", nullable = false, length = 32)
    private String fileType;

    /** 切块数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 处理状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private DocumentStatus status;

    /** 错误信息（仅 status=ERROR 时） */
    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = DocumentStatus.PENDING;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum DocumentStatus {
        PENDING, PROCESSING, DONE, ERROR
    }
}
