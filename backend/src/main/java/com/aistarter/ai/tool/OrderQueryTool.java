package com.aistarter.ai.tool;

import com.aistarter.domain.CustomerOrder;
import com.aistarter.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 业务工具：把「真实业务系统的数据查询能力」暴露给模型。
 * 权限要点——userId 来自 ToolContext（服务端注入），而不是模型/前端传参，
 * 因此模型无法越权查询他人订单；管理员额外可按用户名查。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryTool {

    /** ToolContext 的键：当前用户 id 与是否管理员，由 ToolRegistry/ChatService 服务端写入 */
    public static final String CTX_USER_ID = "userId";
    public static final String CTX_ADMIN = "admin";

    private final OrderRepository orderRepository;

    /**
     * 查当前用户最近 5 笔订单。只按服务端注入的 userId 查，不接受模型给的 id。
     *
     * @param toolContext 工具上下文，必含 userId
     * @return 可直接注入 Prompt 的文本结果
     */
    @Tool(name = "queryMyRecentOrders", description = "查询当前登录用户最近的订单（订单号、商品、金额、状态）。用户询问自己的订单/物流/花费时使用。")
    public String queryMyRecentOrders(ToolContext toolContext) {
        Long userId = requireUserId(toolContext);
        List<CustomerOrder> orders = orderRepository.findTop5ByUserIdOrderByIdDesc(userId);
        return format(orders, "用户 " + userId + " 最近订单");
    }

    /**
     * 按订单号查详情：普通用户只能命中自己的单；ADMIN 可跨用户查（取不到则回答“未找到”，不区分存在与否避免探测）。
     *
     * @param orderNo     订单号（由模型从用户问题里抽取）
     * @param toolContext 工具上下文，含 userId 与 admin 标记
     */
    @Tool(name = "queryOrderDetail", description = "按订单号查询订单详情；只能查到属于当前用户的订单。")
    public String queryOrderDetail(@ToolParam(description = "订单号，例如 SO-2026-0001") String orderNo,
                                   ToolContext toolContext) {
        Long userId = requireUserId(toolContext);
        boolean admin = toolContext != null && Boolean.TRUE.equals(toolContext.getContext().get(CTX_ADMIN));
        List<CustomerOrder> orders = admin
                ? orderRepository.findAll().stream().filter(o -> o.getOrderNo().equals(orderNo)).toList()
                : orderRepository.findByUserIdAndOrderNo(userId, orderNo);
        if (orders.isEmpty()) {
            return "未找到订单 " + orderNo + "（或该订单不属于当前用户）";
        }
        return format(orders, "订单 " + orderNo);
    }

    /** 取服务端注入的 userId；缺失即报错，宁可不回答也不能无主地查全量数据 */
    private Long requireUserId(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(CTX_USER_ID);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("工具调用缺少用户上下文");
        }
        return number.longValue();
    }

    /** 把订单列表渲染成给模型读的文本（带表头，空结果也明说） */
    private String format(List<CustomerOrder> orders, String header) {
        if (orders.isEmpty()) {
            return header + "：无记录";
        }
        StringBuilder sb = new StringBuilder(header).append("：\n");
        for (CustomerOrder o : orders) {
            sb.append(Map.of("orderNo", o.getOrderNo(), "product", o.getProduct(),
                    "amount", o.getAmount().toPlainString(), "status", o.getStatus(),
                    "createdAt", o.getCreatedAt().toLocalDate().toString())).append('\n');
        }
        return sb.toString();
    }
}
