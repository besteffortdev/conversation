package eu.siacs.conversations.ui;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.xmpp.Jid;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UserSearchDialogTest {

    private static Account account() {
        return new Account(Jid.of("romeo@montague.example"), "secret");
    }

    @Test
    public void searchOpensWhileTheAccountHasNotConnected() {
        Assert.assertTrue(UserSearchDialog.isAvailable(account()));
    }

    @Test
    public void searchOpensWhenTheAccountFailsToConnect() {
        for (final var state :
                new Account.State[] {
                    Account.State.CONNECTING,
                    Account.State.NO_INTERNET,
                    Account.State.CONNECTION_TIMEOUT,
                    Account.State.SERVER_NOT_FOUND,
                    Account.State.UNAUTHORIZED
                }) {
            final var account = account();
            account.setStatus(state);
            Assert.assertTrue(state.name(), UserSearchDialog.isAvailable(account));
        }
    }

    @Test
    public void searchOpensWhileLoggedOut() {
        final var account = account();
        account.setOption(Account.OPTION_SOFT_DISABLED, true);
        Assert.assertTrue(UserSearchDialog.isAvailable(account));
    }

    @Test
    public void searchDoesNotOpenForDisabledAccounts() {
        final var account = account();
        account.setOption(Account.OPTION_DISABLED, true);
        Assert.assertFalse(UserSearchDialog.isAvailable(account));
    }
}
