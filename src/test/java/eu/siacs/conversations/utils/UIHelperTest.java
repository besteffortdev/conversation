package eu.siacs.conversations.utils;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UIHelperTest {

    private final Account account = new Account(Jid.of("me@2507-xmpp.core.net"), "secret");

    @Test
    public void foreignAddressIsShortened() {
        Assert.assertEquals(
                "2508-user",
                UIHelper.shortenForeignAddress("2508-user@2508-xmpp.core.net", account));
    }

    @Test
    public void foreignFullJidIsShortened() {
        Assert.assertEquals(
                "user", UIHelper.shortenForeignAddress("user@other.example/phone", account));
    }

    @Test
    public void addressOnOwnServerIsKept() {
        Assert.assertEquals(
                "2507-user@2507-xmpp.core.net",
                UIHelper.shortenForeignAddress("2507-user@2507-xmpp.core.net", account));
    }

    @Test
    public void plainNickIsKept() {
        Assert.assertEquals("Bob", UIHelper.shortenForeignAddress("Bob", account));
    }

    @Test
    public void invalidAddressesAreKept() {
        Assert.assertEquals(
                "@other.example", UIHelper.shortenForeignAddress("@other.example", account));
        Assert.assertEquals("user@", UIHelper.shortenForeignAddress("user@", account));
        Assert.assertNull(UIHelper.shortenForeignAddress(null, account));
    }
}
