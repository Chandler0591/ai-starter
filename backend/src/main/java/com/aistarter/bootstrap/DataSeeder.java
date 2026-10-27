package com.aistarter.bootstrap;

import com.aistarter.ai.KnowledgeService;
import com.aistarter.domain.AppUser;
import com.aistarter.domain.CustomerOrder;
import com.aistarter.domain.Role;
import com.aistarter.dto.KnowledgeDtos;
import com.aistarter.repository.AppUserRepository;
import com.aistarter.repository.OrderRepository;
import com.aistarter.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 演示数据初始化：账号 + 示例订单 + 一份可命中的知识文档。
 * 密码用 PasswordEncoder 现场加密（不写死哈希），换环境即可复用。
 * <p>注意：本类无开关，每次启动都会补齐缺失的演示数据；生产环境请先改掉演示口令，或不要让本类上生产（见 docs/DEPLOYMENT.md 上线清单）。
 *
 * @author Flynn
 * @date 2026-10-27
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    /** 演示知识语料：含「年假 5 天」等可检索事实，评测用例 rag-hit 直接依赖它 */
    private static final String DEMO_DOC = """
            员工福利制度（演示文档）
            公司年假：入职满 1 年不满 10 年的员工，每年享有 5 天年假；满 10 年为 10 天。
            病假：需提供医院证明，每年最多 15 天。
            报销：差旅费在行程结束后 30 日内通过 OA 提交，公司财务部 5 个工作日内打款。
            """;

    private final AppUserRepository userRepository;
    private final OrderRepository orderRepository;
    private final KnowledgeService knowledgeService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 启动后执行，全部写入都是幂等的：已存在则不重复插。
     * 知识文档走 KnowledgeService.upload，因此会真实走完「切片 + 向量化」，保证启动后 RAG 就能命中。
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppUser admin = ensureUser("admin", "admin123", "管理员", Role.ROLE_ADMIN);
        AppUser user = ensureUser("user", "user123", "普通员工", Role.ROLE_USER);

        if (orderRepository.count() == 0) {
            orderRepository.saveAll(List.of(
                    newOrder("SO-2026-0001", user.getId(), "工业传感器 X200", "1280.00", "SHIPPED"),
                    newOrder("SO-2026-0002", user.getId(), "边缘网关 EG-50", "4680.00", "PAID"),
                    newOrder("SO-2026-0003", admin.getId(), "云端算力包 100h", "9800.00", "COMPLETED")));
            log.info("Seeded demo orders");
        }

        // 让 RAG 开箱即有可命中的语料，评测用例也依赖它
        if (knowledgeService.list().isEmpty()) {
            knowledgeService.upload(new KnowledgeDtos.UploadRequest("员工福利制度", DEMO_DOC, "MANUAL"),
                    new AuthPrincipal(admin.getId(), "admin", Role.ROLE_ADMIN));
            log.info("Seeded demo knowledge doc");
        }
    }

    /** 按用户名幂等建账号，已存在则直接复用 */
    private AppUser ensureUser(String username, String rawPassword, String displayName, Role role) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            AppUser user = new AppUser();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setDisplayName(displayName);
            user.setRole(role);
            AppUser saved = userRepository.save(user);
            log.info("Seeded user {} ({})", username, role);
            return saved;
        });
    }

    /** 构造一条未落库的订单；金额用字符串入参避免 double 精度问题 */
    private CustomerOrder newOrder(String orderNo, Long userId, String product, String amount, String status) {
        CustomerOrder order = new CustomerOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setProduct(product);
        order.setAmount(new BigDecimal(amount));
        order.setStatus(status);
        return order;
    }
}
