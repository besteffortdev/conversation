package eu.siacs.conversations.services;

import android.content.Context;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The managed configuration ("app config") an MDM such as SOTI MobiControl or Intune sets for this
 * app, as declared in res/xml/app_restrictions.xml: Conversations settings, which the user then
 * can't change, and an XMPP account with its server. An empty or "unset" value isn't managed.
 * Immutable. See docs/app-config.md.
 */
public final class AppConfig {

    public static final String KEY_DOMAIN = "xmpp_domain";
    public static final String KEY_HOST = "xmpp_host";
    public static final String KEY_PORT = "xmpp_port";
    public static final String KEY_USERNAME = "xmpp_username";
    public static final String KEY_PASSWORD = "xmpp_password";
    public static final String KEY_TRUSTED_CA_CERTIFICATE = "xmpp_trusted_ca_certificate";
    public static final String KEY_ENABLED = "xmpp_enabled";

    /** Debug builds: values set with adb (AppConfigDebugReceiver), added to the MDM's. */
    static final String DEBUG_PREFERENCES = "app_config_debug";

    /** Sent within the app when the debug values change. */
    static final String ACTION_DEBUG_CHANGED = BuildConfig.APPLICATION_ID + ".APP_CONFIG_CHANGED";

    /** The schema's choice for "not managed". An empty value means the same. */
    private static final String UNSET = "unset";

    private static final int DEFAULT_PORT = 5222;

    private static final Pattern PEM =
            Pattern.compile(
                    "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);

    /** Whitespace, and line breaks a text field kept as {@code \n}: never Base64. */
    private static final Pattern NOT_BASE64 = Pattern.compile("\\\\[nr]|\\s");

    private enum Type {
        BOOLEAN,
        NUMBER,
        CHOICE
    }

    private record Spec(Type type, ImmutableSet<String> choices) {

        static Spec of(final Type type, final String... choices) {
            return new Spec(type, ImmutableSet.copyOf(choices));
        }
    }

    /** The Conversations settings an MDM may set, by preference key. */
    private static final ImmutableMap<String, Spec> SETTINGS;

    static {
        final ImmutableMap.Builder<String, Spec> builder = new ImmutableMap.Builder<>();
        for (final String key :
                new String[] {
                    // privacy
                    "confirm_messages",
                    "chat_states",
                    "last_activity",
                    "entity_time",
                    "allow_message_correction",
                    "accept_invites_from_strangers",
                    "use_relays",
                    "send_crash_reports",
                    // security and connection
                    "btbv",
                    "trust_system_ca_store",
                    "require_tls_v1_3",
                    "channel_binding_required",
                    "use_tor",
                    "show_connection_options",
                    // availability
                    "manually_change_presence",
                    "away_when_screen_off",
                    "dnd_on_silent_mode",
                    "treat_vibrate_as_silent",
                    // interface
                    "show_dynamic_tags",
                    "scroll_to_bottom",
                    "start_searching",
                    "display_enter_key",
                    "enter_is_send",
                    "use_green_background",
                    "large_font",
                    "align_start",
                    "show_avatars",
                    "show_avatars_accounts",
                    "allow_screenshots",
                    // attachments and notifications
                    "auto_send_recording",
                    "notifications_from_strangers",
                    "enable_foreground_service"
                }) {
            builder.put(key, Spec.of(Type.BOOLEAN));
        }
        builder.put("omemo", Spec.of(Type.CHOICE, "always", "default_on", "default_off"));
        builder.put("picture_compression", Spec.of(Type.CHOICE, "never", "auto", "always"));
        builder.put(
                "video_compression",
                Spec.of(Type.CHOICE, "360", "480", "720", "1080", "uncompressed"));
        builder.put(
                "channel_discovery_method", Spec.of(Type.CHOICE, "JABBER_NETWORK", "LOCAL_SERVER"));
        // bytes, seconds, seconds
        builder.put("auto_accept_file_size", Spec.of(Type.NUMBER));
        builder.put("grace_period_length", Spec.of(Type.NUMBER));
        builder.put("automatic_message_deletion", Spec.of(Type.NUMBER));
        SETTINGS = builder.buildOrThrow();
    }

    public static final AppConfig NONE =
            new AppConfig(ImmutableMap.of(), null, null, DEFAULT_PORT, null, null, null, List.of());

    private static volatile AppConfig current;

    /** Preference key to a Boolean or a String. */
    private final ImmutableMap<String, Object> settings;

    /** The server whose host and port are managed; null if none is. */
    @Nullable private final Jid domain;

    /** Empty: looked up in DNS. Only with a domain. */
    @Nullable private final String host;

    private final int port;

    /** The account the MDM sets up; null if it doesn't. */
    @Nullable private final Jid jid;

    @Nullable private final String password;

    /** Whether the managed accounts connect; null if the user decides. */
    @Nullable private final Boolean enabled;

    private final ImmutableList<X509Certificate> certificates;

    private AppConfig(
            final ImmutableMap<String, Object> settings,
            @Nullable final Jid domain,
            @Nullable final String host,
            final int port,
            @Nullable final Jid jid,
            @Nullable final String password,
            @Nullable final Boolean enabled,
            final List<X509Certificate> certificates) {
        this.settings = settings;
        this.domain = domain;
        this.host = host;
        this.port = port;
        this.jid = jid;
        this.password = password;
        this.enabled = enabled;
        this.certificates = ImmutableList.copyOf(certificates);
    }

    /** The configuration read last; read now if it hasn't been yet. */
    @NonNull
    public static AppConfig get(final Context context) {
        final AppConfig config = current;
        return config != null ? config : read(context);
    }

    /** Reads the MDM's values again. A binder call. */
    @NonNull
    static AppConfig read(final Context context) {
        final RestrictionsManager restrictionsManager =
                context.getSystemService(RestrictionsManager.class);
        final Bundle bundle =
                restrictionsManager == null
                        ? new Bundle()
                        : restrictionsManager.getApplicationRestrictions();
        if (BuildConfig.DEBUG) {
            addDebugValues(context, bundle);
        }
        final AppConfig config = of(bundle);
        current = config;
        return config;
    }

    private static void addDebugValues(final Context context, final Bundle bundle) {
        final SharedPreferences preferences =
                context.getSharedPreferences(DEBUG_PREFERENCES, Context.MODE_PRIVATE);
        for (final Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof Boolean value) {
                bundle.putBoolean(entry.getKey(), value);
            } else if (entry.getValue() instanceof Integer value) {
                bundle.putInt(entry.getKey(), value);
            } else if (entry.getValue() != null) {
                bundle.putString(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
    }

    /** Checks the MDM's values. Invalid ones are logged and left out. */
    @NonNull
    @VisibleForTesting
    @SuppressWarnings("deprecation") // Bundle.get: the values come in several types
    static AppConfig of(final Bundle bundle) {
        final Map<String, Object> settings = new HashMap<>();
        String domainText = null;
        String host = null;
        String portText = null;
        String username = null;
        String password = null;
        String certificateText = null;
        Boolean enabled = null;
        for (final String key : bundle.keySet()) {
            final Object raw = bundle.get(key);
            final String text = raw == null ? "" : String.valueOf(raw).trim();
            if (text.isEmpty() || UNSET.equalsIgnoreCase(text)) {
                continue;
            }
            switch (key) {
                case KEY_DOMAIN -> domainText = text;
                case KEY_HOST -> host = text;
                case KEY_PORT -> portText = text;
                case KEY_USERNAME -> username = text;
                case KEY_PASSWORD -> password = String.valueOf(raw);
                case KEY_TRUSTED_CA_CERTIFICATE -> certificateText = text;
                case KEY_ENABLED -> {
                    enabled = parseBoolean(raw, text);
                    if (enabled == null) {
                        Log.w(Config.LOGTAG, "app config: invalid value for " + key);
                    }
                }
                default -> {
                    final Spec spec = SETTINGS.get(key);
                    if (spec == null) {
                        Log.w(Config.LOGTAG, "app config: not a managed setting: " + key);
                        continue;
                    }
                    final Object value = parse(spec, raw, text);
                    if (value == null) {
                        Log.w(Config.LOGTAG, "app config: invalid value for " + key);
                    } else {
                        settings.put(key, value);
                    }
                }
            }
        }

        Jid domain = null;
        if (domainText != null) {
            domain = parseDomain(domainText);
            if (domain == null) {
                Log.w(Config.LOGTAG, "app config: invalid value for " + KEY_DOMAIN);
            }
        }
        Jid jid = null;
        if (username != null) {
            jid = parseAddress(username, domain);
            if (jid == null) {
                Log.w(Config.LOGTAG, "app config: no XMPP address in " + KEY_USERNAME);
            }
        }
        if (domain == null && jid != null) {
            domain = jid.getDomain();
        } else if (domain != null && jid != null && !domain.equals(jid.getDomain())) {
            Log.w(Config.LOGTAG, "app config: the managed account isn't on " + domain);
        }
        if (password != null && jid == null) {
            Log.w(Config.LOGTAG, "app config: " + KEY_PASSWORD + " without " + KEY_USERNAME);
            password = null;
        }

        String managedHost = null;
        int port = DEFAULT_PORT;
        if (domain != null) {
            managedHost = host == null ? "" : host;
            if (CharMatcher.whitespace().matchesAnyOf(managedHost)) {
                Log.w(Config.LOGTAG, "app config: invalid value for " + KEY_HOST);
                managedHost = "";
            }
            if (portText != null) {
                final Integer parsed = parsePort(portText);
                if (parsed == null) {
                    Log.w(Config.LOGTAG, "app config: invalid value for " + KEY_PORT);
                } else {
                    port = parsed;
                }
            }
            if (!managedHost.isEmpty()) {
                // Conversations connects to an account's host only with this on
                if (Boolean.FALSE.equals(settings.get(AppSettings.SHOW_CONNECTION_OPTIONS))) {
                    Log.w(
                            Config.LOGTAG,
                            "app config: "
                                    + AppSettings.SHOW_CONNECTION_OPTIONS
                                    + " stays on for "
                                    + KEY_HOST);
                }
                settings.put(AppSettings.SHOW_CONNECTION_OPTIONS, Boolean.TRUE);
            }
        } else if (host != null || portText != null) {
            Log.w(Config.LOGTAG, "app config: a server host or port without " + KEY_DOMAIN);
        }
        if (enabled != null && domain == null) {
            Log.w(Config.LOGTAG, "app config: " + KEY_ENABLED + " without an account to apply to");
            enabled = null;
        }

        List<X509Certificate> certificates = List.of();
        if (certificateText != null) {
            final List<X509Certificate> parsed = parseCertificates(certificateText);
            if (parsed == null) {
                Log.w(Config.LOGTAG, "app config: invalid value for " + KEY_TRUSTED_CA_CERTIFICATE);
            } else {
                certificates = parsed;
            }
        }

        return new AppConfig(
                ImmutableMap.copyOf(settings),
                domain,
                managedHost,
                port,
                jid,
                password,
                enabled,
                certificates);
    }

    @Nullable
    private static Object parse(final Spec spec, final Object raw, final String text) {
        return switch (spec.type()) {
            case BOOLEAN -> parseBoolean(raw, text);
            case CHOICE -> spec.choices().contains(text) ? text : null;
            case NUMBER -> {
                try {
                    yield Long.parseLong(text) >= 0 ? text : null;
                } catch (final NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    @Nullable
    private static Boolean parseBoolean(final Object raw, final String text) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.valueOf(text.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    @Nullable
    private static Integer parsePort(final String text) {
        try {
            final int port = Integer.parseInt(text);
            return port > 0 && port < 65536 ? port : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Jid parseDomain(final String text) {
        try {
            final Jid jid = Jid.ofUserInput(text);
            return jid.isDomainJid() ? jid : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /** username@domain, or the username itself when it is an address. */
    @Nullable
    private static Jid parseAddress(final String username, @Nullable final Jid domain) {
        final String address;
        if (username.indexOf('@') >= 0) {
            address = username;
        } else if (domain != null) {
            address = username + "@" + domain;
        } else {
            return null;
        }
        try {
            final Jid jid = Jid.ofUserInput(address).asBareJid();
            return jid.getLocal() == null ? null : jid;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The certificates in {@code text}: PEM blocks, however an MDM's text field broke their lines,
     * or one certificate in Base64 DER. Null if any isn't a certificate.
     */
    @Nullable
    @VisibleForTesting
    static List<X509Certificate> parseCertificates(final String text) {
        final List<String> bodies = new ArrayList<>();
        final Matcher matcher = PEM.matcher(text);
        while (matcher.find()) {
            bodies.add(matcher.group(1));
        }
        if (bodies.isEmpty()) {
            bodies.add(text);
        }
        final List<X509Certificate> certificates = new ArrayList<>();
        try {
            final CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (final String body : bodies) {
                final byte[] der =
                        Base64.decode(NOT_BASE64.matcher(body).replaceAll(""), Base64.DEFAULT);
                certificates.add(
                        (X509Certificate)
                                factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (final IllegalArgumentException | CertificateException | ClassCastException e) {
            Log.w(Config.LOGTAG, "app config: not a certificate", e);
            return null;
        }
        return certificates;
    }

    // --- what the MDM sets ---

    public boolean isEmpty() {
        return settings.isEmpty() && domain == null && certificates.isEmpty();
    }

    /** The managed settings: preference key to a Boolean or a String. */
    public ImmutableMap<String, Object> getSettings() {
        return settings;
    }

    /** Whether the MDM sets this preference, so the user can't change it. */
    public boolean isManaged(final String key) {
        return settings.containsKey(key);
    }

    /** The account the MDM sets up; null if it doesn't. */
    @Nullable
    public Jid getJid() {
        return jid;
    }

    /** The password of {@link #getJid()}; null if the user types it. */
    @Nullable
    public String getPassword() {
        return password;
    }

    public List<X509Certificate> getCertificates() {
        return certificates;
    }

    /** Whether {@code account} is the one the MDM sets up. The user can't delete it. */
    public boolean managesAccount(@Nullable final Account account) {
        return account != null && jid != null && jid.equals(account.getJid().asBareJid());
    }

    /** Whether the MDM sets the password of {@code account}. */
    public boolean managesPassword(@Nullable final Account account) {
        return password != null && managesAccount(account);
    }

    /** Whether the MDM sets the server host and port of {@code account}. */
    public boolean managesServerOf(@Nullable final Account account) {
        return account != null && domain != null && domain.equals(account.getDomain());
    }

    /** Whether the MDM decides if {@code account} connects. */
    public boolean managesEnabled(@Nullable final Account account) {
        return enabled != null && (managesAccount(account) || managesServerOf(account));
    }

    /** Puts the managed values into {@code account}; true if that changed it. */
    public boolean applyTo(final Account account) {
        boolean changed = false;
        if (managesServerOf(account) && host != null) {
            if (!host.equals(account.getHostname())) {
                account.setHostname(host);
                changed = true;
            }
            if (account.getPort() != port) {
                account.setPort(port);
                changed = true;
            }
        }
        if (managesPassword(account) && !password.equals(account.getPassword())) {
            account.setPassword(password);
            changed = true;
        }
        if (managesEnabled(account) && account.isEnabled() != enabled) {
            account.setOption(Account.OPTION_DISABLED, !enabled);
            changed = true;
        }
        return changed;
    }

    /** Every setting key an MDM may set, for tests. */
    @VisibleForTesting
    static ImmutableSet<String> supportedSettings() {
        return SETTINGS.keySet();
    }
}
