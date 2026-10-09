package eu.siacs.conversations.persistance;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.Nullable;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;

/**
 * Retractions (XEP-0424) whose target has not been seen yet, for example because MAM pages are
 * loaded newest first. They are kept until the retracted message shows up. This table belongs to
 * the fork and is created on demand, independent of DATABASE_VERSION.
 */
public final class PendingRetractions {

    public static final String TABLENAME = "pending_retractions";
    private static final String ACCOUNT = "account";
    private static final String CONVERSATION = "conversation";
    private static final String TARGET = "target";
    private static final String GROUP_CHAT = "group_chat";
    private static final String FROM_US = "from_us";
    private static final String COUNTERPART = "counterpart";
    private static final String OCCUPANT_ID = "occupant_id";
    private static final String REAL_JID = "real_jid";
    private static final String CREATED = "created";

    private PendingRetractions() {}

    /**
     * @param target the id the retraction refers to: a stanza-id in group chats, the message id
     *     otherwise
     * @param fromUs whether the retraction was sent from our own account
     */
    public record Entry(
            String conversationUuid,
            String target,
            boolean groupChat,
            boolean fromUs,
            @Nullable Jid counterpart,
            @Nullable String occupantId,
            @Nullable Jid realJid) {}

    public static void createTable(final SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS "
                        + TABLENAME
                        + " ("
                        + ACCOUNT
                        + " TEXT NOT NULL, "
                        + CONVERSATION
                        + " TEXT NOT NULL, "
                        + TARGET
                        + " TEXT NOT NULL, "
                        + GROUP_CHAT
                        + " INTEGER NOT NULL, "
                        + FROM_US
                        + " INTEGER NOT NULL, "
                        + COUNTERPART
                        + " TEXT, "
                        + OCCUPANT_ID
                        + " TEXT, "
                        + REAL_JID
                        + " TEXT, "
                        + CREATED
                        + " INTEGER NOT NULL, UNIQUE("
                        + CONVERSATION
                        + ","
                        + TARGET
                        + ","
                        + GROUP_CHAT
                        + ","
                        + FROM_US
                        + ") ON CONFLICT REPLACE, FOREIGN KEY("
                        + ACCOUNT
                        + ") REFERENCES "
                        + Account.TABLENAME
                        + "("
                        + Account.UUID
                        + ") ON DELETE CASCADE, FOREIGN KEY("
                        + CONVERSATION
                        + ") REFERENCES "
                        + Conversation.TABLENAME
                        + "("
                        + Conversation.UUID
                        + ") ON DELETE CASCADE)");
    }

    public static List<Entry> load(final DatabaseBackend database, final Account account) {
        final var builder = new ImmutableList.Builder<Entry>();
        final SQLiteDatabase db = database.getReadableDatabase();
        try (final Cursor cursor =
                db.query(
                        TABLENAME,
                        null,
                        ACCOUNT + "=?",
                        new String[] {account.getUuid()},
                        null,
                        null,
                        null)) {
            while (cursor.moveToNext()) {
                builder.add(
                        new Entry(
                                cursor.getString(cursor.getColumnIndexOrThrow(CONVERSATION)),
                                cursor.getString(cursor.getColumnIndexOrThrow(TARGET)),
                                cursor.getInt(cursor.getColumnIndexOrThrow(GROUP_CHAT)) > 0,
                                cursor.getInt(cursor.getColumnIndexOrThrow(FROM_US)) > 0,
                                jidOrNull(
                                        cursor.getString(
                                                cursor.getColumnIndexOrThrow(COUNTERPART))),
                                cursor.getString(cursor.getColumnIndexOrThrow(OCCUPANT_ID)),
                                jidOrNull(
                                        cursor.getString(cursor.getColumnIndexOrThrow(REAL_JID)))));
            }
        }
        return builder.build();
    }

    public static void add(
            final DatabaseBackend database, final Account account, final Entry entry) {
        final var values = new ContentValues();
        values.put(ACCOUNT, account.getUuid());
        values.put(CONVERSATION, entry.conversationUuid());
        values.put(TARGET, entry.target());
        values.put(GROUP_CHAT, entry.groupChat() ? 1 : 0);
        values.put(FROM_US, entry.fromUs() ? 1 : 0);
        values.put(
                COUNTERPART, entry.counterpart() == null ? null : entry.counterpart().toString());
        values.put(OCCUPANT_ID, entry.occupantId());
        values.put(REAL_JID, entry.realJid() == null ? null : entry.realJid().toString());
        values.put(CREATED, System.currentTimeMillis());
        database.getWritableDatabase().insert(TABLENAME, null, values);
    }

    public static void remove(final DatabaseBackend database, final Entry entry) {
        database.getWritableDatabase()
                .delete(
                        TABLENAME,
                        CONVERSATION
                                + "=? AND "
                                + TARGET
                                + "=? AND "
                                + GROUP_CHAT
                                + "=? AND "
                                + FROM_US
                                + "=?",
                        new String[] {
                            entry.conversationUuid(),
                            entry.target(),
                            entry.groupChat() ? "1" : "0",
                            entry.fromUs() ? "1" : "0"
                        });
    }

    @Nullable
    private static Jid jidOrNull(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        try {
            return Jid.of(value);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
