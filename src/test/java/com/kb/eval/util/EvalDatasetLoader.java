package com.kb.eval.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 评测数据集加载工具
 * <p>
 * 从 src/test/resources/eval/ 目录加载 JSON 格式的评测数据集。
 */
public class EvalDatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 classpath 加载 JSON 数组格式的评测数据集
     *
     * @param resourcePath 资源路径（相对于 classpath），如 "eval/intent_dataset.json"
     * @param clazz        元素类型
     * @param <T>          元素泛型
     * @return 数据集列表
     */
    public static <T> List<T> loadList(String resourcePath, Class<T> clazz) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return MAPPER.readValue(is,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, clazz));
        }
    }

    /**
     * 从 classpath 加载单个 JSON 对象
     *
     * @param resourcePath 资源路径
     * @param clazz        目标类型
     */
    public static <T> T loadObject(String resourcePath, Class<T> clazz) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream is = resource.getInputStream()) {
            return MAPPER.readValue(is, clazz);
        }
    }
}
