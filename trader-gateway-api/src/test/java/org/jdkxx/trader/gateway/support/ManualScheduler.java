package org.jdkxx.trader.gateway.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Single-threaded scheduler on virtual time: tests advance time with {@link #tick(Duration)}, which
 * runs every task that has come due, so results are fully deterministic.
 */
final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {

  private final List<Task<?>> tasks = new ArrayList<>();
  private long now;
  private boolean shutdown;

  long nowMillis() {
    return now;
  }

  /**
   * Advances virtual time and runs tasks in due order (including immediate tasks submitted while
   * running).
   */
  void tick(Duration d) {
    long target = now + d.toMillis();
    while (true) {
      Task<?> next = null;
      for (Task<?> t : tasks) {
        if (!t.cancelled && t.due <= target && (next == null || t.due < next.due)) {
          next = t;
        }
      }
      if (next == null) {
        break;
      }
      now = Math.max(now, next.due);
      tasks.remove(next);
      next.run();
    }
    now = target;
  }

  void runPending() {
    tick(Duration.ZERO);
  }

  private <V> Task<V> add(Callable<V> c, long delayMs, long periodMs) {
    Task<V> t = new Task<>(c, now + delayMs, periodMs);
    tasks.add(t);
    return t;
  }

  @Override
  public void execute(Runnable command) {
    add(
        () -> {
          command.run();
          return null;
        },
        0,
        0);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return add(
        () -> {
          command.run();
          return null;
        },
        unit.toMillis(delay),
        0);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    return add(callable, unit.toMillis(delay), 0);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    return scheduleWithFixedDelay(command, initialDelay, period, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return add(
        () -> {
          command.run();
          return null;
        },
        unit.toMillis(initialDelay),
        unit.toMillis(delay));
  }

  @Override
  public void shutdown() {
    shutdown = true;
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown = true;
    tasks.clear();
    return List.of();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return shutdown;
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) {
    return true;
  }

  private final class Task<V> implements ScheduledFuture<V> {
    private final Callable<V> callable;
    private long due;
    private final long period;
    private boolean cancelled;
    private boolean done;

    Task(Callable<V> callable, long due, long period) {
      this.callable = callable;
      this.due = due;
      this.period = period;
    }

    void run() {
      try {
        callable.call();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      if (period > 0 && !cancelled) {
        due = now + period;
        tasks.add(this);
      } else {
        done = true;
      }
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(due - now, TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
      return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      cancelled = true;
      tasks.remove(this);
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return done || cancelled;
    }

    @Override
    public V get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public V get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }
}
