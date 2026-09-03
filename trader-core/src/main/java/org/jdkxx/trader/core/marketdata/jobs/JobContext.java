package org.jdkxx.trader.core.marketdata.jobs;

/** 作业执行上下文：汇报进度、标记部分失败。 */
public interface JobContext {

    long id();

    void progress(String text);

    /** 标记本次作业"部分完成"（有失败但整体继续），最终状态为 PARTIAL。 */
    void partial(String reason);

    boolean cancelled();
}
