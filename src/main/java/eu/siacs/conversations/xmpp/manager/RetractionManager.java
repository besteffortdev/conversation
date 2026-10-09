package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.PendingRetractions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.fallback.Fallback;
import im.conversations.android.xmpp.model.hints.Store;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.retraction.Retract;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** XEP-0424: Message Retraction (retracting your own messages; moderation is separate) */
public class RetractionManager extends AbstractManager {

    public static final String FALLBACK_BODY =
            "/me retracted a previous message, but it's unsupported by your client.";

    private final XmppConnectionService service;
    private Map<String, List<PendingRetractions.Entry>> pending;

    public RetractionManager(final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    /**
     * @return whether one of our own messages can be retracted
     */
    public static boolean isRetractable(final Message message) {
        final int status = message.getStatus();
        // only messages that actually made it to the server; queued ones would still be sent
        if ((status != Message.STATUS_SEND
                        && status != Message.STATUS_SEND_RECEIVED
                        && status != Message.STATUS_SEND_DISPLAYED)
                || message.isRetracted()
                || message.getType() == Message.TYPE_STATUS
                || message.getType() == Message.TYPE_RTP_SESSION
                || message.getTransferable() != null) {
            return false;
        }
        if (message.getConversation() instanceof Conversation conversation) {
            if (conversation.getMode() == Conversational.MODE_MULTI
                    && !message.isPrivateMessage()) {
                return conversation.getMucOptions().participating()
                        && !Strings.isNullOrEmpty(message.getServerMsgId());
            }
            return !Strings.isNullOrEmpty(message.getMessageId());
        }
        return false;
    }

    /** Asks everyone in the conversation to delete one of our messages and removes it locally. */
    public boolean retract(final Message message) {
        if (!isRetractable(message)
                || !(message.getConversation() instanceof Conversation conversation)
                || !getAccount().isOnlineAndConnected()) {
            return false;
        }
        final boolean groupChat =
                conversation.getMode() == Conversational.MODE_MULTI && !message.isPrivateMessage();
        final Jid to;
        final Collection<String> ids;
        if (groupChat) {
            to = conversation.getAddress().asBareJid();
            ids =
                    withPreviousVersions(
                            message.getServerMsgId(), message.getEditedServerMessageIds());
        } else {
            to =
                    message.isPrivateMessage()
                            ? message.getCounterpart()
                            : conversation.getAddress().asBareJid();
            ids = withPreviousVersions(message.getMessageId(), message.getEditedIds());
        }
        if (to == null) {
            return false;
        }
        // corrected messages may be known to other clients by any of their ids. only the first
        // retraction carries a fallback body so clients without support show a single notice
        boolean withFallback = true;
        for (final String id : ids) {
            this.connection.sendMessagePacket(retraction(to, groupChat, id, withFallback));
            withFallback = false;
        }
        apply(message);
        return true;
    }

    private static Collection<String> withPreviousVersions(
            final String id, final Collection<String> previous) {
        final var builder = new ImmutableSet.Builder<String>();
        for (final String previousId : previous) {
            if (!Strings.isNullOrEmpty(previousId)) {
                builder.add(previousId);
            }
        }
        builder.add(id);
        return builder.build();
    }

    public static im.conversations.android.xmpp.model.stanza.Message retraction(
            final Jid to, final boolean groupChat, final String id, final boolean withFallback) {
        final var packet = new im.conversations.android.xmpp.model.stanza.Message();
        packet.setType(
                groupChat
                        ? im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT
                        : im.conversations.android.xmpp.model.stanza.Message.Type.CHAT);
        packet.setTo(to);
        packet.setId(UUID.randomUUID().toString());
        final var retract = packet.addExtension(new Retract());
        retract.setAttribute("id", id);
        if (withFallback) {
            final var fallback = packet.addExtension(new Fallback());
            fallback.setAttribute("for", Namespace.RETRACTION);
            packet.setBody(FALLBACK_BODY);
        }
        packet.addExtension(new Store());
        return packet;
    }

    /**
     * Handles a retraction sent by the author of a message (including ourselves from another
     * device). Retractions sent by moderators are handled by {@link ModerationManager}. If the
     * retracted message is not known yet it is remembered until it shows up.
     */
    public void processRetraction(
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Jid counterpart,
            @Nullable final MessageArchiveManager.Query query) {
        final var retract = packet.getExtension(Retract.class);
        if (retract == null) {
            throw new IllegalStateException("Called processRetraction w/o checking for retract");
        }
        final String id = retract.getId();
        if (retract.getModerated() != null || id == null || counterpart == null) {
            return;
        }
        final var account = getAccount();
        final var conversation = this.service.find(account, counterpart.asBareJid());
        if (conversation == null) {
            return;
        }
        final boolean groupChat =
                packet.getType()
                                == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT
                        && conversation.getMode() == Conversational.MODE_MULTI;
        if (groupChat && !counterpart.isFullJid()) {
            Log.d(Config.LOGTAG, "ignoring retraction in MUC that was not sent by an occupant");
            return;
        }
        final Sender sender = getSender(conversation, packet, counterpart, query);
        final Message message =
                groupChat
                        ? findByServerMsgId(conversation, id)
                        : findByMessageId(conversation, id, !sender.fromUs());
        if (message == null) {
            remember(conversation, id, groupChat, sender);
            return;
        }
        if (!isAuthor(sender, conversation, message)) {
            Log.d(Config.LOGTAG, "retraction was not sent by the author of the message");
            return;
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": received retraction for "
                        + id
                        + " in "
                        + conversation.getAddress());
        apply(message);
    }

    /**
     * To be called for every new message before it is stored or displayed: retracts it right away
     * if a retraction for it has been received earlier.
     */
    public void applyPendingRetraction(final Conversation conversation, final Message message) {
        final PendingRetractions.Entry match;
        synchronized (this) {
            final var entries = getPending().get(conversation.getUuid());
            if (entries == null || entries.isEmpty()) {
                return;
            }
            match =
                    Iterables.find(
                            entries,
                            e ->
                                    e != null
                                            && isTarget(e, message)
                                            && isAuthor(e, conversation, message),
                            null);
            if (match == null) {
                return;
            }
            entries.remove(match);
        }
        PendingRetractions.remove(getDatabase(), match);
        Log.d(
                Config.LOGTAG,
                getAccount().getJid().asBareJid()
                        + ": applied earlier retraction to "
                        + match.target()
                        + " in "
                        + conversation.getAddress());
        message.retract();
    }

    private void remember(
            final Conversation conversation,
            final String id,
            final boolean groupChat,
            final Sender sender) {
        if (conversation.getMode() == Conversational.MODE_MULTI
                && !sender.fromUs()
                && sender.occupantId() == null
                && sender.realJid() == null) {
            Log.d(Config.LOGTAG, "not remembering retraction from an unverifiable occupant");
            return;
        }
        final var entry =
                new PendingRetractions.Entry(
                        conversation.getUuid(),
                        id,
                        groupChat,
                        sender.fromUs(),
                        sender.counterpart(),
                        sender.occupantId(),
                        sender.realJid());
        synchronized (this) {
            final var entries =
                    getPending().computeIfAbsent(conversation.getUuid(), k -> new ArrayList<>());
            if (entries.contains(entry)) {
                return;
            }
            entries.add(entry);
        }
        PendingRetractions.add(getDatabase(), getAccount(), entry);
        Log.d(
                Config.LOGTAG,
                getAccount().getJid().asBareJid()
                        + ": remembering retraction for unknown message "
                        + id
                        + " in "
                        + conversation.getAddress());
    }

    private synchronized Map<String, List<PendingRetractions.Entry>> getPending() {
        if (this.pending == null) {
            final var pending = new HashMap<String, List<PendingRetractions.Entry>>();
            for (final var entry : PendingRetractions.load(getDatabase(), getAccount())) {
                pending.computeIfAbsent(entry.conversationUuid(), k -> new ArrayList<>())
                        .add(entry);
            }
            this.pending = pending;
        }
        return this.pending;
    }

    static boolean isTarget(final PendingRetractions.Entry entry, final Message message) {
        if (entry.groupChat()) {
            return entry.target().equals(message.getServerMsgId());
        }
        if ((message.getStatus() != Message.STATUS_RECEIVED) != entry.fromUs()) {
            return false;
        }
        return entry.target().equals(message.getRemoteMsgId())
                || entry.target().equals(message.getUuid());
    }

    /** Who sent a retraction, with everything needed to check that it was the author. */
    record Sender(
            boolean fromUs,
            @Nullable Jid counterpart,
            @Nullable String occupantId,
            @Nullable Jid realJid) {

        static Sender of(final PendingRetractions.Entry entry) {
            return new Sender(
                    entry.fromUs(), entry.counterpart(), entry.occupantId(), entry.realJid());
        }
    }

    private Sender getSender(
            final Conversation conversation,
            final im.conversations.android.xmpp.model.stanza.Message packet,
            final Jid counterpart,
            @Nullable final MessageArchiveManager.Query query) {
        final var account = getAccount();
        if (conversation.getMode() != Conversational.MODE_MULTI) {
            return new Sender(packet.fromAccount(account), counterpart.asBareJid(), null, null);
        }
        final var mucOptions =
                getManager(MultiUserChatManager.class).getOrCreateState(conversation);
        final var occupant =
                mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
        final String occupantId = occupant == null ? null : occupant.getId();
        final var user = getManager(MultiUserChatManager.class).getMucUser(packet, query);
        final Jid realJid = mucOptions.nonanonymous() && user != null ? user.getRealJid() : null;
        final boolean fromUs =
                packet.fromAccount(account)
                        || (occupantId != null
                                && occupantId.equals(mucOptions.getSelf().getOccupantId()))
                        || (realJid != null
                                && realJid.asBareJid().equals(account.getJid().asBareJid()));
        return new Sender(fromUs, counterpart, occupantId, realJid);
    }

    private boolean isAuthor(
            final PendingRetractions.Entry entry,
            final Conversation conversation,
            final Message message) {
        return isAuthor(Sender.of(entry), conversation, message);
    }

    private boolean isAuthor(
            final Sender sender, final Conversation conversation, final Message message) {
        return isAuthor(
                sender,
                conversation.getMode() == Conversational.MODE_MULTI,
                getAccount().getJid(),
                message);
    }

    /**
     * The sender of a retraction has to be the author of the message: the same bare JID in 1:1
     * chats; in MUCs the same occupant id (XEP-0421) or, in non-anonymous rooms, the same real JID.
     */
    static boolean isAuthor(
            final Sender sender,
            final boolean multiUserChat,
            final Jid account,
            final Message message) {
        final boolean ours = message.getStatus() != Message.STATUS_RECEIVED;
        if (multiUserChat) {
            if (sender.occupantId() != null) {
                return sender.occupantId().equals(message.getOccupantId())
                        || (ours && sender.fromUs());
            }
            if (sender.realJid() != null) {
                final Jid author = ours ? account : message.getTrueCounterpart();
                return author != null && sender.realJid().asBareJid().equals(author.asBareJid());
            }
            // for example private messages we sent from another device (carbons)
            return ours && sender.fromUs();
        }
        if (ours) {
            return sender.fromUs();
        }
        return !sender.fromUs()
                && sender.counterpart() != null
                && message.getCounterpart() != null
                && sender.counterpart().asBareJid().equals(message.getCounterpart().asBareJid());
    }

    @Nullable
    private Message findByServerMsgId(final Conversation conversation, final String id) {
        final var inMemoryMessage = conversation.findMessageWithServerMsgId(id);
        if (inMemoryMessage != null) {
            return inMemoryMessage;
        }
        return getDatabase().getMessageWithServerMsgId(conversation, id);
    }

    @Nullable
    private Message findByMessageId(
            final Conversation conversation, final String id, final boolean received) {
        final var inMemoryMessage = conversation.findMessageWithUuidOrRemoteId(id, null, received);
        if (inMemoryMessage != null) {
            return inMemoryMessage;
        }
        final var inMemoryEdit = conversation.findMessageWithPreviousVersion(id, received);
        if (inMemoryEdit != null) {
            return inMemoryEdit;
        }
        final var message = getDatabase().getMessageWithUuidOrRemoteId(conversation, id);
        if (message != null && (message.getStatus() == Message.STATUS_RECEIVED) == received) {
            return message;
        }
        return null;
    }

    /** Turns the message into a tombstone and deletes its file, if no other message uses it. */
    private void apply(final Message message) {
        if (message.isRetracted()) {
            return;
        }
        final Transferable transferable = message.getTransferable();
        if (transferable != null) {
            transferable.cancel();
        }
        final var storageLocation = message.isFileOrImage() ? message.getRelativeFilePath() : null;
        message.retract();
        this.service.getNotificationService().clear(message);
        this.service.updateMessage(message, true);
        if (storageLocation != null) {
            deleteFile(storageLocation);
        }
    }

    private void deleteFile(final Message.StorageLocation storageLocation) {
        final var file = storageLocation.file();
        // the retracted message no longer references the file; this comes up empty for files
        // that are not used elsewhere
        final List<String> messagesWithFile = getDatabase().getMessagesWithFile(file);
        if (messagesWithFile.isEmpty() && file.exists()) {
            synchronized (service.FILENAMES_TO_IGNORE_DELETION) {
                service.FILENAMES_TO_IGNORE_DELETION.add(file.getAbsolutePath());
            }
            if (file.delete()) {
                Log.d(Config.LOGTAG, "deleted file of retracted message");
            }
        }
    }
}
