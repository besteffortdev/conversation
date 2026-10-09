package eu.siacs.conversations.xmpp.manager;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.StubConversation;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.fallback.Body;
import im.conversations.android.xmpp.model.fallback.Fallback;
import im.conversations.android.xmpp.model.hints.Store;
import im.conversations.android.xmpp.model.retraction.Retract;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RetractionManagerTest {

    @Test
    public void retractionWithFallback() {
        final var packet =
                RetractionManager.retraction(
                        Jid.of("lord@capulet.example"), false, "wrong-recipient-1", true);
        Assert.assertEquals(
                im.conversations.android.xmpp.model.stanza.Message.Type.CHAT, packet.getType());
        Assert.assertEquals("lord@capulet.example", packet.getTo().toString());
        Assert.assertNotNull(packet.getId());
        Assert.assertEquals("wrong-recipient-1", packet.getExtension(Retract.class).getId());
        Assert.assertTrue(Fallback.get(packet, Retract.class, Body.class).isPresent());
        Assert.assertEquals(RetractionManager.FALLBACK_BODY, packet.getBody().content);
        Assert.assertTrue(packet.hasExtension(Store.class));
    }

    @Test
    public void groupChatRetractionWithoutFallback() {
        final var packet =
                RetractionManager.retraction(
                        Jid.of("room@muc.capulet.example"), true, "stanza-id-1", false);
        Assert.assertEquals(
                im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT,
                packet.getType());
        Assert.assertEquals("stanza-id-1", packet.getExtension(Retract.class).getId());
        Assert.assertFalse(packet.hasExtension(Fallback.class));
        Assert.assertNull(packet.getBody());
        Assert.assertTrue(packet.hasExtension(Store.class));
    }

    @Test
    public void retractWipesContentAndPreviousVersions() {
        final var account = new Account(Jid.of("romeo@montague.example"), "secret");
        final var conversation =
                new StubConversation(
                        account,
                        "conversation",
                        Jid.of("juliet@capulet.example"),
                        Conversational.MODE_SINGLE);
        final var message =
                new Message(conversation, "original", Message.ENCRYPTION_NONE, Message.STATUS_SEND);
        message.putEdited(new Message.BodyVersion("corrected", Message.ENCRYPTION_NONE, null));
        Assert.assertTrue(message.hasEditHistory());
        final var originalId = message.getMessageId();

        message.retract();

        Assert.assertTrue(message.isRetracted());
        Assert.assertEquals("", message.getBody());
        for (final Message version : message.getVersionsAsMessages()) {
            Assert.assertEquals("", version.getBody());
        }
        // ids are kept so later references (for example duplicate retractions) still match
        Assert.assertEquals(originalId, message.getMessageId());
        Assert.assertTrue(message.getEditedIds().contains(originalId));
        Assert.assertFalse(RetractionManager.isRetractable(message));
    }
}
