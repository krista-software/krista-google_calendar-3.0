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

package app.kristasoft.extensions.essentials.collaboration.google_calendar3.testutil;

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.CaGateway;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.ca.EsbCaGateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Builds the {@link EsbCaGateway} collaborator that call-site tests need but have no opinion about.
 *
 * <p>This replaces {@code TestCaTransport}, which built a {@code CaTransportSelector} over an ESB
 * and an HTTP gateway. The {@code caTransport} attribute was never commissioned and was struck by
 * the product owner on 2026-09-11; the extension is ESB-only, so call sites inject
 * {@link EsbCaGateway} directly and there is nothing left to select.
 *
 * <p>Tests whose subject IS a Meeting Agent call mock {@link EsbCaGateway} themselves and stub the
 * method they care about. {@link #unusedGateway()} is for the rest — a test of, say, the always-on
 * gate must construct the collaborator but never exercises it, and a gateway that answered
 * {@code null} to an unstubbed call would turn a wiring slip into an NPE rather than a clear
 * failure.
 */
public final class TestCaGateway {

    private TestCaGateway() {
    }

    /**
     * A do-nothing gateway, for tests that must construct a collaborator but never make a Meeting
     * Agent call.
     *
     * <p>The two stubs are deliberate rather than decorative: an unstubbed {@code upsertMeeting}
     * would return {@code null} and NPE inside the call site's own outcome handling, hiding what
     * actually went wrong. A named FAILED outcome makes an unexpected call say so.
     *
     * @return a default-answering mock gateway
     */
    public static EsbCaGateway unusedGateway() {
        EsbCaGateway gateway = mock(EsbCaGateway.class, RETURNS_DEFAULTS);
        lenient().when(gateway.upsertMeeting(any())).thenReturn(
                CaGateway.UpsertOutcome.failed(null, "no gateway wired in this test"));
        lenient().when(gateway.deleteMeeting(anyString(), any(), any())).thenReturn(false);
        return gateway;
    }
}
