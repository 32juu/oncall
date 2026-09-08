package org.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 工具统一注册表（执行文档 §5.2「统一注册、权限校验」的落地 V1）。
 *
 * <p>职责：把散落的 {@link Tool} 方法 + {@link ToolLevel} 分级收敛成一张「工具名 → 危险级别」表，
 * 并提供按级别过滤 {@link ToolCallback} 的能力。当前唯一的策略消费方是 AIOps 槽位：
 * 它的 agent 只准拿 READ_ONLY 工具（认领/抑制=人工接管，AIOps 不得无人值守写状态）。
 *
 * <p>扫描方式：反射 {@code ApplicationContext.getBeansWithAnnotation(ToolLevel.class)} 拿到所有声明分级的工具 bean，
 * 对其类上每个 {@link Tool} 方法取名（注解 name 为空时取方法名，与 Spring AI 默认一致）。
 * <b>对 claim.tool 零编译依赖</b>（纯反射）——放本包不会造出 {@code agent.tool ↔ claim.*} 环。
 *
 * <p>边界：本切片只做"按 agent 槽位的可见性过滤"这一种权限校验；运行时角色/用户级鉴权归后续
 * 账号/RBAC 切片（spec Assumptions 已知局限）。结果截断、调用审计留 {@code claims_events} 与工具自身实现。
 */
@Component
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolLevel.Level> nameToLevel;

    /** Spring 装配入口：从容器反射扫出全部分级工具。 */
    public ToolRegistry(ApplicationContext applicationContext) {
        this(scan(applicationContext));
    }

    /** 测试/显式装配入口。 */
    ToolRegistry(Map<String, ToolLevel.Level> nameToLevel) {
        this.nameToLevel = new LinkedHashMap<>(nameToLevel);
        if (!this.nameToLevel.isEmpty()) {
            logger.info("ToolRegistry: 已登记 {} 个分级工具 → {}", this.nameToLevel.size(), this.nameToLevel);
        }
    }

    public static ToolRegistry from(Map<String, ToolLevel.Level> nameToLevel) {
        return new ToolRegistry(nameToLevel);
    }

    /** 某工具的分级；未登记（含 MCP / 未标注 @ToolLevel 的读工具）一律视为 READ_ONLY。 */
    public ToolLevel.Level levelOf(String toolName) {
        return nameToLevel.getOrDefault(toolName, ToolLevel.Level.READ_ONLY);
    }

    /** 过滤：只保留级别不高于 {@code allowed} 的工具回调（读槽位传 READ_ONLY 即丢弃一切写/高危工具）。 */
    public ToolCallback[] filter(ToolCallback[] all, ToolLevel.Level allowed) {
        return filter(all, level -> level.ordinal() <= allowed.ordinal());
    }

    /** 便捷：只读槽位（AIOps）专用，等价于 {@code filter(all, READ_ONLY)}。 */
    public ToolCallback[] readOnlyOnly(ToolCallback[] all) {
        return filter(all, ToolLevel.Level.READ_ONLY);
    }

    private ToolCallback[] filter(ToolCallback[] all, Predicate<ToolLevel.Level> keep) {
        if (all == null || all.length == 0) {
            return all;
        }
        return java.util.Arrays.stream(all)
                .filter(cb -> keep.test(levelOf(cb.getToolDefinition().name())))
                .toArray(ToolCallback[]::new);
    }

    /** 反射扫容器：bean 类带 {@link ToolLevel} → 类上每个 {@link Tool} 方法登记一条 name→level。 */
    private static Map<String, ToolLevel.Level> scan(ApplicationContext applicationContext) {
        Map<String, ToolLevel.Level> map = new LinkedHashMap<>();
        applicationContext.getBeansWithAnnotation(ToolLevel.class)
                .forEach((beanName, bean) -> {
                    Class<?> type = ClassUtils.getUserClass(bean.getClass());
                    ToolLevel level = type.getAnnotation(ToolLevel.class);
                    if (level == null) {
                        return;
                    }
                    for (java.lang.reflect.Method method : type.getMethods()) {
                        Tool tool = method.getAnnotation(Tool.class);
                        if (tool != null) {
                            String toolName = tool.name().isBlank() ? method.getName() : tool.name();
                            map.put(toolName, level.value());
                        }
                    }
                });
        return map;
    }
}
