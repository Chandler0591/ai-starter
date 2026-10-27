package com.aistarter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 示例业务表 orders 的实体：AI 工具调用（Tool Calling）据此读取真实业务数据，替换为自己系统的表即可。
 * 命名为 CustomerOrder 是为了避免与 Spring 的 @Order 注解同名冲突。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外订单号（业务唯一键），模型可以用它查详情 */
    @Column(name = "order_no", nullable = false, unique = true)
    private String orderNo;

    /** 下单人；工具查询必须带上这个条件，是数据级权限的落点 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String product;

    /** 金额用 BigDecimal（库内 numeric(12,2)），不用 double，避免精度与四舍五入问题 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 16)
    private String status;

    /** 下单时间（建实体时取 now），工具输出里只取它的日期部分 */
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
