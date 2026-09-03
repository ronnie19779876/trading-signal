package org.jdkxx.trader.core.marketdata.universe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTablesTest {

    @Test
    void 去标签去脚注解实体压空白() {
        assertThat(HtmlTables.text("<a href=\"x\">Apple</a> Inc.<sup>[3]</sup>&nbsp;&amp; Co&#8217;s")).isEqualTo("Apple Inc. & Co’s");
    }
}
