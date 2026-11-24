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

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Suppresses the OpenTelemetry javaagent's "Failed to export ..." WARN flood on stderr.
 *
 * <p>The otel javaagent (attached by the invoker base image, outside this jar's control) logs
 * through its own repackaged slf4j-simple in the default {@code otel.javaagent.logging=simple}
 * mode. That logger writes directly to {@code System.err}, bypassing log4j2 entirely, and is
 * configured only by system properties read at premain — so neither {@code log4j2.xml} nor any
 * code in this jar can turn the logger itself down. When the collector (SigNoz) sheds load
 * ("data refused due to high memory usage"), every failed batch emits a WARN line and floods
 * the extension logs.
 *
 * <p>What this jar <em>can</em> do: slf4j-simple resolves {@code System.err} dynamically on each
 * write (its default {@code cacheOutputStream=false}), so installing a filtering stream via
 * {@link System#setErr} intercepts every subsequent agent log line. Only exporter-failure lines
 * are dropped; all other stderr output — including other otel warnings — passes through
 * untouched, and tracing itself stays fully enabled (spans export normally once the collector
 * recovers).
 *
 * <p>So the failures aren't invisible, a single summary line is emitted at most every
 * {@link #SUMMARY_INTERVAL_MS} reporting how many warnings were suppressed since the last
 * summary. The summary is written straight to the delegate stream (never via log4j2) so the
 * filter can never recurse through a logging framework.
 */
public final class OtelAgentLogFilter extends PrintStream {

    /** Prefix stamped by the agent's internal simple logger on every line it emits. */
    private static final String OTEL_AGENT_PREFIX = "[otel.javaagent";
    /** Matches span/metric/log exporter failures (e.g. "Failed to export spans. ..."). */
    private static final String EXPORT_FAILURE_MARKER = "Failed to export";
    private static final long SUMMARY_INTERVAL_MS = 5 * 60 * 1000L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private final AtomicLong suppressedSinceSummary = new AtomicLong();
    private final AtomicLong lastSummaryAtMillis;

    /** Set env var {@code GOOGLE_CALENDAR_OTEL_STDERR_FILTER=off} to disable the filter without a rebuild. */
    private static final String DISABLE_ENV_VAR = "GOOGLE_CALENDAR_OTEL_STDERR_FILTER";

    /**
     * Installs the filter over the current {@code System.err}. Idempotent — subsequent calls
     * are no-ops, so the stream is never double-wrapped. Honors {@link #DISABLE_ENV_VAR} as a
     * runtime kill switch so the filter can be ruled out when diagnosing logging issues.
     */
    public static boolean install() {
        if ("off".equalsIgnoreCase(System.getenv(DISABLE_ENV_VAR))) {
            return false;
        }
        if (INSTALLED.compareAndSet(false, true)) {
            System.setErr(new OtelAgentLogFilter(System.err));
        }
        return true;
    }

    private OtelAgentLogFilter(PrintStream delegate) {
        super(delegate, true);
        this.lastSummaryAtMillis = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * slf4j-simple emits each log record as a single {@code println(String)} call, so filtering
     * this overload alone is sufficient — and multi-line output from other sources (stack
     * traces, etc.) is never swallowed.
     */
    @Override
    public void println(String line) {
        if (isExportFailure(line)) {
            long suppressed = suppressedSinceSummary.incrementAndGet();
            maybeEmitSummary(suppressed);
            return;
        }
        super.println(line);
    }

    private static boolean isExportFailure(String line) {
        return line != null
                && line.startsWith(OTEL_AGENT_PREFIX)
                && line.contains(EXPORT_FAILURE_MARKER);
    }

    private void maybeEmitSummary(long suppressedSoFar) {
        long now = System.currentTimeMillis();
        long last = lastSummaryAtMillis.get();
        if (now - last >= SUMMARY_INTERVAL_MS && lastSummaryAtMillis.compareAndSet(last, now)) {
            suppressedSinceSummary.addAndGet(-suppressedSoFar);
            super.println("[otel-log-filter] Suppressed " + suppressedSoFar
                    + " OpenTelemetry export-failure warnings since the last summary"
                    + " (collector is refusing data — spans resume when it recovers)");
        }
    }
}
