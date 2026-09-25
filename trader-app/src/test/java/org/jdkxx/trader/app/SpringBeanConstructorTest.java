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
        // 只扫 trader-app 时约 10 个；扫全九个模块应该远多于此。数字定低一点，但必须能挡住"只扫到一个模块"
        assertThat(components).as("组件扫得太少，多半是只扫到了 trader-app 一个模块（全模块应有数十个）").hasSizeGreaterThan(25);

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

    /**
     * 遍历<b>全部模块</b>的编译产物，挑出标了 {@code @Component}（含 {@code @RestController} 等元注解）的类。
     *
     * <p>原先只扫 trader-app 自己的 {@code target/classes}（取自 {@code TraderApplication} 的 code source），
     * 而 {@code @SpringBootApplication(scanBasePackages = "org.jdkxx.trader")} 扫的是九个模块——
     * core / storage / gateway 里被扫描的组件一个都没检查到（2026-09-25 全项目审查发现）。
     * 现在从 classpath 里挑出本仓库各模块的 {@code target/classes} 一起扫。
     */
    private static List<Class<?>> scanCompiledClasses() throws Exception {
        Path appClasses = Path.of(TraderApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        List<Class<?>> found = new ArrayList<>();
        for (Path root : moduleClassRoots(appClasses)) {
            scanInto(root, found);
        }
        return found;
    }

    /**
     * classpath 上属于本仓库的模块编译目录。
     * trader-app 的 target/classes 形如 {@code <仓库>/trader-app/target/classes}，往上三级就是仓库根；
     * 只认仓库根下的目录，外部依赖（jar、本地仓库里的路径）一律不扫。
     */
    private static List<Path> moduleClassRoots(Path appClasses) {
        List<Path> roots = new ArrayList<>();
        Path repo = appClasses.getParent() == null ? null : appClasses.getParent().getParent();
        repo = repo == null ? null : repo.getParent();
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            Path p = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.isDirectory(p) || !p.endsWith(Path.of("target", "classes"))) {
                continue;
            }
            if (repo != null && !p.startsWith(repo)) {
                continue;
            }
            roots.add(p);
        }
        if (roots.isEmpty() && Files.isDirectory(appClasses)) {
            roots.add(appClasses);      // 兜底：classpath 上只有 jar 时至少还扫本模块
        }
        return roots;
    }

    private static void scanInto(Path root, List<Class<?>> found) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
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
