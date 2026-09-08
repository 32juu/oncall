package org.example.agent.tool;

import org.springframework.ai.tool.annotation.Tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具风险分级元数据（执行文档 §5.2 草稿的落地）。标注在 {@link Tool} 工具 bean 的**类**上，
 * 声明该类内所有 @Tool 方法的危险等级；配合 {@link ToolRegistry} 做按 agent 槽位的可见性过滤
 * （如 AIOps 只允许 READ_ONLY，聊天允许 WRITE）。
 *
 * <p>缺省 {@link #value()} 为 {@link Level#READ_ONLY}——只有写/高危工具必须显式标注，读工具可不标。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolLevel {

    Level value() default Level.READ_ONLY;

    /** 危险分级：READ_ONLY 只读安全 / WRITE 会改状态（认领、抑制等值班常规写操作）/ HIGH_RISK 高危（自愈执行等，P3 预留）。 */
    enum Level {
        READ_ONLY, WRITE, HIGH_RISK
    }
}
