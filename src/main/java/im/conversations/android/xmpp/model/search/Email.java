package im.conversations.android.xmpp.model.search;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Email extends Extension {

    public Email() {
        super(Email.class);
    }
}
