package com.kb.repository;

import com.kb.model.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 文档元数据 Repository
 */
@Repository
public interface DocumentMetaRepository extends JpaRepository<KbDocument, UUID> {

    /** 按文件 Hash 查询（用于去重） */
    Optional<KbDocument> findByFileHash(String fileHash);

    /** 按文件名查询 */
    Optional<KbDocument> findByFilename(String filename);

    /** 按状态查询 */
    java.util.List<KbDocument> findByStatus(KbDocument.DocumentStatus status);
}
