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
 * 支持三种录入来源：FILE（文件上传）、URL（网页抓取）、RPC（接口直接提交）。
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

    /** 原始文件名（URL 录入时为页面标题，RPC 录入时为 title 字段） */
    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    /** 文件内容 SHA-256，用于去重 */
    @Column(name = "file_hash", nullable = false, unique = true, length = 64)
    private String fileHash;

    /** 文件大小（字节；URL/RPC 录入时为文本字节数） */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 文件类型：pdf / docx / txt / md / url / rpc */
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

    /**
     * 录入来源类型：FILE / URL / RPC
     * <p>Hibernate ddl-auto=update 会自动添加该列（默认值 'FILE'）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 16, columnDefinition = "VARCHAR(16) DEFAULT 'FILE'")
    @Builder.Default
    private SourceType sourceType = SourceType.FILE;

    /**
     * 原始 URL（仅 URL 录入时记录）
     */
    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    /**
     * 来源系统标识（仅 RPC 录入时记录，如 "crm-system"、"ops-platform"）
     */
    @Column(name = "source_system", length = 128)
    private String sourceSystem;

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
        if (this.sourceType == null) {
            this.sourceType = SourceType.FILE;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum DocumentStatus {
        PENDING, PROCESSING, DONE, ERROR
    }

    /** 录入来源类型 */
    public enum SourceType {
        /** 传统文件上传（multipart） */
        FILE,
        /** 通过 URL 抓取网页内容 */
        URL,
        /** 通过 RPC 接口直接提交文本 */
        RPC
    }
}
