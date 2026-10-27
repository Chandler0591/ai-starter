package com.aistarter.controller;

import com.aistarter.ai.LlmClient;
import com.aistarter.ai.PromptRegistry;
import com.aistarter.dto.DashboardView;
import com.aistarter.repository.KnowledgeDocRepository;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 运行看板：直接读 MeterRegistry 中所有 ai.* 指标，前端无需接 Prometheus。
 * 生产环境建议由 Prometheus + Grafana 承担趋势图，这里保留最小可用的即时视图。
 * 接口仅 ADMIN（/api/dashboard/** 在 SecurityConfig 里收口）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    /** 只暴露本项目自定义指标，避免把 JVM 指标全量扫给前端 */
    private static final String AI_METRIC_PREFIX = "ai.";

    private final MeterRegistry registry;
    private final LlmClient llmClient;
    private final PromptRegistry promptRegistry;
    private final KnowledgeDocRepository knowledgeDocRepository;

    /**
     * 看板数据：provider + Prompt 目录 + 知识文档数 + ai.* 指标行。
     * 指标按名称与 tag 排序，保证多次刷新时展示顺序稳定。
     */
    @GetMapping("/metrics")
    public DashboardView metrics() {
        List<DashboardView.MetricRow> rows = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith(AI_METRIC_PREFIX))
                .map(this::toRow)
                .sorted(Comparator.comparing(DashboardView.MetricRow::name)
                        .thenComparing(row -> row.tags().toString()))
                .toList();

        return new DashboardView(llmClient.provider(), promptRegistry.catalog(),
                knowledgeDocRepository.count(), rows);
    }

    /** 把单个 Meter 拍平成前端可直接渲染的行：统计值按 statistic（count/totalAmount/max/VALUE 等）入 map */
    private DashboardView.MetricRow toRow(Meter meter) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Measurement measurement : meter.measure()) {
            values.put(measurement.getStatistic().getTagValueRepresentation(), measurement.getValue());
        }
        Map<String, String> tags = meter.getId().getTags().stream()
                .collect(Collectors.toMap(Tag::getKey, Tag::getValue, (a, b) -> a, LinkedHashMap::new));
        return new DashboardView.MetricRow(meter.getId().getName(), tags, values);
    }
}
