package io.github.kiyo29.origami.internal;

import org.slf4j.Logger;

import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/** java.util.logging のログ(Bukkit のプラグインロガー)を SLF4J へ転送する。 */
public final class JulToSlf4jHandler extends Handler {
    private final Logger target;
    private final Formatter formatter = new SimpleFormatter();

    public JulToSlf4jHandler(Logger target) {
        this.target = target;
        setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null) {
            return;
        }
        int level = record.getLevel().intValue();
        String message = formatter.formatMessage(record);
        Throwable thrown = record.getThrown();
        if (level >= Level.SEVERE.intValue()) {
            target.error(message, thrown);
        } else if (level >= Level.WARNING.intValue()) {
            target.warn(message, thrown);
        } else if (level >= Level.INFO.intValue()) {
            target.info(message, thrown);
        } else if (level >= Level.FINE.intValue()) {
            target.debug(message, thrown);
        } else {
            target.trace(message, thrown);
        }
    }

    @Override
    public void flush() { }

    @Override
    public void close() { }
}
