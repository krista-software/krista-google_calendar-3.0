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

import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationException;
import app.kristasoft.extensions.essentials.collaboration.google_calendar3.impl.connectors.DomainWideDelegationProvider;
import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.User;
import com.google.api.services.directory.model.Users;
import org.jvnet.hk2.annotations.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * KIM-474 — enumerates the mailboxes of a customer's Google Workspace account via the Admin SDK
 * Directory API, so Always On can scan every calendar rather than only the calendars of users who
 * individually authenticated.
 *
 * <p>Runs on a delegated credential impersonating the configured Workspace admin
 * ({@link DomainWideDelegationProvider#directoryClientForAdmin()}) — {@code users.list} is an
 * admin-only API and a service account with no impersonated user cannot call it at all.
 *
 * <h3>Partial results are preferred to no results</h3>
 * A failure on the FIRST page means the delegation is not working, and the caller must degrade to
 * the consented set — so it is thrown. A failure on a LATER page means we already hold a usable
 * slice of the tenant; returning that slice with a loud WARN scans more calendars than falling back
 * would, and the next sweep retries from scratch. Silently returning a short list would be the
 * dangerous option, which is why it is logged at WARN with the page number.
 *
 * <h3>Thread safety</h3>
 * Stateless; each call builds its own client.
 */
@Service
public class TenantUserDirectory {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantUserDirectory.class);

    /** Directory's maximum page size for {@code users.list}. Fewer round-trips on a large tenant. */
    private static final int PAGE_SIZE = 500;

    /**
     * Hard stop on paging. At {@link #PAGE_SIZE} this is half a million mailboxes — far beyond any
     * real tenant — and exists only so a misbehaving {@code nextPageToken} cannot spin forever.
     */
    private static final int MAX_PAGES = 1_000;

    /** Lists every domain in the Workspace account; the documented alias for "this customer". */
    private static final String ALL_CUSTOMER_ACCOUNTS = "my_customer";

    /** Server-side filter, so suspended accounts never travel over the wire. */
    private static final String ACTIVE_USERS_QUERY = "isSuspended=false";

    /** Omits the (large) custom-schema payload we have no use for. */
    private static final String BASIC_PROJECTION = "basic";

    private final DomainWideDelegationProvider delegationProvider;

    /**
     * What one enumeration read.
     *
     * @param emails   de-duplicated primary emails, in Directory order
     * @param complete true only when paging reached the end of the Directory. A false here means
     *                 absence from {@code emails} proves nothing about a mailbox.
     */
    public record Listing(List<String> emails, boolean complete) {

        public Listing {
            emails = List.copyOf(emails);
        }

        /** @return a listing that read the whole Directory */
        public static Listing complete(List<String> emails) {
            return new Listing(emails, true);
        }
    }

    @Inject
    public TenantUserDirectory(DomainWideDelegationProvider delegationProvider) {
        this.delegationProvider = delegationProvider;
    }

    /**
     * Lists the primary email of every active mailbox in the tenant.
     *
     * @return de-duplicated primary emails, in Directory order; never null, possibly empty
     * @throws DomainWideDelegationException when the credential cannot be built, or the very first
     *                                       page fails — both mean tenant-wide discovery is not
     *                                       working and the caller must degrade
     */
    public List<String> listActiveUserEmails() throws DomainWideDelegationException {
        return listActiveUsers().emails();
    }

    /**
     * Lists every active mailbox and reports whether the listing is complete, for the caller that
     * has to treat a mailbox's absence as a fact.
     *
     * @return the listing; never null, possibly empty
     * @throws DomainWideDelegationException as {@link #listActiveUserEmails()}
     */
    public Listing listActiveUsers() throws DomainWideDelegationException {
        Directory directory = delegationProvider.directoryClientForAdmin();
        String domain = delegationProvider.getTenantDomain();

        Set<String> emails = new LinkedHashSet<>();
        String pageToken = null;
        int page = 0;
        int skipped = 0;
        boolean complete = false;

        while (page < MAX_PAGES) {
            Users response;
            final String currentPageToken = pageToken;
            final int pageNumber = page + 1;
            try {
                // KIM-474 (scale): enumerating a large Workspace account is the single call most
                // likely to be rate-limited, and losing it costs the whole sweep rather than one
                // mailbox — so every page is issued through the Retry-After-aware backoff.
                response = GoogleThrottleBackoff.execute("tenant enumeration page " + pageNumber,
                        () -> requestPage(directory, domain, currentPageToken).execute());
            } catch (IOException | RuntimeException cause) {
                if (page == 0) {
                    throw new DomainWideDelegationException(
                            "The Google Admin SDK refused to list the tenant's users"
                                    + (domain == null ? "" : " for domain '" + domain + "'") + ".",
                            DomainWideDelegationProvider.sanitized(cause));
                }
                LOGGER.warn("[GCAL-DISCOVERY] Tenant enumeration failed on page {} after collecting {} "
                                + "mailbox(es); continuing with the partial set. The next sweep retries "
                                + "from the start.",
                        page + 1, emails.size(), DomainWideDelegationProvider.sanitized(cause));
                break;
            }

            skipped += collect(response, emails);
            page++;

            pageToken = response.getNextPageToken();
            if (pageToken == null || pageToken.isBlank()) {
                complete = true;
                break;
            }
        }

        if (page >= MAX_PAGES && !complete) {
            LOGGER.warn("[GCAL-DISCOVERY] Stopped tenant enumeration at the {}-page safety limit with {} "
                    + "mailbox(es). Restrict the scan with the 'tenantDomain' attribute if the account "
                    + "really is this large.", MAX_PAGES, emails.size());
        }

        LOGGER.info("[GCAL-DISCOVERY] Directory enumeration complete: {} active mailbox(es) over {} page(s), "
                + "{} skipped (suspended, archived, or without a primary email), scope={}",
                emails.size(), page, skipped, domain == null ? "whole account" : "domain " + domain);
        return new Listing(List.copyOf(emails), complete);
    }

    /**
     * Builds one {@code users.list} request. {@code domain} and {@code customer} are mutually
     * exclusive on this API — passing both is a 400 — so exactly one is set.
     */
    private Directory.Users.List requestPage(Directory directory, String domain, String pageToken)
            throws IOException {
        Directory.Users.List request = directory.users().list()
                .setMaxResults(PAGE_SIZE)
                .setQuery(ACTIVE_USERS_QUERY)
                .setProjection(BASIC_PROJECTION)
                .setPageToken(pageToken);
        if (domain != null) {
            request.setDomain(domain);
        } else {
            request.setCustomer(ALL_CUSTOMER_ACCOUNTS);
        }
        return request;
    }

    /**
     * Adds the usable mailboxes from one page.
     *
     * <p>{@link #ACTIVE_USERS_QUERY} already excludes suspended accounts server-side; the
     * client-side re-check costs nothing and keeps the guarantee if that query is ever changed or
     * silently ignored by the API. Archived users have no live calendar to scan, so they are skipped
     * too.
     *
     * @return how many entries on the page were skipped
     */
    private int collect(Users response, Set<String> emails) {
        List<User> users = response.getUsers();
        if (users == null || users.isEmpty()) {
            return 0;
        }
        int skipped = 0;
        for (User user : users) {
            if (user == null) {
                skipped++;
                continue;
            }
            String email = user.getPrimaryEmail();
            if (email == null || email.isBlank()
                    || Boolean.TRUE.equals(user.getSuspended())
                    || Boolean.TRUE.equals(user.getArchived())) {
                skipped++;
                continue;
            }
            emails.add(email.trim().toLowerCase(Locale.ROOT));
        }
        return skipped;
    }
}
