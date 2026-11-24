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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.esb.publishers;

import app.krista.infra.esb.ServiceBusConfig;
import app.krista.infra.esb.client.Input;
import app.krista.infra.esb.client.QRequester;
import app.krista.infra.esb.client.QueueClient;
import app.krista.infra.esb.service.Reply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for publishing events to CA Service via ESB (fire-and-forget).
 *
 * <p>Mirrors {@code app.krista.global.esb.publishers.EventProducer} from the KIP module.
 * Included here because the Google Calendar module does not depend on the KIP module.
 *
 * @since 3.0.2
 */
public abstract class EventProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EventProducer.class);

    private static final String ESB_CONFIG_PATH = "esb/default_esb.properties";
    private static final String ESB_CONTENT_TYPE_PROTO = "application/x-protobuf";

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "gcal-event-producer");
                t.setDaemon(true);
                return t;
            });

    /**
     * Publish an event as protobuf bytes synchronously (blocking, fire-and-forget).
     *
     * @param queueName   the destination queue base name
     * @param workspaceId the workspace ID for tenant isolation
     * @param event       the event object (must be registered with the serialization context)
     * @param apiName     the API name for message routing on the consumer side
     */
    protected void publishProtoEventSync(String queueName, String workspaceId, Input event, String apiName) {
        try {
            publishProtoEvent(queueName, workspaceId, event, apiName).join();
        } catch (Exception e) {
            LOG.error("Synchronous proto event publishing failed for queue '{}': {}", queueName, e.getMessage(), e);
            throw new RuntimeException("Synchronous proto event publishing failed", e);
        }
    }

    private CompletableFuture<Void> publishProtoEvent(String queueName, String workspaceId,
                                                       Input event, String apiName) {
        return CompletableFuture.runAsync(() -> {
            try {
                String eventType = event.getClass().getSimpleName();
                String eventClassName = event.getClass().getName();

                LOG.debug("[ESB-PUBLISH-PROTO] queue='{}', tenantId='{}', eventType='{}', apiName='{}'",
                        queueName, workspaceId, eventType, apiName);

                ServiceBusConfig config = new ServiceBusConfig(ESB_CONFIG_PATH)
                        .setDestinationBaseName(queueName)
                        .setVersion(1)
                        .setDestinationType(ServiceBusConfig.DestinationType.Queue);

                Map<String, String> props = new HashMap<>();
                props.put("esb_tenantId", workspaceId);
                props.put("esb_protoMsg", eventClassName);
                props.put(Input.API_HDR_PROP, apiName != null ? apiName : "handleEvent");
                props.put(Input.API_VER_PROP, "1");
                props.put("esb_contentType", ESB_CONTENT_TYPE_PROTO);

                QueueClient<Reply> client = new QueueClient<>(config, props, event, Reply.class);
                client.setOneWay(true);

                try (QRequester<Reply> requester = new QRequester<>(config)) {
                    requester.send(client);
                }

                LOG.info("[ESB-PUBLISH-PROTO] Published '{}' to queue '{}' for workspace '{}'",
                        eventType, queueName, workspaceId);
            } catch (Exception e) {
                LOG.error("[ESB-PUBLISH-PROTO] FAILED to publish to queue '{}' for workspace '{}': {}",
                        queueName, workspaceId, e.getMessage(), e);
                throw new RuntimeException("Proto event publishing failed", e);
            }
        }, EXECUTOR);
    }

    /** Shutdown the event producer executor on application stop. */
    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
