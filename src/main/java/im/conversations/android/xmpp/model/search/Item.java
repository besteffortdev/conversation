package im.conversations.android.xmpp.model.search;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Item extends Extension {

    public Item() {
        super(Item.class);
    }

    public String getFirst() {
        return getContent(First.class);
    }

    public String getLast() {
        return getContent(Last.class);
    }

    public String getNick() {
        return getContent(Nick.class);
    }

    public String getEmail() {
        return getContent(Email.class);
    }

    private String getContent(final Class<? extends Extension> clazz) {
        final var extension = this.getExtension(clazz);
        return extension == null ? null : extension.getContent();
    }
}
