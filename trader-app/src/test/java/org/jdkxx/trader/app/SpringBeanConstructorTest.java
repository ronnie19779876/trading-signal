package org.jdkxx.trader.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 组件不能有多个公开构造器（除非其中一个标了 {@code @Autowired}）。
 *
 * <p>这个测试是补的：{@code JobsHealthIndicator} 曾经有两个公开构造器（一个给 Spring、一个给测试塞时钟），
 * Spring 选不出来就去找无参构造器，启动直接失败。而它只在开了跑批的实例装配，
 * 开发实例根本不创建这个 bean，本地全绿、一上生产就起不来——和 {@code ScheduledPlaceholdersTest}
 * 守的是同一类"开发发现不了、生产才炸"的问题。
 *
 * <p><b>不能用 Spring 的类路径扫描</b>：{@code ClassPathScanningCandidateComponentProvider}
 * 会顺带评估 {@code @Conditional}，条件不满足的类根本不会被扫出来——而出问题的恰恰就是这种类，
 * 测试会假通过（第一版就是这么写的，加回第二个构造器它照样绿）。所以这里直接遍历编译产物。
 */
class SpringBeanConstructorTest {

    @Test
    void 组件只能有一个公开构造器() throws Exception {
        List<Class<?>> components = scanCompiledClasses();
        assertThat(components).as("至少要扫到几个组件，否则是扫描本身失效了").hasSizeGreaterThan(2);

        List<String> bad = new ArrayList<>();
        for (Class<?> type : components) {
            Constructor<?>[] publicCtors = type.getConstructors();
            boolean annotated = false;
            for (Constructor<?> c : publicCtors) {
                if (c.isAnnotationPresent(Autowired.class)) {
                    annotated = true;
                    break;
                }
            }
            if (publicCtors.length > 1 && !annotated) {
                bad.add(type.getSimpleName() + " 有 " + publicCtors.length + " 个公开构造器且都没标 @Autowired");
            }
        }

        assertThat(bad).as("这些组件启动时会因为选不出构造器而失败（本地不装配的 bean 尤其危险）").isEmpty();
    }

    /** 遍历本模块的编译产物，挑出标了 {@code @Component}（含 {@code @RestController} 等元注解）的类。 */
    private static List<Class<?>> scanCompiledClasses() throws Exception {
        // 用主类定位编译产物：测试类的 code source 在 surefire 下未必是 target/test-classes
        Path root = Path.of(TraderApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<Class<?>> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return found;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    return FileVisitResult.CONTINUE;
                }
                String className = root.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
                try {
                    Class<?> type = Class.forName(className, false, SpringBeanConstructorTest.class.getClassLoader());
                    if (!type.isInterface() && isComponent(type)) {
                        found.add(type);
                    }
                } catch (Throwable ignored) {
                    // 加载不了的类（缺可选依赖等）不参与检查
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    /**
     * 直接标了 @Component，或经由元注解间接标上的。
     * 必须用 Spring 的工具递归查：@RestController → @Controller → @Component 隔了两层，
     * 手写一层的判断会漏掉所有控制器（第一版就漏了，导致只扫到 2 个类、断言在"扫到的太少"上失败）。
     */
    private static boolean isComponent(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, Component.class);
    }
}
