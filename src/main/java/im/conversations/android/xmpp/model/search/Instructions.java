package im.conversations.android.xmpp.model.search;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Instructions extends Extension {

    public Instructions() {
        super(Instructions.class);
    }
}
