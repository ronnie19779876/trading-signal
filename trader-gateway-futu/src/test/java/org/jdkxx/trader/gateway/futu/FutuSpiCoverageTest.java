package org.jdkxx.trader.gateway.futu;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每一种发出去的富途请求，{@link FutuChannel} 的 SPI 里都必须有对应的 {@code onReply_*}。
 *
 * <p>坑表里记着：漏注册的表现是<b>请求超时</b>而不是编译错误（实测快照 400 只只要 228ms，
 * 超时必是没注册）。而在 3.1.1 之前，{@code FutuChannelTest} 全类只有一个测试
 * （「不得在回复线程上发请求」），完全不碰 SPI——这条坑没有任何守护
 * （2026-09-25 全项目审查发现）。
 *
 * <p>按源码文本核对：调用侧一律写成 {@code Xxx.Response.class}，SPI 一侧一律写成
 * {@code onReply_Xxx(FTAPI_Conn client, int nSerialNo, Xxx.Response rsp)}，两边取交集比对。
 * 不用反射：SPI 是匿名内部类里的重写方法，而且真正要防的是「新加请求忘了加回调」——
 * 那正是源码层面就能看出来的事。
 */
class FutuSpiCoverageTest {

    private static final Path SRC = Path.of("src/main/java/org/jdkxx/trader/gateway/futu");
    private static final Path CHANNEL = SRC.resolve("FutuChannel.java");

    /** 调用侧：作为请求类型传出去的 {@code Xxx.Response.class}。 */
    private static final Pattern REQUESTED = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\.Response\\.class\\b");
    /** SPI 侧：{@code onReply_X(..., Xxx.Response rsp)}，以形参类型为准。 */
    private static final Pattern REGISTERED = Pattern.compile("onReply_[A-Za-z0-9_]+\\s*\\([^)]*?\\b([A-Za-z][A-Za-z0-9_]*)\\.Response\\s+\\w+\\s*\\)");

    @Test
    void 每种请求都注册了对应的onReply回调() throws IOException {
        Set<String> requested = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                collect(REQUESTED, Files.readString(f), requested);
            }
        }
        Set<String> registered = new TreeSet<>();
        collect(REGISTERED, Files.readString(CHANNEL), registered);

        assertThat(requested).as("一条请求都没扫到，说明扫描本身失效了（改了调用写法？）").hasSizeGreaterThan(8);
        assertThat(registered).as("一个 onReply_* 都没扫到，说明扫描本身失效了").hasSizeGreaterThan(8);

        Set<String> missing = new LinkedHashSet<>(requested);
        missing.removeAll(registered);
        assertThat(missing)
                .as("这些请求发得出去、回复接不住，表现是请求超时而不是编译错误：在 FutuChannel 的 SPI 里补 onReply_*")
                .isEmpty();
    }

    /** 反过来也查一次：注册了却没人用的回调是死代码，多半是请求被删了而回调忘了删。 */
    @Test
    void 没有注册了却无人使用的回调() throws IOException {
        Set<String> requested = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                collect(REQUESTED, Files.readString(f), requested);
            }
        }
        Set<String> registered = new TreeSet<>();
        collect(REGISTERED, Files.readString(CHANNEL), registered);

        Set<String> unused = new LinkedHashSet<>(registered);
        unused.removeAll(requested);
        assertThat(unused).as("注册了 onReply_* 但没有任何地方发这种请求").isEmpty();
    }

    private static void collect(Pattern p, String src, Set<String> into) {
        Matcher m = p.matcher(src);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    /** 扫描本身要能读到文件——路径写错时上面两条会变成空集比空集，永远绿。 */
    @Test
    void 扫描路径有效() {
        assertThat(Files.isDirectory(SRC)).as("源码目录 %s 不在，测试需要跟着改", SRC).isTrue();
        assertThat(Files.exists(CHANNEL)).as("%s 不在，测试需要跟着改", CHANNEL).isTrue();
        assertThat(List.of()).isEmpty();
    }
}
