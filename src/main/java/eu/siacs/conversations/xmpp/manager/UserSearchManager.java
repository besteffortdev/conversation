package eu.siacs.conversations.xmpp.manager;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.data.Data;
import im.conversations.android.xmpp.model.data.Field;
import im.conversations.android.xmpp.model.search.Email;
import im.conversations.android.xmpp.model.search.First;
import im.conversations.android.xmpp.model.search.Last;
import im.conversations.android.xmpp.model.search.Nick;
import im.conversations.android.xmpp.model.search.Search;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * XEP-0055: Jabber Search. Finds users in the directory of the account's server, for example
 * Openfire's search.example.com or ejabberd's vjud.example.com.
 */
public class UserSearchManager extends AbstractManager {

    // Openfire's search form has a single free text field plus checkboxes picking the user
    // attributes it is matched against
    private static final String FIELD_SEARCH = "search";

    // other data forms (ejabberd) AND every field they receive, so each of these is queried on
    // its own and the results merged
    private static final List<String> PREFERRED_FIELDS =
            Arrays.asList("user", "username", "name", "fn", "nick", "email", "first", "last");
    private static final int MAX_QUERIES = 4;

    private final Map<Jid, Search> forms = new HashMap<>();

    public UserSearchManager(final Context context, final XmppConnection connection) {
        super(context, connection);
    }

    public Jid getService() {
        final var services =
                getManager(DiscoManager.class).findDiscoItemsByFeature(Namespace.SEARCH);
        Jid fallback = null;
        for (final var entry : services.entrySet()) {
            final var info = entry.getValue();
            if (info.hasIdentityWithCategoryAndType("directory", "user")) {
                return entry.getKey();
            }
            // Openfire's MUC service also answers jabber:iq:search, but it searches rooms
            if (fallback == null && !info.hasIdentityWithCategory("conference")) {
                fallback = entry.getKey();
            }
        }
        return fallback;
    }

    public boolean hasFeature() {
        return getService() != null;
    }

    public ListenableFuture<List<Result>> search(final String term) {
        final var service = getService();
        if (service == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("No user search service available"));
        }
        final var future =
                Futures.transformAsync(
                        getForm(service),
                        form -> search(service, form, term),
                        MoreExecutors.directExecutor());
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final List<Result> results) {}

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        // fetch the form again next time in case the service changed it
                        synchronized (forms) {
                            forms.remove(service);
                        }
                        Log.d(
                                Config.LOGTAG,
                                getAccount().getJid().asBareJid()
                                        + ": user search on "
                                        + service
                                        + " failed",
                                throwable);
                    }
                },
                MoreExecutors.directExecutor());
        return future;
    }

    private ListenableFuture<Search> getForm(final Jid service) {
        synchronized (this.forms) {
            final var form = this.forms.get(service);
            if (form != null) {
                return Futures.immediateFuture(form);
            }
        }
        final var iq = new Iq(Iq.Type.GET);
        iq.setTo(service);
        iq.addExtension(new Search());
        return Futures.transform(
                connection.sendIqPacket(iq),
                response -> {
                    final var form = response.getExtension(Search.class);
                    if (form == null) {
                        throw new IllegalStateException("Service did not return a search form");
                    }
                    synchronized (this.forms) {
                        this.forms.put(service, form);
                    }
                    return form;
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<List<Result>> search(
            final Jid service, final Search form, final String term) {
        final var queries = buildQueries(form, term);
        if (queries.isEmpty()) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("Search form has no field to search with"));
        }
        final List<ListenableFuture<List<Result>>> futures =
                ImmutableList.copyOf(
                        Lists.transform(
                                queries,
                                query -> {
                                    final var iq = new Iq(Iq.Type.SET);
                                    iq.setTo(service);
                                    iq.addExtension(query);
                                    return Futures.transform(
                                            connection.sendIqPacket(iq),
                                            response -> parse(response.getExtension(Search.class)),
                                            MoreExecutors.directExecutor());
                                }));
        // when a term is spread over several fields, one of them being refused is fine
        return Futures.transformAsync(
                Futures.successfulAsList(futures),
                lists -> {
                    if (Iterables.all(lists, Objects::isNull)) {
                        // all of them failed; pass on the first error
                        return futures.get(0);
                    }
                    return Futures.immediateFuture(merge(lists));
                },
                MoreExecutors.directExecutor());
    }

    @VisibleForTesting
    static List<Search> buildQueries(final Search form, final String term) {
        final var data = form.getData();
        final var queries = new ImmutableList.Builder<Search>();
        if (data != null) {
            if (data.getFieldByName(FIELD_SEARCH) != null) {
                queries.add(
                        query(data.submit(ImmutableMap.<String, Object>of(FIELD_SEARCH, term))));
            } else {
                // ejabberd only matches prefixes when the value ends with a wildcard
                final var value = term.endsWith("*") ? term : term + "*";
                for (final var field : fieldsToSearch(data)) {
                    queries.add(
                            query(
                                    Data.of(
                                            ImmutableMap.<String, Object>of(field, value),
                                            data.getFormType())));
                }
            }
        } else {
            for (final var field :
                    Arrays.asList(new Nick(), new First(), new Last(), new Email())) {
                if (form.hasExtension(field.getClass())) {
                    field.setContent(term);
                    queries.add(query(field));
                }
            }
        }
        return queries.build();
    }

    private static List<String> fieldsToSearch(final Data data) {
        final var textFields = new ArrayList<String>();
        for (final var field : data.getFields()) {
            final var name = field.getFieldName();
            final var type = field.getType();
            // text-single is the default type
            if (name != null && (type == null || type == Field.Type.TEXT_SINGLE)) {
                textFields.add(name);
            }
        }
        final var fields = new ArrayList<String>();
        for (final var preferred : PREFERRED_FIELDS) {
            for (final var name : textFields) {
                if (preferred.equalsIgnoreCase(name)) {
                    fields.add(name);
                }
            }
        }
        return ImmutableList.copyOf(
                Iterables.limit(fields.isEmpty() ? textFields : fields, MAX_QUERIES));
    }

    private static Search query(final Extension field) {
        final var search = new Search();
        search.addExtension(field);
        return search;
    }

    @VisibleForTesting
    static List<Result> parse(final Search search) {
        if (search == null) {
            return ImmutableList.of();
        }
        final var results = new ImmutableList.Builder<Result>();
        final var data = search.getData();
        if (data != null) {
            for (final var item :
                    data.getExtensions(im.conversations.android.xmpp.model.data.Item.class)) {
                final var values = new HashMap<String, String>();
                for (final var field : item.getExtensions(Field.class)) {
                    final var name = field.getFieldName();
                    final var value = field.getValue();
                    if (name != null && !Strings.isNullOrEmpty(value)) {
                        values.putIfAbsent(name.toLowerCase(Locale.ROOT), value);
                    }
                }
                final var address = parseJid(values.get("jid"));
                if (address == null) {
                    continue;
                }
                results.add(
                        new Result(
                                address,
                                firstNonEmpty(
                                        values.get("name"),
                                        values.get("fn"),
                                        join(values.get("first"), values.get("last")),
                                        values.get("nick")),
                                values.get("email")));
            }
        } else {
            for (final var item : search.getItems()) {
                final var address = parseJid(item.getAttribute("jid"));
                if (address == null) {
                    continue;
                }
                results.add(
                        new Result(
                                address,
                                firstNonEmpty(
                                        join(item.getFirst(), item.getLast()), item.getNick()),
                                item.getEmail()));
            }
        }
        return results.build();
    }

    @VisibleForTesting
    static List<Result> merge(final Collection<List<Result>> lists) {
        final var merged = new LinkedHashMap<Jid, Result>();
        for (final var list : lists) {
            if (list == null) {
                continue;
            }
            for (final var result : list) {
                final var existing = merged.get(result.address());
                if (existing == null || (existing.name() == null && result.name() != null)) {
                    merged.put(result.address(), result);
                }
            }
        }
        final var results = new ArrayList<>(merged.values());
        results.sort(Comparator.comparing(r -> r.getDisplayName().toLowerCase(Locale.ROOT)));
        return results;
    }

    private static Jid parseJid(final String value) {
        if (Strings.isNullOrEmpty(value)) {
            return null;
        }
        try {
            final var jid = Jid.of(value).asBareJid();
            return jid.isDomainJid() ? null : jid;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static String join(final String first, final String last) {
        return Strings.emptyToNull(
                Joiner.on(' ')
                        .skipNulls()
                        .join(Strings.emptyToNull(first), Strings.emptyToNull(last)));
    }

    private static String firstNonEmpty(final String... values) {
        for (final var value : values) {
            if (!Strings.isNullOrEmpty(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record Result(Jid address, String name, String email) {

        public String getDisplayName() {
            return name == null ? address.toString() : name;
        }
    }
}
