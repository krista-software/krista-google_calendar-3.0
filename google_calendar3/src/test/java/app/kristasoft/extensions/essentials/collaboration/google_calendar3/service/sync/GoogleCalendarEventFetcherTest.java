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

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.calendar.Calendar;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GoogleCalendarEventFetcherTest {
    private final GoogleCalendarEventFetcher fetcher = new GoogleCalendarEventFetcher(mock(CalendarSyncTelemetryService.class));

    private Calendar calendar(List<String> urls, String first, String second, boolean failSecond) {
        return new Calendar.Builder(new MockHttpTransport() {
            @Override public LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
                urls.add(url);
                boolean next = url.contains("pageToken=");
                if (next && failSecond) throw new IOException("page two unavailable");
                return new MockLowLevelHttpRequest().setResponse(new MockLowLevelHttpResponse()
                        .setStatusCode(200).setContentType("application/json").setContent(next ? second : first));
            }
        }, GsonFactory.getDefaultInstance(), null).setApplicationName("test").build();
    }

    @Test void emptyPageWithContinuationStillFetchesTheNextPage() {
        List<String> urls = new ArrayList<>();
        var events = fetcher.fetchEventsForDays(calendar(urls,
                "{\"items\":[],\"nextPageToken\":\"page2\"}",
                "{\"items\":[{\"id\":\"on-page-two\"}]}", false), 30);
        assertEquals(List.of("on-page-two"), events.stream().map(e -> e.getId()).toList());
        assertEquals(2, urls.size());
    }

    @Test void failedSecondPageNeverReturnsPartialSnapshotForDeletion() {
        assertThrows(IllegalStateException.class, () -> fetcher.fetchEventsForDays(calendar(new ArrayList<>(),
                "{\"items\":[{\"id\":\"first\"}],\"nextPageToken\":\"page2\"}", "{}", true), 30));
    }

    @Test void emptyCompleteCalendarIsAValidSnapshot() {
        assertTrue(fetcher.fetchEventsForDays(calendar(new ArrayList<>(), "{\"items\":[]}", "{}", false), 30).isEmpty());
    }
}
