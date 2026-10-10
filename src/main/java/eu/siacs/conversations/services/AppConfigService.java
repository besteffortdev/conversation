package eu.siacs.conversations.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import com.google.common.io.BaseEncoding;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.BuildConfig;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the managed configuration ({@link AppConfig}) an MDM sets, when the service starts and
 * whenever Android announces a change: the managed settings go into the preferences (and settings
 * no longer managed back to their defaults), the managed CA certificates into the trust store, and
 * the managed account is added or updated. See docs/app-config.md.
 */
public class AppConfigService {

    /** Aliases of the managed CA certificates in the {@link MemorizingTrustManager}'s store. */
    public static final String CERTIFICATE_ALIAS_PREFIX = "app_config:";

    private static final String STATE = "app_config";
    private static final String KEY_MANAGED_SETTINGS = "managed_settings";

    private static final Set<String> RECONNECT =
            Set.of(
                    AppSettings.USE_TOR,
                    AppSettings.SHOW_CONNECTION_OPTIONS,
                    AppSettings.TRUST_SYSTEM_CA_STORE,
                    AppSettings.REQUIRE_CHANNEL_BINDING,
                    AppSettings.REQUIRE_TLS_V1_3);
    private static final Set<String> PRESENCE =
            Set.of(
                    AppSettings.AWAY_WHEN_SCREEN_IS_OFF,
                    AppSettings.MANUALLY_CHANGE_PRESENCE,
                    AppSettings.DND_SYNC_SYSTEM,
                    AppSettings.DND_INCLUDE_SILENT_MODES,
                    AppSettings.READ_RECEIPTS,
                    AppSettings.BROADCAST_LAST_ACTIVITY,
                    AppSettings.ALLOW_MESSAGE_CORRECTION);

    private final XmppConnectionService service;

    private final BroadcastReceiver receiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    Log.d(Config.LOGTAG, "app config: " + intent.getAction());
                    refresh(false);
                }
            };

    // held here: SharedPreferences keeps listeners only weakly
    private final SharedPreferences.OnSharedPreferenceChangeListener lock =
            (preferences, key) -> {
                if (key != null) {
                    enforce(preferences, key);
                }
            };

    public AppConfigService(final XmppConnectionService service) {
        this.service = service;
    }

    /** Main thread, once the accounts are loaded. */
    void start() {
        refresh(true);
        PreferenceManager.getDefaultSharedPreferences(service)
                .registerOnSharedPreferenceChangeListener(lock);
        final IntentFilter filter =
                new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);
        if (BuildConfig.DEBUG) {
            filter.addAction(AppConfig.ACTION_DEBUG_CHANGED);
        }
        ContextCompat.registerReceiver(
                service, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    void stop() {
        PreferenceManager.getDefaultSharedPreferences(service)
                .unregisterOnSharedPreferenceChangeListener(lock);
        try {
            service.unregisterReceiver(receiver);
        } catch (final IllegalArgumentException e) {
            Log.d(Config.LOGTAG, "app config receiver was not registered");
        }
    }

    /** Puts the managed values into an account about to be saved. */
    public void enforce(final Account account) {
        if (AppConfig.get(service).applyTo(account)) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": app config values put back into account");
        }
    }

    /**
     * Reads the configuration again and applies it. While the service starts, nothing is connected
     * or shown yet: it skips what only running connections need.
     */
    private synchronized void refresh(final boolean starting) {
        final AppConfig config = AppConfig.read(service);
        if (!config.isEmpty()) {
            Log.d(Config.LOGTAG, "app config: managed settings " + config.getSettings().keySet());
        }
        final Set<String> changed = applySettings(config);
        if (changed.contains(AppSettings.OMEMO)) {
            OmemoSetting.load(service);
        }
        if (changed.contains(AppSettings.TRUST_SYSTEM_CA_STORE)) {
            service.updateMemorizingTrustManager();
        }
        final boolean trustChanged = applyCertificates(config.getCertificates());
        if (!starting) {
            if (changed.contains(AppSettings.AWAY_WHEN_SCREEN_IS_OFF)
                    || changed.contains(AppSettings.MANUALLY_CHANGE_PRESENCE)) {
                service.toggleScreenEventReceiver();
            }
            if (!Collections.disjoint(changed, PRESENCE)) {
                service.refreshAllPresences();
            }
            if (changed.contains(AppSettings.USE_TOR)) {
                service.reinitializeMuclumbusService();
            }
            if (changed.contains(AppSettings.AUTOMATIC_MESSAGE_DELETION)) {
                service.expireOldMessages(true);
            }
        }
        final boolean reconnect =
                !starting && (trustChanged || !Collections.disjoint(changed, RECONNECT));
        applyAccounts(config, reconnect);
    }

    // --- settings ---

    /** Returns the keys whose value changed. */
    private Set<String> applySettings(final AppConfig config) {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(service);
        final SharedPreferences state = service.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        final Set<String> previous =
                state.getStringSet(KEY_MANAGED_SETTINGS, Collections.emptySet());
        final Map<String, Object> settings = config.getSettings();
        final Set<String> changed = new HashSet<>();
        final SharedPreferences.Editor editor = preferences.edit();
        for (final String key : previous) {
            // no longer managed: back to the default
            if (!settings.containsKey(key) && preferences.contains(key)) {
                editor.remove(key);
                changed.add(key);
            }
        }
        for (final Map.Entry<String, Object> entry : settings.entrySet()) {
            if (!matches(preferences, entry.getKey(), entry.getValue())) {
                put(editor, entry.getKey(), entry.getValue());
                changed.add(entry.getKey());
            }
        }
        if (!changed.isEmpty()) {
            Log.d(Config.LOGTAG, "app config: settings changed " + changed);
            editor.apply();
        }
        if (!previous.equals(settings.keySet())) {
            state.edit()
                    .putStringSet(KEY_MANAGED_SETTINGS, new HashSet<>(settings.keySet()))
                    .apply();
        }
        return changed;
    }

    /** Puts back the managed value of {@code key} when something else changed it. */
    private void enforce(final SharedPreferences preferences, final String key) {
        final Object value = AppConfig.get(service).getSettings().get(key);
        if (value == null || matches(preferences, key, value)) {
            return;
        }
        Log.d(Config.LOGTAG, "app config: " + key + " is managed, change undone");
        final SharedPreferences.Editor editor = preferences.edit();
        put(editor, key, value);
        editor.apply();
    }

    private static boolean matches(
            final SharedPreferences preferences, final String key, final Object value) {
        try {
            if (value instanceof Boolean bool) {
                return preferences.contains(key) && preferences.getBoolean(key, false) == bool;
            }
            return value.equals(preferences.getString(key, null));
        } catch (final ClassCastException e) {
            // stored as another type: rewritten
            return false;
        }
    }

    private static void put(
            final SharedPreferences.Editor editor, final String key, final Object value) {
        if (value instanceof Boolean bool) {
            editor.putBoolean(key, bool);
        } else {
            editor.putString(key, String.valueOf(value));
        }
    }

    // --- trust ---

    /** Stores the managed CA certificates as trust anchors; true if that changed them. */
    private boolean applyCertificates(final List<X509Certificate> certificates) {
        final MemorizingTrustManager trustManager = service.getMemorizingTrustManager();
        final Map<String, X509Certificate> wanted = new HashMap<>();
        for (final X509Certificate certificate : certificates) {
            final String alias = alias(certificate);
            if (alias != null) {
                wanted.put(alias, certificate);
            }
        }
        final List<String> stored = Collections.list(trustManager.getCertificates());
        boolean changed = false;
        for (final String alias : stored) {
            if (alias.startsWith(CERTIFICATE_ALIAS_PREFIX) && !wanted.containsKey(alias)) {
                try {
                    trustManager.deleteCertificate(alias);
                    changed = true;
                    Log.d(Config.LOGTAG, "app config: removed CA certificate " + alias);
                } catch (final KeyStoreException e) {
                    Log.w(Config.LOGTAG, "app config: unable to remove " + alias, e);
                }
            }
        }
        for (final Map.Entry<String, X509Certificate> entry : wanted.entrySet()) {
            if (!stored.contains(entry.getKey())) {
                trustManager.storeCert(entry.getKey(), entry.getValue());
                changed = true;
                Log.d(
                        Config.LOGTAG,
                        "app config: trusting CA certificate "
                                + entry.getValue().getSubjectX500Principal());
            }
        }
        return changed;
    }

    /** Named after the certificate's SHA-256: a new certificate is a new entry. */
    private static String alias(final X509Certificate certificate) {
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return CERTIFICATE_ALIAS_PREFIX + BaseEncoding.base16().lowerCase().encode(digest);
        } catch (final CertificateEncodingException | NoSuchAlgorithmException e) {
            Log.w(Config.LOGTAG, "app config: unable to read a CA certificate", e);
            return null;
        }
    }

    // --- accounts ---

    private void applyAccounts(final AppConfig config, final boolean reconnect) {
        if (!QuickConversationsService.isConversations()) {
            return;
        }
        final Jid jid = config.getJid();
        if (jid != null && config.getPassword() != null && service.findAccountByJid(jid) == null) {
            Log.d(Config.LOGTAG, jid + ": adding the account set by the app config");
            // createAccount() puts in the managed server and state
            service.createAccount(new Account(jid, config.getPassword()));
        }
        for (final Account account : service.getAccounts()) {
            final boolean wasEnabled = account.isEnabled();
            if (config.applyTo(account)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": updated by the app config");
                if (!wasEnabled && account.isEnabled()) {
                    account.getXmppConnection().resetEverything();
                }
                service.updateAccount(account);
            } else if (reconnect && account.isEnabled()) {
                service.reconnectAccountInBackground(account);
            }
        }
    }
}
