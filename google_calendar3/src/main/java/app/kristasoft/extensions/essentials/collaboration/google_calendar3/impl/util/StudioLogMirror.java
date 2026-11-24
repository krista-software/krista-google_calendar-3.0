/*
 * Google Calendar 3.0 Extension for Krista
 * Copyright (C) 2025 Krista Software
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mirrors this extension's log4j output to stderr so it appears in the Studio 'Extension Log'
 * diagnostics panel.
 *
 * <p><b>Why this exists:</b> the invoker base image captures <em>stderr</em> into
 * {@code /opt/krista/var/log/debug.log}, which is the file the Studio Extension Log panel
 * serves (via getLogs). log4j output, however, goes to a Console appender targeting
 * <em>stdout</em> — visible in the container logs and SigNoz, but never in Studio. Worse, the
 * base image's {@code generate-log4j2-file.sh} regenerates the runtime log4j2.xml keeping only
 * the {@code <Logger>} names/levels from the jar's bundled config and forcing its own
 * stdout-only appender — so a stderr appender cannot be added via {@code resources/log4j2.xml}.
 *
 * <p>The only in-jar remedy is programmatic: attach a second Console appender (SYSTEM_ERR,
 * {@code follow=true}) to this extension's logger config in the running {@link LoggerContext}.
 * {@code follow=true} makes the appender track the <em>current</em> {@code System.err}, i.e. the
 * harness's debug.log capture stream (wrapped by {@link OtelAgentLogFilter}, which passes
 * non-otel lines through untouched).
 *
 * <p>The layout omits the timestamp on purpose — the harness capture stream already prefixes
 * every stderr line with an ISO timestamp in the Studio panel.
 */
public final class StudioLogMirror {

    /**
     * Must match the package logger declared in resources/log4j2.xml. Every logging class in
     * this extension lives under this package, and the base image only carries over declared
     * {@code <Logger>} entries — without that entry this mirror would attach to the Root config.
     */
    private static final String EXTENSION_LOGGER =
            "app.kristasoft.extensions.essentials.collaboration.google_calendar3";
    private static final String APPENDER_NAME = "StudioStderrMirror";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private StudioLogMirror() {}

    /**
     * Attaches the stderr mirror appender to the extension's logger config. Idempotent.
     * Mirrors INFO and above — the extension is not chatty, and lifecycle visibility in the
     * Studio panel is the whole point.
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();

        ConsoleAppender appender = ConsoleAppender.newBuilder()
                .setName(APPENDER_NAME)
                .setTarget(ConsoleAppender.Target.SYSTEM_ERR)
                .setFollow(true)
                .setLayout(PatternLayout.newBuilder()
                        .withPattern("%-5p | %-25.25c{1} | %m{nolookups}%n")
                        .withConfiguration(config)
                        .build())
                .build();
        appender.start();
        config.addAppender(appender);

        LoggerConfig loggerConfig = config.getLoggerConfig(EXTENSION_LOGGER);
        loggerConfig.addAppender(appender, Level.INFO, null);
        ctx.updateLoggers();
    }
}
