package com.kb.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RPC 录入请求 DTO
 * <p>
 * 调用 POST /api/documents/ingest/rpc 时的请求体。
 * 外部服务直接提交文本内容，无需上传文件，模拟 RPC 调用语义入库。
 *
 * <h3>使用示例（curl）</h3>
 * <pre>{@code
 * curl -X POST http://localhost:8080/api/documents/ingest/rpc \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "title": "2024年Q4营销策略文档",
 *     "content": "本季度营销策略重点聚焦在...",
 *     "sourceSystem": "crm-system",
 *     "storeType": "DOC"
 *   }'
 * }</pre>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>CRM、ERP、OA 等内部系统实时推送文档内容</li>
 *   <li>数据管道（ETL）批量同步文档</li>
 *   <li>自动化脚本（如 shell/Python）直接提交文本入库</li>
 * </ul>
 */
@Data
public class RpcIngestRequest {

    /**
     * 文档标题（必填）
     * <p>作为 kb_document.filename 存储，用于检索结果来源展示。
     */
    @NotBlank(message = "title 不能为空")
    private String title;

    /**
     * 文档正文内容（必填）
     * <p>UTF-8 文本，系统将直接对其进行切块和向量化，无需任何格式解析。
     */
    @NotBlank(message = "content 不能为空")
    private String content;

    /**
     * 来源系统标识（可选）
     * <p>例如 "crm-system"、"ops-platform"、"data-pipeline"，用于追溯录入来源。
     */
    private String sourceSystem;

    /**
     * 存储目标：DOC（默认，营销文档库）/ SOP（SOP 活动库）
     */
    private String storeType = "DOC";
}
