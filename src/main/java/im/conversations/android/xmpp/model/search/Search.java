package im.conversations.android.xmpp.model.search;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.data.Data;
import java.util.Collection;

@XmlElement(name = "query")
public class Search extends Extension {

    public Search() {
        super(Search.class);
    }

    public Data getData() {
        return this.getExtension(Data.class);
    }

    public Collection<Item> getItems() {
        return this.getExtensions(Item.class);
    }
}
