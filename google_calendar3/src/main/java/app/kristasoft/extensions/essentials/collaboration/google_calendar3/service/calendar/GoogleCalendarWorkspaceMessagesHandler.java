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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.calendar;

import app.krista.conversationagent.module.calendar.esb.CalendarWorkspaceMessagesProto;
import app.krista.extension.executor.Invoker;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.CalendarSyncTelemetryService;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync.WorkspaceMessagesStore;
import app.krista.infra.esb.service.ServiceEndpoint;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Locale;

/**
 * Stores the workspace's in-call messages the moment Meeting Agent broadcasts them (KIM-492).
 *
 * <p>Google Meet has no extension-side chat surface — the Krista Bot presents both messages itself from
 * its {@code BOT_CONFIG} — so this handler PRESENTS nothing. It drains a durable, per-system queue that
 * would otherwise accumulate one message per settings save per Google-bound workspace forever, and it
 * records what it drained so a future Google-side surface reads state it was already sent rather than
 * asking Meeting Agent for state that had been thrown away. See
 * {@link GoogleCalendarWorkspaceMessagesService} for the full reasoning.
 *
 * <h2>An EMPTY message clears; it never means "no change"</h2>
 *
 * <p>Meeting Agent dispatches a DELETE of the compliance notice as an empty compliance notice, on
 * purpose: a withdrawn notice has to stop being presented at once rather than at the next scheduled
 * refresh. So both fields are handed to the store exactly as received and the store clears on blank.
 * Treating an empty field as "leave the previous value alone" would keep presenting legal copy the
 * customer had just withdrawn — the one direction in this feature where stale text has consequences
 * outside the product.
 *
 * <h2>Foreign broadcasts are IGNORED, not merely logged</h2>
 *
 * <p>The destination is shared per calendar system and isolation is by the consumer's tenant selector,
 * so a broadcast for another workspace should never arrive here. Should is not a guarantee: this
 * extension runs multi-tenant, and if that selector is ever wrong this pod would overwrite THIS
 * workspace's compliance notice with a stranger's — silently, because a store write succeeds either way.
 * Comparing the command's {@code workspaceId} against the invoker's own turns that into one WARN naming
 * both ids. Note the tenant check matters MORE here than on the seat consumer: a wrongly-routed seat
 * event costs one redundant roster refresh, whereas a wrongly-routed broadcast cross-contaminates one
 * customer's legal copy with another's.
 *
 * <p>A broadcast whose {@code workspaceId} is blank is treated as OURS, matching
 * {@link GoogleCalendarSeatRegisteredHandler}: Meeting Agent populates it today, and refusing an
 * unattributed broadcast would mean a Meeting Agent that stops populating the field silently freezes
 * this store on whatever it last held.
 *
 * <h2>A failed store is ACKNOWLEDGED, never rethrown</h2>
 *
 * <p>The queue is durable with {@code setProcessorAutoAcknowledge(false)}, so an escaping throwable
 * makes the broker redeliver the same command — and the conditions that fail this call (an unavailable
 * key-value store, a mis-staged contract jar) are exactly the conditions that fail every redelivery. One
 * settings save would become an unbounded retry loop, on a queue whose whole purpose here is to be
 * drained. Meeting Agent re-broadcasts on its next boot and on the next admin save, which is the
 * recovery path. So the failure is logged at WARN, recorded on a metric, and the message acknowledged.
 *
 * <p>{@link Throwable} rather than {@link RuntimeException}: on a durable manual-ack queue an
 * {@code Error} — a {@code NoClassDefFoundError} from a mis-staged contract jar is the realistic one —
 * redelivers exactly like an exception, and a poison message that cannot be decoded would be retried
 * forever.
 *
 * <h2>The message text never reaches a log</h2>
 *
 * <p>A compliance notice is the customer's own legal copy. Lengths and the correlation id are logged;
 * contents are not, here or in the store.
 *
 * @since 3.1.0
 */
@Service
public class GoogleCalendarWorkspaceMessagesHandler
        implements GoogleCalendarWorkspaceMessagesService, ServiceEndpoint {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GoogleCalendarWorkspaceMessagesHandler.class);

    private final WorkspaceMessagesStore messagesStore;
    private final CalendarSyncTelemetryService telemetryService;
    private final Invoker invoker;

    @Inject
    public GoogleCalendarWorkspaceMessagesHandler(WorkspaceMessagesStore messagesStore,
                                                  CalendarSyncTelemetryService telemetryService,
                                                  Invoker invoker) {
        this.messagesStore = messagesStore;
        this.telemetryService = telemetryService;
        this.invoker = invoker;
    }

    @Override
    public void googleCalendarWorkspaceMessages(CalendarWorkspaceMessagesProto command) {
        if (command == null) {
            LOGGER.warn("[GCAL-MESSAGES-EVENT] Received a null messages broadcast — acknowledging.");
            record("null_command", false);
            return;
        }

        // Lengths, not contents: the compliance notice is the customer's own legal text.
        LOGGER.info("[GCAL-MESSAGES-EVENT] workspace={} welcome={} chars compliance={} chars corr={}"
                        + " emittedAt={}", command.getWorkspaceId(),
                length(command.getWelcomeMessage()), length(command.getComplianceNotice()),
                command.getCorrelationId(), command.getEmittedAt());

        if (!isForThisWorkspace(command.getWorkspaceId())) {
            // Acknowledged, not requeued: redelivering a broadcast that is not ours would loop, and
            // the workspace it IS for has its own consumer.
            LOGGER.warn("[GCAL-MESSAGES-EVENT] Ignoring an in-call messages broadcast for workspace {}"
                            + " — this invoker serves {}. Consumer tenant isolation is by the"
                            + " consumer's tenant selector; a mismatch here means that selector is not"
                            + " doing its job, and acting on it would overwrite this workspace's"
                            + " compliance notice with another customer's legal copy.",
                    command.getWorkspaceId(), ownWorkspaceId());
            record("ignored_foreign_workspace", true);
            return;
        }

        try {
            // Handed over exactly as received. An empty field means "present nothing" and the store
            // clears on blank — see the class javadoc.
            WorkspaceMessagesStore.Messages stored = messagesStore.store(
                    command.getWelcomeMessage(), command.getComplianceNotice());
            record(stored.isEmpty() ? "stored_cleared" : "stored", true);
        }
        catch (Throwable failure) {
            record("store_failed", false);
            // Acknowledged deliberately — see the class javadoc. Rethrowing redelivers.
            LOGGER.warn("[GCAL-MESSAGES-EVENT] Could not store the in-call messages; acknowledging the"
                            + " broadcast. Meeting Agent re-broadcasts on its next boot and on the next"
                            + " settings save, which is the recovery path: {}",
                    failure.getMessage(), failure);
        }
    }

    /**
     * Whether this broadcast is about the workspace this invoker serves.
     *
     * <p>A blank command workspace, or an invoker that cannot report its own, is treated as a match —
     * the same bias as the seat consumer, and for the same reason: refusing an unattributable broadcast
     * would freeze this store on whatever it last held, with nothing to re-send it.
     *
     * @param commandWorkspaceId the workspace on the broadcast
     * @return true when the broadcast may be acted on
     */
    private boolean isForThisWorkspace(String commandWorkspaceId) {
        if (commandWorkspaceId == null || commandWorkspaceId.isBlank()) {
            return true;
        }
        String own = ownWorkspaceId();
        if (own == null || own.isBlank()) {
            return true;
        }
        return own.trim().equalsIgnoreCase(commandWorkspaceId.trim());
    }

    private String ownWorkspaceId() {
        try {
            return invoker.getWorkspaceId();
        }
        catch (Exception unavailable) {
            LOGGER.debug("[GCAL-MESSAGES-EVENT] Could not read this invoker's workspace id: {}",
                    unavailable.getMessage());
            return null;
        }
    }

    /**
     * Records the outcome. Never allowed to throw: this handler's contract is to acknowledge, and an
     * unavailable metrics backend must not turn that into a redelivery loop.
     */
    private void record(String outcome, boolean success) {
        try {
            telemetryService.recordApiCall("ma",
                    "workspace_messages_" + outcome.toLowerCase(Locale.ROOT), success);
        }
        catch (Exception metricsUnavailable) {
            LOGGER.debug("[GCAL-MESSAGES-EVENT] Could not record the messages-event metric: {}",
                    metricsUnavailable.getMessage());
        }
    }

    private static int length(String message) {
        return message == null ? 0 : message.length();
    }
}
