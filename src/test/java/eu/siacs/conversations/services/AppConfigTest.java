package eu.siacs.conversations.services;

import android.os.Bundle;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** What the managed configuration (app config) an MDM sends becomes. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AppConfigTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private static final String CA_1 =
            """
            -----BEGIN CERTIFICATE-----
            MIIBUzCB+aADAgECAgkArIhKQyzWos0wCgYIKoZIzj0EAwMwFDESMBAGA1UEAxMJ
            VGVzdCBDQSAxMCAXDTI2MTAwOTIzMDgwNloYDzIxMjYwOTE1MjMwODA2WjAUMRIw
            EAYDVQQDEwlUZXN0IENBIDEwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASJdHqH
            /SONKIEPQpP6X4nan5s8YeG0UiDhFeE4qR1vazZNH0kOYwRmIqBC/KBeEFQk3Nv+
            dFveJJWI9sC8X2MRozIwMDAdBgNVHQ4EFgQUZboel3EGcmrLLrUWcsAJlR4NYMAw
            DwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAwNJADBGAiEAnsNePz/eLb6TdufF
            jMubFbbVxVuXbFfj3/KCS03XLooCIQCUilJTry26N5ePeFTqN8B4tIWX5RJpLQzI
            70S89KBuEQ==
            -----END CERTIFICATE-----
            """;

    private static final String CA_2 =
            """
            -----BEGIN CERTIFICATE-----
            MIIBUTCB+KADAgECAggayG+Yx50RPzAKBggqhkjOPQQDAzAUMRIwEAYDVQQDEwlU
            ZXN0IENBIDIwIBcNMjYxMDA5MjMwODA3WhgPMjEyNjA5MTUyMzA4MDdaMBQxEjAQ
            BgNVBAMTCVRlc3QgQ0EgMjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABG1q5VdL
            8I7/aqUoaOzmswH1yZx09cSD4ZUHwW79ZKyCZkPAs4CWKI7/GjHJ6J1KU131faJZ
            eWMFv+zPX1ICBeijMjAwMB0GA1UdDgQWBBRYp+RQ9y8dxNgN5sTqmtQC2+v2OzAP
            BgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMDA0gAMEUCIQDVImLSg81+xA7L9upD
            MoA6C8akbdworJPoAopUjcxjLgIgcMaJUxMj8Wh9uv7K4Zg0wpOml1j7dy5Bplom
            4P4zk+E=
            -----END CERTIFICATE-----
            """;

    private static Account account(final String jid) {
        return new Account(Jid.of(jid), "user password");
    }

    @Test
    public void nothingSetManagesNothing() {
        final AppConfig config = AppConfig.of(new Bundle());
        Assert.assertTrue(config.isEmpty());
        Assert.assertNull(config.getJid());
        final Account account = account("alice@example.com");
        Assert.assertFalse(config.applyTo(account));
        Assert.assertFalse(config.managesServerOf(account));
        Assert.assertFalse(config.managesEnabled(account));
    }

    @Test
    public void notManagedChoicesAndEmptyTextsAreLeftOut() {
        final Bundle bundle = new Bundle();
        bundle.putString("confirm_messages", "unset");
        bundle.putString("omemo", "");
        bundle.putString(AppConfig.KEY_DOMAIN, " ");
        bundle.putString(AppConfig.KEY_ENABLED, "unset");
        Assert.assertTrue(AppConfig.of(bundle).isEmpty());
    }

    @Test
    public void settingsAreTypedAndChecked() {
        final Bundle bundle = new Bundle();
        bundle.putString("confirm_messages", "false");
        bundle.putBoolean("chat_states", true);
        bundle.putString("last_activity", "maybe");
        bundle.putString("omemo", "always");
        bundle.putString("picture_compression", "sometimes");
        bundle.putInt("auto_accept_file_size", 1048576);
        bundle.putString("automatic_message_deletion", "-1");
        bundle.putString("grace_period_length", "sixty");
        bundle.putString("channel_discovery_method", "LOCAL_SERVER");
        bundle.putString("theme", "dark");
        final AppConfig config = AppConfig.of(bundle);
        Assert.assertEquals(Boolean.FALSE, config.getSettings().get("confirm_messages"));
        Assert.assertEquals(Boolean.TRUE, config.getSettings().get("chat_states"));
        Assert.assertEquals("always", config.getSettings().get("omemo"));
        Assert.assertEquals("1048576", config.getSettings().get("auto_accept_file_size"));
        Assert.assertEquals("LOCAL_SERVER", config.getSettings().get("channel_discovery_method"));
        Assert.assertFalse(config.isManaged("last_activity"));
        Assert.assertFalse(config.isManaged("picture_compression"));
        Assert.assertFalse(config.isManaged("automatic_message_deletion"));
        Assert.assertFalse(config.isManaged("grace_period_length"));
        Assert.assertFalse("not offered to an MDM", config.isManaged("theme"));
        Assert.assertEquals(5, config.getSettings().size());
    }

    @Test
    public void usernameAndDomainMakeTheAccount() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_DOMAIN, "Example.com");
        bundle.putString(AppConfig.KEY_USERNAME, "alice");
        bundle.putString(AppConfig.KEY_PASSWORD, " secret ");
        final AppConfig config = AppConfig.of(bundle);
        Assert.assertEquals(Jid.of("alice@example.com"), config.getJid());
        Assert.assertEquals("a password is kept as it is", " secret ", config.getPassword());
        Assert.assertTrue(config.managesAccount(account("alice@example.com")));
        Assert.assertTrue(config.managesPassword(account("alice@example.com")));
        Assert.assertFalse(config.managesAccount(account("bob@example.com")));
        Assert.assertTrue(config.managesServerOf(account("bob@example.com")));
        Assert.assertFalse(config.managesServerOf(account("bob@example.org")));
    }

    @Test
    public void aFullAddressAsUsernameNeedsNoDomain() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_USERNAME, "alice@example.org/phone");
        final AppConfig config = AppConfig.of(bundle);
        Assert.assertEquals(Jid.of("alice@example.org"), config.getJid());
        Assert.assertNull("no password: the user types it", config.getPassword());
        Assert.assertTrue(config.managesServerOf(account("bob@example.org")));
    }

    @Test
    public void aUsernameWithoutDomainOrAPasswordWithoutUsernameIsNoAccount() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_USERNAME, "alice");
        Assert.assertNull(AppConfig.of(bundle).getJid());

        final Bundle passwordOnly = new Bundle();
        passwordOnly.putString(AppConfig.KEY_DOMAIN, "example.com");
        passwordOnly.putString(AppConfig.KEY_PASSWORD, "secret");
        final AppConfig config = AppConfig.of(passwordOnly);
        Assert.assertNull(config.getJid());
        Assert.assertNull(config.getPassword());
    }

    @Test
    public void theDomainManagesHostAndPortAtTheirDefaults() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_DOMAIN, "example.com");
        final AppConfig config = AppConfig.of(bundle);
        final Account account = account("bob@example.com");
        account.setHostname("old.example.com");
        account.setPort(5223);
        Assert.assertTrue(config.applyTo(account));
        Assert.assertEquals("", account.getHostname());
        Assert.assertEquals(5222, account.getPort());
        Assert.assertEquals("user password", account.getPassword());
        Assert.assertFalse("already applied", config.applyTo(account));
        Assert.assertFalse("no host to connect to", config.isManaged("show_connection_options"));
    }

    @Test
    public void aHostTurnsOnTheConnectionSettings() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_DOMAIN, "example.com");
        bundle.putString(AppConfig.KEY_HOST, "xmpp.example.com");
        bundle.putString(AppConfig.KEY_PORT, "5223");
        bundle.putString("show_connection_options", "false");
        final AppConfig config = AppConfig.of(bundle);
        Assert.assertEquals(Boolean.TRUE, config.getSettings().get("show_connection_options"));
        final Account account = account("bob@example.com");
        Assert.assertTrue(config.applyTo(account));
        Assert.assertEquals("xmpp.example.com", account.getHostname());
        Assert.assertEquals(5223, account.getPort());
    }

    @Test
    public void anInvalidPortIsTheDefault() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_DOMAIN, "example.com");
        bundle.putString(AppConfig.KEY_HOST, "xmpp.example.com");
        bundle.putString(AppConfig.KEY_PORT, "70000");
        final Account account = account("bob@example.com");
        AppConfig.of(bundle).applyTo(account);
        Assert.assertEquals(5222, account.getPort());
    }

    @Test
    public void hostWithoutDomainIsIgnored() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_HOST, "xmpp.example.com");
        bundle.putBoolean(AppConfig.KEY_ENABLED, false);
        Assert.assertTrue(AppConfig.of(bundle).isEmpty());
    }

    @Test
    public void passwordAndStateApplyToTheManagedAccount() {
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_USERNAME, "alice@example.com");
        bundle.putString(AppConfig.KEY_PASSWORD, "secret");
        bundle.putString(AppConfig.KEY_ENABLED, "false");
        final AppConfig config = AppConfig.of(bundle);

        final Account alice = account("alice@example.com");
        Assert.assertTrue(config.applyTo(alice));
        Assert.assertEquals("secret", alice.getPassword());
        Assert.assertFalse(alice.isEnabled());

        final Account bob = account("bob@example.com");
        Assert.assertTrue("on the managed domain", config.managesEnabled(bob));
        Assert.assertFalse(config.managesPassword(bob));
        config.applyTo(bob);
        Assert.assertEquals("user password", bob.getPassword());
        Assert.assertFalse(bob.isEnabled());

        final Account carol = account("carol@example.org");
        Assert.assertFalse(config.applyTo(carol));
        Assert.assertTrue(carol.isEnabled());
    }

    @Test
    public void certificatesAsPem() {
        final List<X509Certificate> one = AppConfig.parseCertificates(CA_1);
        Assert.assertNotNull(one);
        Assert.assertEquals(1, one.size());
        Assert.assertEquals("CN=Test CA 1", one.get(0).getSubjectX500Principal().getName());

        final List<X509Certificate> two = AppConfig.parseCertificates(CA_1 + CA_2);
        Assert.assertNotNull(two);
        Assert.assertEquals(2, two.size());
        Assert.assertEquals("CN=Test CA 2", two.get(1).getSubjectX500Principal().getName());
    }

    @Test
    public void certificatesWithLineBreaksAConsoleChanged() {
        // a single-line text field: no line breaks, or kept as \n
        final List<X509Certificate> joined = AppConfig.parseCertificates(CA_1.replace("\n", ""));
        Assert.assertNotNull(joined);
        Assert.assertEquals(1, joined.size());
        final List<X509Certificate> escaped =
                AppConfig.parseCertificates(CA_1.replace("\n", "\\n"));
        Assert.assertNotNull(escaped);
        Assert.assertEquals(1, escaped.size());
    }

    @Test
    public void certificateAsBase64Der() {
        final String base64 =
                CA_2.replace("-----BEGIN CERTIFICATE-----", "")
                        .replace("-----END CERTIFICATE-----", "");
        final List<X509Certificate> certificates = AppConfig.parseCertificates(base64);
        Assert.assertNotNull(certificates);
        Assert.assertEquals(
                "CN=Test CA 2", certificates.get(0).getSubjectX500Principal().getName());
    }

    @Test
    public void notACertificate() {
        Assert.assertNull(AppConfig.parseCertificates("hello"));
        Assert.assertNull(AppConfig.parseCertificates(CA_1 + CA_2.replace("MIIB", "MIIC")));
        final Bundle bundle = new Bundle();
        bundle.putString(AppConfig.KEY_TRUSTED_CA_CERTIFICATE, "hello");
        Assert.assertTrue(AppConfig.of(bundle).getCertificates().isEmpty());
    }

    /** The schema an MDM console shows lists exactly what {@link AppConfig} reads. */
    @Test
    public void schemaMatchesWhatIsRead() throws Exception {
        final List<String> restrictions = keys("app_restrictions.xml", "restriction");
        final Set<String> keys = new HashSet<>(restrictions);
        Assert.assertEquals("a key twice", restrictions.size(), keys.size());
        final Set<String> expected = new HashSet<>(AppConfig.supportedSettings());
        expected.addAll(
                Set.of(
                        AppConfig.KEY_DOMAIN,
                        AppConfig.KEY_HOST,
                        AppConfig.KEY_PORT,
                        AppConfig.KEY_USERNAME,
                        AppConfig.KEY_PASSWORD,
                        AppConfig.KEY_TRUSTED_CA_CERTIFICATE,
                        AppConfig.KEY_ENABLED));
        Assert.assertEquals(expected, keys);
    }

    /** Every managed setting is on a settings screen, where it shows as managed. */
    @Test
    public void managedSettingsArePreferences() throws Exception {
        final Set<String> keys = new HashSet<>();
        for (final String screen :
                new String[] {
                    "preferences_attachments.xml",
                    "preferences_availability.xml",
                    "preferences_connection.xml",
                    "preferences_interface.xml",
                    "preferences_interface_bubbles.xml",
                    "preferences_notifications.xml",
                    "preferences_privacy.xml",
                    "preferences_security.xml"
                }) {
            keys.addAll(keys(screen, "*"));
        }
        for (final String setting : AppConfig.supportedSettings()) {
            Assert.assertTrue(setting, keys.contains(setting));
        }
    }

    /**
     * The android:key of the {@code tag} elements in a resource file. The sources: unit tests don't
     * get the merged resources (no includeAndroidResources).
     */
    private static List<String> keys(final String file, final String tag) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        final NodeList elements =
                factory.newDocumentBuilder()
                        .parse(new File("src/main/res/xml", file))
                        .getElementsByTagName(tag);
        final List<String> keys = new ArrayList<>();
        for (int i = 0; i < elements.getLength(); ++i) {
            final String key = ((Element) elements.item(i)).getAttributeNS(ANDROID, "key");
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }
}
