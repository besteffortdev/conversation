package im.conversations.android.xmpp.model.search;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Last extends Extension {

    public Last() {
        super(Last.class);
    }
}
