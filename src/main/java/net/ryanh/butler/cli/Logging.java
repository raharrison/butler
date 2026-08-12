package net.ryanh.butler.cli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.JsonEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import net.ryanh.butler.config.model.Enums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chooses the log format for the process (DESIGN.md §10.3). Only the daemon calls it: an
 * interactive command keeps the text pattern {@code logback.xml} sets up.
 *
 * <p>Logback's own {@code JsonEncoder}, which is why structured logging costs no dependency.
 */
public final class Logging {

    private Logging() {
    }

    /**
     * Switches the root logger to JSON. Text needs nothing done, being what {@code logback.xml}
     * already configures.
     */
    public static void configure(Enums.LogFormat format) {
        if (format != Enums.LogFormat.JSON
                || !(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return;
        }
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        JsonEncoder encoder = new JsonEncoder();
        encoder.setContext(context);
        // By default `jq .message` prints "{} in {}", the pattern and its arguments being separate
        // fields. The rest are constant or zero on every line.
        encoder.setWithFormattedMessage(true);
        encoder.setWithMessage(false);
        encoder.setWithArguments(false);
        encoder.setWithSequenceNumber(false);
        encoder.setWithContext(false);
        encoder.setWithNanoseconds(false);
        encoder.start();

        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setName("STDERR-JSON");
        appender.setContext(context);
        appender.setTarget("System.err");
        appender.setEncoder(encoder);
        appender.start();

        root.detachAndStopAllAppenders();
        root.addAppender(appender);
        root.setLevel(Level.INFO);
    }
}
