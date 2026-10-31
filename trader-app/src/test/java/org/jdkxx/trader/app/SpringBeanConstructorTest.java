package org.jdkxx.trader.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * A Spring component must not have more than one public constructor (unless one of them is
 * {@code @Autowired}).
 *
 * <p>This test was added after the fact: {@code JobsHealthIndicator} once had two public
 * constructors (one for Spring, one for tests to inject a clock); Spring could not choose, looked
 * for a no-arg constructor and failed at startup. Since it is only wired on instances with
 * scheduling enabled, the dev instance never creates this bean: all green locally, fails to start
 * in production. It guards the same class of "invisible in dev, explodes in production" problems as
 * {@code ScheduledPlaceholdersTest}.
 *
 * <p><b>Spring's classpath scanning cannot be used</b>: {@code
 * ClassPathScanningCandidateComponentProvider} also evaluates {@code @Conditional}, so classes
 * whose conditions do not hold are never found, and those are exactly the problematic ones. The
 * test would pass falsely (the first version was written that way and stayed green with the second
 * constructor added back). So the compiled classes are walked directly.
 */
class SpringBeanConstructorTest {

  @Test
  void 组件只能有一个公开构造器() throws Exception {
    List<Class<?>> components = scanCompiledClasses();
    // Scanning trader-app alone finds about 10; all nine modules should give far more. The number
    // is set low, but it must catch "only one module scanned"
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
   * Walks the compiled classes of <b>all modules</b> and picks the classes annotated with
   * {@code @Component} (including meta-annotations such as {@code @RestController}).
   *
   * <p>Originally only trader-app's own {@code target/classes} was scanned (taken from {@code
   * TraderApplication}'s code source), while {@code @SpringBootApplication(scanBasePackages =
   * "org.jdkxx.trader")} scans nine modules, so not a single scanned component in core / storage /
   * gateway was checked (found in the 2026-09-25 full-project review). Now the {@code
   * target/classes} of every module of this repository are scanned together.
   */
  private static List<Class<?>> scanCompiledClasses() throws Exception {
    Path appClasses =
        Path.of(
            TraderApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    List<Class<?>> found = new ArrayList<>();
    for (Path root : moduleClassRoots(appClasses)) {
      scanInto(root, found);
    }
    return found;
  }

  /**
   * The compile output directories of this repository's modules (each module's {@code
   * target/classes} under the repository root).
   *
   * <p><b>They cannot be picked from the classpath</b>: after {@code package} the reactor gives
   * downstream modules the <b>jar</b> instead of the {@code target/classes} directory, so {@code
   * mvn test} sees nine modules and {@code mvn verify} only trader-app: green locally with test,
   * broken before release with verify (this actually happened when releasing 3.1.2 on 2026-09-25).
   * Locating directories by repository layout behaves the same in both phases: a module's compiled
   * classes are in {@code target/classes} in every phase.
   *
   * <p>surefire's working directory is the module basedir ({@code SpaForwardControllerTest} in the
   * same package relies on this too), so one level up is the repository root.
   */
  private static List<Path> moduleClassRoots(Path appClasses) throws IOException {
    Path repo = Path.of("..").toAbsolutePath().normalize();
    List<Path> roots = new ArrayList<>();
    if (Files.isDirectory(repo)) {
      try (var modules = Files.list(repo)) {
        modules
            .map(m -> m.resolve("target").resolve("classes"))
            .filter(Files::isDirectory)
            .sorted()
            .forEach(roots::add);
      }
    }
    if (roots.isEmpty() && Files.isDirectory(appClasses)) {
      roots.add(appClasses); // fallback: when the layout does not match, at least scan this module
    }
    return roots;
  }

  private static void scanInto(Path root, List<Class<?>> found) throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            String name = file.getFileName().toString();
            if (!name.endsWith(".class") || name.contains("$")) {
              return FileVisitResult.CONTINUE;
            }
            String className =
                root.relativize(file)
                    .toString()
                    .replace(java.io.File.separatorChar, '.')
                    .replaceAll("\\.class$", "");
            try {
              Class<?> type =
                  Class.forName(className, false, SpringBeanConstructorTest.class.getClassLoader());
              if (!type.isInterface() && isComponent(type)) {
                found.add(type);
              }
            } catch (Throwable ignored) {
              // classes that cannot be loaded (missing optional dependencies etc.) are not checked
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  /**
   * Annotated with @Component directly or indirectly through meta-annotations. Spring's utilities
   * must be used to search recursively: @RestController -&gt; @Controller -&gt; @Component is two
   * levels deep, and a hand-written one-level check misses every controller (the first version did,
   * scanning only 2 classes and failing the "too few found" assertion).
   */
  private static boolean isComponent(Class<?> type) {
    return AnnotatedElementUtils.hasAnnotation(type, Component.class);
  }
}
