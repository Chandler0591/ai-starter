package com.aistarter.ai;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Prompt 版本化注册中心。命名约定：resources/prompts/{name}.v{n}.md
 * 版本随文件走 -> 可灰度发布（请求指定 promptVersion）、可回滚、可 A/B 对比。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Component
public class PromptRegistry {

    /** 文件名即版本：system-chat.v2.md -> name=system-chat, ver=2；不合规的文件名会被跳过并告警 */
    private static final Pattern FILE_PATTERN = Pattern.compile("^(?<name>.+)\\.v(?<ver>\\d+)\\.md$");

    /** name -> (version -> template) */
    private final Map<String, TreeMap<Integer, String>> store = new TreeMap<>();

    /**
     * 启动时扫描 classpath:prompts/*.md 并全量加载进内存。
     * 运行期不重读文件：修改模板需重新发布，版本变更因此可追溯、不会中途抖动。
     */
    @PostConstruct
    public void load() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath:prompts/*.md")) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            var matcher = FILE_PATTERN.matcher(filename);
            if (!matcher.matches()) {
                log.warn("Skip prompt file with unexpected name: {}", filename);
                continue;
            }
            String text;
            try (InputStream in = resource.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            store.computeIfAbsent(matcher.group("name"), k -> new TreeMap<>())
                    .put(Integer.parseInt(matcher.group("ver")), text);
            log.info("Loaded prompt: {} v{}", matcher.group("name"), matcher.group("ver"));
        }
    }

    /**
     * 取指定版本的模板正文。
     *
     * @param name    提示词名，如 system-chat / rag-answer
     * @param version v1 / v2 / latest；为空或 latest 时取最大版本
     * @return 模板正文
     * @throws IllegalArgumentException 提示词不存在或指定版本不存在
     */
    public String get(String name, String version) {
        TreeMap<Integer, String> versions = versions(name);
        if ("latest".equalsIgnoreCase(version) || version == null || version.isBlank()) {
            return versions.lastEntry().getValue();
        }
        Integer parsed = parseVersion(version);
        String template = versions.get(parsed);
        if (template == null) {
            throw new IllegalArgumentException("Prompt 版本不存在: " + name + "@" + version);
        }
        return template;
    }

    /** 所有已加载的提示词名 */
    public Set<String> names() {
        return store.keySet();
    }

    /** 某提示词的可用版本列表（升序），不存在则抛异常 */
    public List<String> versionNames(String name) {
        return versions(name).keySet().stream().map(v -> "v" + v).toList();
    }

    /** 取版本表，提示词不存在或无可用版本时抛 400 类异常 */
    private TreeMap<Integer, String> versions(String name) {
        TreeMap<Integer, String> versions = store.get(name);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("Prompt 不存在: " + name);
        }
        return versions;
    }

    /** 从字符串里提数字版本号（v2/2/V2 均可），无数字返回 null */
    private Integer parseVersion(String version) {
        String digits = version.replaceAll("\\D", "");
        return digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    /** 极简占位符渲染：{key} -> value。刻意不引入模板引擎，避免 JSON/大括号转义坑 */
    public static String render(String template, Map<String, String> vars) {
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    /** 供看板展示可用 Prompt 及版本 */
    public Map<String, List<String>> catalog() {
        Map<String, List<String>> catalog = new java.util.LinkedHashMap<>();
        store.forEach((name, versions) -> {
            List<String> list = new ArrayList<>(versions.keySet().stream()
                    .sorted(Comparator.reverseOrder())
                    .map(v -> "v" + v)
                    .toList());
            catalog.put(name, list);
        });
        return catalog;
    }
}
