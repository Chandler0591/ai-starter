package com.aistarter.dto;

import java.time.OffsetDateTime;

/**
 * 会话与消息的对外视图（不回传 user_id 等内部字段）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public final class SessionDtos {

    /** 仅作为内嵌 record 的命名空间容器，禁止实例化 */
    private SessionDtos() {
    }

    /**
     * 会话列表项。
     *
     * @param id        会话 id，下轮对话带回即可续用
     * @param title     标题，取首问前 40 字
     * @param promptKey 该会话建立时用的系统提示词名
     * @param promptVer 请求里带的版本原值（v1/v2/latest）；模板解析在每次对话时进行
     */
    public record SessionView(Long id, String title, String promptKey, String promptVer, OffsetDateTime createdAt) {
    }

    /**
     * 一条历史消息。
     *
     * @param role    user / assistant（system 不对外返回）
     * @param traceId 该条回答所属链路，便于从页面直接查到日志
     */
    public record MessageView(Long id, String role, String content, String traceId, OffsetDateTime createdAt) {
    }
}
