package com.kb.intent;

/**
 * 用户意图类型枚举
 * <p>
 * QUERY  - 查询类：用户想查看、搜索、了解信息（走电商内部文档 RAG）
 * CONFIG - 配置类：用户想创建、修改、配置操作（走 SOP RAG + Agent 链）
 * UNKNOWN - 无法确定意图，默认降级到 QUERY
 */
public enum IntentType {

    /** 查询类：如"查看活动列表"、"什么是满减活动"、"当前配置是什么" */
    QUERY,

    /** 配置类：如"创建一个双十一活动"、"帮我配置优惠券"、"修改活动时间" */
    CONFIG,

    /** 未知意图：置信度过低，默认降级为 QUERY 处理 */
    UNKNOWN
}
