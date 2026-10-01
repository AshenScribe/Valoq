package server.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Rate-limits repetitive log messages so a misbehaving client or transient
 * database outage does not saturate disk I/O and CPU with duplicate logs.
 */
public class RateLimitedLogger {

    private final Logger logger;
    private final long minIntervalNanos;
    private final ConcurrentHashMap<String, MessageRateGate> gates = new ConcurrentHashMap<>();

    private RateLimitedLogger(Logger logger, long duration, TimeUnit unit) {
        this.logger = logger;
        this.minIntervalNanos = unit.toNanos(duration);
    }

    public static RateLimitedLogger getLogger(Logger logger, long duration, TimeUnit unit) {
        return new RateLimitedLogger(logger, duration, unit);
    }

    public void warn(String key, String format, Object... args) {
        MessageRateGate gate = gates.computeIfAbsent(key, k -> new MessageRateGate());
        long now = System.nanoTime();
        if (gate.shouldLog(now, minIntervalNanos)) {
            long dropped = gate.resetSuppressed();
            if (dropped > 0) {
                logger.warn(format + " (suppressed {} similar messages)", appendArg(args, dropped));
            } else {
                logger.warn(format, args);
            }
        }
    }

    public void info(String key, String format, Object... args) {
        MessageRateGate gate = gates.computeIfAbsent(key, k -> new MessageRateGate());
        long now = System.nanoTime();
        if (gate.shouldLog(now, minIntervalNanos)) {
            long dropped = gate.resetSuppressed();
            if (dropped > 0) {
                logger.info(format + " (suppressed {} similar messages)", appendArg(args, dropped));
            } else {
                logger.info(format, args);
            }
        }
    }

    private static Object[] appendArg(Object[] args, Object extra) {
        Object[] newArgs = new Object[args.length + 1];
        System.arraycopy(args, 0, newArgs, 0, args.length);
        newArgs[args.length] = extra;
        return newArgs;
    }

    private static class MessageRateGate {
        private final AtomicLong lastLoggedNanos = new AtomicLong(0);
        private final AtomicLong suppressedCount = new AtomicLong(0);

        boolean shouldLog(long now, long intervalNanos) {
            long last = lastLoggedNanos.get();
            if (now - last > intervalNanos) {
                return lastLoggedNanos.compareAndSet(last, now);
            }
            suppressedCount.incrementAndGet();
            return false;
        }

        long resetSuppressed() {
            return suppressedCount.getAndSet(0);
        }
    }
}