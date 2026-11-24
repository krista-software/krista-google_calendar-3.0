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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.service.sync;

import app.krista.extension.executor.Invoker;
import app.krista.extensions.util.KeyValueStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Optional;

/**
 * KIM-492 — the workspace's two <b>in-call messages</b>, as Meeting Agent last broadcast them: the
 * standard Krista welcome and the customer's own compliance notice.
 *
 * <h2>Why a Google extension stores messages it never presents</h2>
 *
 * <p>Google Meet gives this extension no in-call chat surface. The Krista Bot presents both messages
 * itself, from its own {@code BOT_CONFIG}, so there is nothing here to render today. What there IS is a
 * durable, per-system queue: Meeting Agent broadcasts to
 * {@code ext.calendar.workspace.messages.google} on every admin write of the messages settings and for
 * every workspace at its own boot. A queue nobody drains does not stay quiet — it accumulates one
 * message per settings save per Google-bound workspace, forever. So this extension consumes the
 * command, and storing what it consumed is what makes the consumption honest: a future Google-side
 * surface reads this store instead of asking Meeting Agent for state it was already sent.
 *
 * <h2>Empty means "present nothing", never "no change"</h2>
 *
 * <p>That is Meeting Agent's contract ({@code EsbCalendarWorkspaceMessagesNotifier}): a DELETE of the
 * compliance notice is dispatched as an EMPTY compliance notice, not as an omitted field, precisely so
 * a withdrawn notice stops being presented immediately rather than at the next scheduled refresh. A
 * store that treated blank as "leave the previous value alone" would re-introduce exactly the staleness
 * the notifier is shaped to avoid — and on the one field in this feature whose staleness has
 * consequences outside the product. So {@link #store(String, String)} CLEARS on blank.
 *
 * <p>"Received and cleared" and "never received" therefore have to stay distinguishable, and they are:
 * a cleared message is a present snapshot whose field is absent ({@link #messages()} non-empty,
 * {@link #welcomeMessage()} empty), whereas a workspace Meeting Agent has never broadcast to has no
 * snapshot at all ({@link #messages()} empty). A future presenter needs that difference — the first
 * case means "the tenant has nothing to say", the second means "we have not been told yet".
 *
 * <h2>The text never reaches a log</h2>
 *
 * <p>A compliance notice is the customer's own legal copy. Lengths and presence are logged; contents
 * are not, here or in the handler.
 *
 * <h2>Thread safety</h2>
 *
 * <p>The snapshot is a volatile immutable value, so readers never block and never observe a
 * half-written pair. {@link #store(String, String)} is synchronized so two commands consumed
 * concurrently cannot interleave an in-memory replacement with the other's persist.
 *
 * @since 3.1.0
 */
@Service
@Singleton
public class WorkspaceMessagesStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkspaceMessagesStore.class);

    private static final String WELCOME_FIELD = "welcomeMessage";
    private static final String COMPLIANCE_FIELD = "complianceNotice";
    private static final String STORED_AT_FIELD = "storedAt";

    private final Invoker invoker;
    private final KeyValueStore keyValueStore;

    /** The last broadcast received, or null when none has been seen in this process yet. */
    private volatile Messages messages;

    /** Guards the one-time KV read, so a cold process reads the persisted snapshot exactly once. */
    private volatile boolean loadedFromStore;

    @Inject
    public WorkspaceMessagesStore(Invoker invoker, KeyValueStore keyValueStore) {
        this.invoker = invoker;
        this.keyValueStore = keyValueStore;
    }

    /**
     * One broadcast, as received.
     *
     * <p>Either field may be null, which means "present nothing" for that message — see the class
     * javadoc on why that is not the same as this record being absent.
     *
     * @param welcomeMessage   the effective welcome text, or null when there is none to present
     * @param complianceNotice the customer's compliance notice, or null when there is none
     * @param storedAt         epoch millis this extension stored the broadcast
     */
    public record Messages(String welcomeMessage, String complianceNotice, long storedAt) {

        /** @return true when neither message would be presented */
        public boolean isEmpty() {
            return welcomeMessage == null && complianceNotice == null;
        }
    }

    // ── reading ───────────────────────────────────────────────────────────

    /**
     * The messages Meeting Agent last broadcast for this workspace.
     *
     * @return the snapshot, or {@link Optional#empty()} when none has ever been received — the absence
     *         of a signal, which a caller must not read as "this tenant presents nothing"
     */
    public Optional<Messages> messages() {
        return Optional.ofNullable(current());
    }

    /** @return the welcome text to present, empty when there is none or none was ever received */
    public Optional<String> welcomeMessage() {
        Messages held = current();
        return held == null ? Optional.empty() : Optional.ofNullable(held.welcomeMessage());
    }

    /** @return the compliance notice to present, empty when there is none or none was ever received */
    public Optional<String> complianceNotice() {
        Messages held = current();
        return held == null ? Optional.empty() : Optional.ofNullable(held.complianceNotice());
    }

    /** @return epoch millis of the snapshot in force, or 0 when there is none */
    public long storedAt() {
        Messages held = current();
        return held == null ? 0L : held.storedAt();
    }

    // ── writing ───────────────────────────────────────────────────────────

    /**
     * Replaces the stored messages with what Meeting Agent just broadcast.
     *
     * <p>A blank field is stored as ABSENT, not skipped: Meeting Agent dispatches a cleared message as
     * an empty one and expects it to stop being presented (class javadoc).
     *
     * <p>Persisting is best-effort — the in-memory snapshot is already in force, and losing the KV copy
     * costs only the head start after a restart. Nothing here throws: the caller is a consumer on a
     * durable manual-acknowledge queue, where an escaping failure redelivers the same command against
     * the same fault forever.
     *
     * @param welcomeMessage   the welcome text as received; blank or null clears it
     * @param complianceNotice the compliance notice as received; blank or null clears it
     * @return the snapshot now in force
     */
    public synchronized Messages store(String welcomeMessage, String complianceNotice) {
        Messages stored = new Messages(normalise(welcomeMessage), normalise(complianceNotice),
                System.currentTimeMillis());
        this.messages = stored;
        this.loadedFromStore = true;
        persist(stored);

        LOGGER.info("[GCAL-MESSAGES] workspace={} stored welcome={} chars compliance={} chars",
                workspaceId(), length(stored.welcomeMessage()), length(stored.complianceNotice()));
        return stored;
    }

    // ── internals ─────────────────────────────────────────────────────────

    private Messages current() {
        Messages held = messages;
        if (held != null || loadedFromStore) {
            return held;
        }
        return loadFromStore();
    }

    /**
     * Reads the snapshot persisted by a previous process, exactly once.
     *
     * <p>Meeting Agent re-broadcasts to every workspace at ITS boot, not at this extension's, so a
     * container restart between two Meeting Agent boots would otherwise leave this extension with no
     * messages at all until the next settings save.
     */
    private synchronized Messages loadFromStore() {
        if (loadedFromStore) {
            return messages;
        }
        loadedFromStore = true;
        try {
            Object raw = keyValueStore.get(storeKey());
            if (raw == null) {
                return null;
            }
            JsonObject stored = JsonParser.parseString(raw.toString()).getAsJsonObject();
            Messages restored = new Messages(
                    readField(stored, WELCOME_FIELD),
                    readField(stored, COMPLIANCE_FIELD),
                    stored.has(STORED_AT_FIELD) ? stored.get(STORED_AT_FIELD).getAsLong() : 0L);
            this.messages = restored;
            LOGGER.info("[GCAL-MESSAGES] Restored in-call messages recorded at {} from the key-value"
                            + " store: welcome={} chars compliance={} chars", restored.storedAt(),
                    length(restored.welcomeMessage()), length(restored.complianceNotice()));
            return restored;
        }
        catch (Exception cause) {
            // A corrupt or unreadable entry is the same position as never having been told, which the
            // next broadcast corrects. Failing the read would be worse: this is on the consumer path.
            LOGGER.warn("[GCAL-MESSAGES] Could not restore the in-call messages from the key-value"
                    + " store; the next broadcast replaces them: {}", cause.getMessage());
            return null;
        }
    }

    private void persist(Messages stored) {
        try {
            JsonObject payload = new JsonObject();
            // Written only when present, so a cleared message reads back as absent rather than as the
            // string "null" — the JSON shape carries the same distinction the record does.
            if (stored.welcomeMessage() != null) {
                payload.addProperty(WELCOME_FIELD, stored.welcomeMessage());
            }
            if (stored.complianceNotice() != null) {
                payload.addProperty(COMPLIANCE_FIELD, stored.complianceNotice());
            }
            payload.addProperty(STORED_AT_FIELD, stored.storedAt());
            keyValueStore.put(storeKey(), payload.toString());
        }
        catch (Exception cause) {
            LOGGER.warn("[GCAL-MESSAGES] Could not persist the in-call messages: {}",
                    cause.getMessage());
        }
    }

    private static String readField(JsonObject stored, String field) {
        if (!stored.has(field) || stored.get(field).isJsonNull()) {
            return null;
        }
        return normalise(stored.get(field).getAsString());
    }

    private String storeKey() {
        String invokerId;
        try {
            invokerId = invoker.getInvokerId();
        }
        catch (Exception cause) {
            invokerId = "unknown";
        }
        return "gcal_workspace_messages:" + invokerId;
    }

    private String workspaceId() {
        try {
            String workspaceId = invoker.getWorkspaceId();
            return workspaceId == null || workspaceId.isBlank() ? null : workspaceId.trim();
        }
        catch (Exception cause) {
            LOGGER.debug("[GCAL-MESSAGES] Could not read this invoker's workspace id: {}",
                    cause.getMessage());
            return null;
        }
    }

    /** Blank is ABSENT — see the class javadoc. Trimmed, because trailing copy-paste whitespace in a
     *  Settings textarea is not a message. */
    private static String normalise(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int length(String message) {
        return message == null ? 0 : message.length();
    }
}
