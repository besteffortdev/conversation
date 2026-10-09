package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.model.fallback.Fallback;
import im.conversations.android.xmpp.model.hints.Store;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.retraction.Retract;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** XEP-0424: Message Retraction (retracting your own messages; moderation is separate) */
public class RetractionManager extends AbstractManager {

    public static final String FALLBACK_BODY =
            "/me retracted a previous message, but it's unsupported by your client.";

    private final XmppConnectionService service;

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
     * device). Retractions sent by moderators are handled by {@link ModerationManager}.
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
        final var isTypeGroupChat =
                packet.getType()
                        == im.conversations.android.xmpp.model.stanza.Message.Type.GROUPCHAT;
        final Message message;
        if (isTypeGroupChat && conversation.getMode() == Conversational.MODE_MULTI) {
            if (!counterpart.isFullJid()) {
                Log.d(Config.LOGTAG, "ignoring retraction in MUC that was not sent by an occupant");
                return;
            }
            message = findByServerMsgId(conversation, id);
            if (message == null) {
                Log.d(Config.LOGTAG, "received retraction for unknown stanza-id " + id);
                return;
            }
            final var mucOptions =
                    getManager(MultiUserChatManager.class).getOrCreateState(conversation);
            if (!isSameOccupant(mucOptions, message, packet, query)) {
                Log.d(Config.LOGTAG, "retraction in MUC did not pass validation");
                return;
            }
        } else {
            final boolean fromUs = packet.fromAccount(account);
            message = findByMessageId(conversation, id, !fromUs);
            if (message == null) {
                Log.d(Config.LOGTAG, "received retraction for unknown message id " + id);
                return;
            }
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                // retraction of a private message in a MUC
                final var mucOptions =
                        getManager(MultiUserChatManager.class).getOrCreateState(conversation);
                if (!fromUs && !isSameOccupant(mucOptions, message, packet, query)) {
                    Log.d(Config.LOGTAG, "retraction via MUC PM did not pass validation");
                    return;
                }
            } else if (!fromUs
                    && (message.getCounterpart() == null
                            || !message.getCounterpart()
                                    .asBareJid()
                                    .equals(counterpart.asBareJid()))) {
                Log.d(Config.LOGTAG, "retraction was not sent by the author of the message");
                return;
            }
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

    /**
     * The sender of a retraction in a MUC has to be the author of the message: in semi-anonymous
     * rooms this is established via occupant ids (XEP-0421), in non-anonymous rooms via real JIDs.
     */
    private boolean isSameOccupant(
            @NonNull final MucOptions mucOptions,
            @NonNull final Message message,
            @NonNull final im.conversations.android.xmpp.model.stanza.Message packet,
            @Nullable final MessageArchiveManager.Query query) {
        final boolean ours = message.getStatus() != Message.STATUS_RECEIVED;
        final var occupant =
                mucOptions.occupantId() ? packet.getOnlyExtension(OccupantId.class) : null;
        final String occupantId = occupant == null ? null : occupant.getId();
        if (occupantId != null) {
            if (occupantId.equals(message.getOccupantId())) {
                return true;
            }
            return ours && occupantId.equals(mucOptions.getSelf().getOccupantId());
        }
        if (mucOptions.nonanonymous()) {
            final var user = getManager(MultiUserChatManager.class).getMucUser(packet, query);
            final Jid realJid = user == null ? null : user.getRealJid();
            final Jid author = ours ? getAccount().getJid() : message.getTrueCounterpart();
            return realJid != null
                    && author != null
                    && realJid.asBareJid().equals(author.asBareJid());
        }
        return false;
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
