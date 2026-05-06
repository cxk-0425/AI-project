package com.kb.eval.util;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 意图识别评测数据集的单条记录
 */
public record IntentTestCase(
        @JsonProperty("query") String query,
        @JsonProperty("expected") String expected
) {}
