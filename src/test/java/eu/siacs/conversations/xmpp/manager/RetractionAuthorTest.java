package eu.siacs.conversations.xmpp.manager;

import android.database.Cursor;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.StubConversation;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.PendingRetractions;
import eu.siacs.conversations.xmpp.Jid;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.ConscryptMode;

/** Retractions must only be applied when they come from the author of the retracted message. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RetractionAuthorTest {

    private static final Jid ME = Jid.of("romeo@montague.example");
    private static final Jid JULIET = Jid.of("juliet@capulet.example");
    private static final Jid TYBALT = Jid.of("tybalt@capulet.example");
    private static final Jid ROOM = Jid.of("verona@muc.capulet.example");

    private final Account account = new Account(ME, "secret");
    private final StubConversation chat =
            new StubConversation(account, "chat", JULIET, Conversational.MODE_SINGLE);
    private final StubConversation room =
            new StubConversation(account, "room", ROOM, Conversational.MODE_MULTI);

    private static PendingRetractions.Entry entry(
            final String target,
            final boolean groupChat,
            final boolean fromUs,
            final Jid counterpart,
            final String occupantId,
            final Jid realJid) {
        return new PendingRetractions.Entry(
                "conversation", target, groupChat, fromUs, counterpart, occupantId, realJid);
    }

    private static boolean applies(
            final PendingRetractions.Entry entry,
            final boolean multiUserChat,
            final Message message) {
        return RetractionManager.isTarget(entry, message)
                && RetractionManager.isAuthor(
                        RetractionManager.Sender.of(entry), multiUserChat, ME, message);
    }

    private Message received(
            final StubConversation conversation,
            final Jid from,
            final String remoteMsgId,
            final String serverMsgId) {
        final var message =
                new Message(
                        conversation, "hello", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED);
        message.setCounterpart(from);
        message.setRemoteMsgId(remoteMsgId);
        message.setServerMsgId(serverMsgId);
        return message;
    }

    @Test
    public void oneToOneRetractionFromAuthor() {
        final var message = received(chat, JULIET.withResource("phone"), "m1", null);
        Assert.assertTrue(applies(entry("m1", false, false, JULIET, null, null), false, message));
    }

    @Test
    public void oneToOneRetractionFromSomeoneElse() {
        final var message = received(chat, JULIET.withResource("phone"), "m1", null);
        Assert.assertFalse(applies(entry("m1", false, false, TYBALT, null, null), false, message));
    }

    @Test
    public void oneToOneRetractionCanNotTargetOurOwnMessage() {
        final var ours = new Message(chat, "hello", Message.ENCRYPTION_NONE, Message.STATUS_SEND);
        Assert.assertFalse(
                applies(entry(ours.getUuid(), false, false, JULIET, null, null), false, ours));
        Assert.assertTrue(applies(entry(ours.getUuid(), false, true, ME, null, null), false, ours));
    }

    @Test
    public void groupChatRetractionMatchesOccupantId() {
        final var message = received(room, ROOM.withResource("Juliet"), "m1", "stanza-1");
        message.setOccupantId("occupant-juliet");
        Assert.assertTrue(
                applies(
                        entry("stanza-1", true, false, null, "occupant-juliet", null),
                        true,
                        message));
        Assert.assertFalse(
                applies(
                        entry("stanza-1", true, false, null, "occupant-tybalt", null),
                        true,
                        message));
        Assert.assertFalse(
                applies(
                        entry("stanza-2", true, false, null, "occupant-juliet", null),
                        true,
                        message));
    }

    @Test
    public void groupChatRetractionOfOurOwnMessage() {
        final var ours =
                new Message(room, "hello", Message.ENCRYPTION_NONE, Message.STATUS_SEND_RECEIVED);
        ours.setServerMsgId("stanza-1");
        Assert.assertTrue(
                applies(entry("stanza-1", true, true, null, "occupant-me", null), true, ours));
        // someone else can not retract our message, even when they know its stanza-id
        Assert.assertFalse(
                applies(entry("stanza-1", true, false, null, "occupant-tybalt", null), true, ours));
    }

    @Test
    public void groupChatRetractionMatchesRealJidInNonAnonymousRooms() {
        final var message = received(room, ROOM.withResource("Juliet"), "m1", "stanza-1");
        message.setTrueCounterpart(JULIET);
        Assert.assertTrue(
                applies(entry("stanza-1", true, false, null, null, JULIET), true, message));
        Assert.assertFalse(
                applies(entry("stanza-1", true, false, null, null, TYBALT), true, message));
    }

    @Test
    public void groupChatRetractionThatCanNotBeVerified() {
        final var message = received(room, ROOM.withResource("Juliet"), "m1", "stanza-1");
        Assert.assertFalse(
                applies(entry("stanza-1", true, false, null, null, null), true, message));
    }

    @Test
    public void pendingRetractionsAreStored() {
        final var database = DatabaseBackend.getInstance(RuntimeEnvironment.getApplication());
        database.createAccount(account);
        final var conversation =
                new Conversation("Juliet", account, JULIET, Conversational.MODE_SINGLE);
        database.createConversation(conversation);
        final var entry =
                new PendingRetractions.Entry(
                        conversation.getUuid(), "m1", false, false, JULIET, null, null);
        PendingRetractions.add(database, account, entry);
        PendingRetractions.add(database, account, entry);
        Assert.assertEquals(
                Collections.singletonList(entry), PendingRetractions.load(database, account));
        PendingRetractions.remove(database, entry);
        Assert.assertTrue(PendingRetractions.load(database, account).isEmpty());
    }

    @Test
    public void retractedColumnIsCreated() {
        final var database = DatabaseBackend.getInstance(RuntimeEnvironment.getApplication());
        boolean found = false;
        try (final Cursor cursor =
                database.getReadableDatabase()
                        .rawQuery("PRAGMA table_info(" + Message.TABLENAME + ")", null)) {
            while (cursor.moveToNext()) {
                found |= Message.RETRACTED.equals(cursor.getString(cursor.getColumnIndex("name")));
            }
        }
        Assert.assertTrue(found);
    }
}
