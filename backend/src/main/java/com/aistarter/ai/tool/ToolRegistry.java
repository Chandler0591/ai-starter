package com.aistarter.ai.tool;

import com.aistarter.domain.Role;
import com.aistarter.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具注册中心，两件事：
 * 1) 工具级权限：按角色决定本轮对话暴露给模型的工具集合（最小权限原则）；
 * 2) 关键词路由的「前置调用」：不依赖模型是否主动发起 function call，
 *    命中业务意图时由服务端直接调用工具，把结果注入 Prompt。
 *    这让 Mock Provider 也能演示工具链路，同时给真实模型多一层兜底。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final OrderQueryTool orderQueryTool;
    private final KnowledgeSearchTool knowledgeSearchTool;

    /** 订单类意图关键词：命中才做前置调用，避免每轮都无谓打库 */
    private static final Pattern ORDER_INTENT =
            Pattern.compile("(订单|我的.{0,4}单|order|发货|退款|物流|花了多少|金额)", Pattern.CASE_INSENSITIVE);

    /** 一次前置工具调用的结果：工具名（用于指标 tag）+ 输出文本（注入 Prompt） */
    public record ToolOutcome(String tool, String output) {
    }

    /** 暴露给模型做原生 function calling 的工具对象 */
    public List<Object> toolsFor(Role role) {
        return role == Role.ROLE_ADMIN
                ? List.of(orderQueryTool, knowledgeSearchTool)
                : List.of(orderQueryTool);
    }

    /** 本轮暴露给模型的工具名清单（与 toolsFor 一一对应，用于回答溯源与调试） */
    public List<String> toolNames(Role role) {
        return role == Role.ROLE_ADMIN
                ? List.of("queryMyRecentOrders", "queryOrderDetail", "searchKnowledgeBase")
                : List.of("queryMyRecentOrders", "queryOrderDetail");
    }

    /**
     * 服务端前置调用：命中订单意图才执行，并把当前用户身份作为上下文传入。
     *
     * @param question  用户问题
     * @param principal 当前登录用户，未登录直接不调用
     * @return 调用结果列表（本期只有一个订单工具，故最多一条）
     */
    public List<ToolOutcome> preInvoke(String question, AuthPrincipal principal) {
        if (principal == null || !ORDER_INTENT.matcher(question).find()) {
            return List.of();
        }
        Map<String, Object> context = Map.of(
                OrderQueryTool.CTX_USER_ID, principal.userId(),
                OrderQueryTool.CTX_ADMIN, principal.isAdmin());
        String output = orderQueryTool.queryMyRecentOrders(new ToolContext(context));
        return List.of(new ToolOutcome("queryMyRecentOrders", output));
    }
}
