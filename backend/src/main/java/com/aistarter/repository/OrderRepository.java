package com.aistarter.repository;

import com.aistarter.domain.CustomerOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 订单表访问。只给订单查询工具使用，因此每个查询都带上 userId，把数据权限压在 SQL 层。
 *
 * @author Flynn
 * @date 2026-10-27
 */
public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    /** 当前用户最近 5 笔（按 id 倒序），用于“我的订单”类提问 */
    List<CustomerOrder> findTop5ByUserIdOrderByIdDesc(Long userId);

    /** 按订单号精确定位：查不到与不属于自己都返回空列表，不向外区分存在与否 */
    List<CustomerOrder> findByUserIdAndOrderNo(Long userId, String orderNo);
}
