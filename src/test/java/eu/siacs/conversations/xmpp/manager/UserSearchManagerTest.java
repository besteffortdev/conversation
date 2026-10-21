package eu.siacs.conversations.xmpp.manager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xml.XmlElementReader;
import im.conversations.android.xmpp.model.disco.info.InfoQuery;
import im.conversations.android.xmpp.model.search.Email;
import im.conversations.android.xmpp.model.search.Nick;
import im.conversations.android.xmpp.model.search.Search;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UserSearchManagerTest {

    // as sent by Openfire's search plugin
    private static final String OPENFIRE_FORM =
            """
            <query xmlns="jabber:iq:search">
              <instructions>The following fields are available for search.</instructions>
              <first/><last/><nick/><email/>
              <x xmlns="jabber:x:data" type="form">
                <title>Advanced User Search</title>
                <instructions>The following fields are available for search.</instructions>
                <field var="FORM_TYPE" type="hidden"><value>jabber:iq:search</value></field>
                <field var="search" type="text-single" label="Search"><required/></field>
                <field var="Username" type="boolean" label="Username"><value>1</value></field>
                <field var="Name" type="boolean" label="Name"><value>1</value></field>
                <field var="Email" type="boolean" label="Email"><value>1</value></field>
              </x>
            </query>\
            """;

    // as sent by ejabberd's vjud
    private static final String EJABBERD_FORM =
            """
            <query xmlns="jabber:iq:search">
              <instructions>You need an x:data capable client to search</instructions>
              <x xmlns="jabber:x:data" type="form">
                <field var="FORM_TYPE" type="hidden"><value>jabber:iq:search</value></field>
                <field var="user" type="text-single" label="User"/>
                <field var="fn" type="text-single" label="Full Name"/>
                <field var="first" type="text-single" label="Name"/>
                <field var="middle" type="text-single" label="Middle Name"/>
                <field var="last" type="text-single" label="Family Name"/>
                <field var="nick" type="text-single" label="Nickname"/>
                <field var="bday" type="text-single" label="Birthday"/>
                <field var="ctry" type="text-single" label="Country"/>
                <field var="email" type="text-single" label="Email"/>
              </x>
            </query>\
            """;

    @Test
    public void openfireFormIsOneQuery() throws IOException {
        final var form = XmlElementReader.read(OPENFIRE_FORM, Search.class);
        final var queries = UserSearchManager.buildQueries(form, "bob");
        Assert.assertEquals(1, queries.size());
        final var data = queries.get(0).getData();
        Assert.assertEquals("submit", data.getAttribute("type"));
        Assert.assertEquals("jabber:iq:search", data.getFormType());
        Assert.assertEquals("bob", data.getValue("search"));
        Assert.assertEquals("1", data.getValue("Username"));
        Assert.assertEquals("1", data.getValue("Name"));
        Assert.assertEquals("1", data.getValue("Email"));
        // the legacy fields are not sent along with the form
        Assert.assertFalse(queries.get(0).hasExtension(Nick.class));
    }

    @Test
    public void otherFormsQueryEachFieldWithWildcard() throws IOException {
        final var form = XmlElementReader.read(EJABBERD_FORM, Search.class);
        final var queries = UserSearchManager.buildQueries(form, "bob");
        Assert.assertEquals(4, queries.size());
        final var fields = ImmutableList.of("user", "fn", "nick", "email");
        for (int i = 0; i < fields.size(); ++i) {
            final var data = queries.get(i).getData();
            Assert.assertEquals("jabber:iq:search", data.getFormType());
            Assert.assertEquals(1, data.getFields().size());
            Assert.assertEquals("bob*", data.getValue(fields.get(i)));
        }
    }

    @Test
    public void legacyFormQueriesEachField() throws IOException {
        final var form =
                XmlElementReader.read(
                        """
                        <query xmlns="jabber:iq:search"><nick/><email/></query>\
                        """,
                        Search.class);
        final var queries = UserSearchManager.buildQueries(form, "bob");
        Assert.assertEquals(2, queries.size());
        Assert.assertEquals("bob", queries.get(0).getExtension(Nick.class).getContent());
        Assert.assertNull(queries.get(0).getData());
        Assert.assertEquals("bob", queries.get(1).getExtension(Email.class).getContent());
    }

    @Test
    public void parseDataFormResult() throws IOException {
        final var result =
                XmlElementReader.read(
                        """
                        <query xmlns="jabber:iq:search">
                          <x xmlns="jabber:x:data" type="result">
                            <field var="FORM_TYPE" type="hidden"><value>jabber:iq:search</value></field>
                            <reported>
                              <field var="jid" label="JID" type="jid-single"/>
                              <field var="Username" label="Username" type="text-single"/>
                              <field var="Name" label="Name" type="text-single"/>
                              <field var="Email" label="Email" type="text-single"/>
                            </reported>
                            <item>
                              <field var="jid"><value>bob@example.com</value></field>
                              <field var="Username"><value>bob</value></field>
                              <field var="Name"><value>Bob Smith</value></field>
                              <field var="Email"><value>bob@mail.example.com</value></field>
                            </item>
                            <item>
                              <field var="jid"><value>bobby@example.com</value></field>
                              <field var="Username"><value>bobby</value></field>
                              <field var="Name"/>
                            </item>
                            <item>
                              <field var="jid"><value>not a jid@@</value></field>
                            </item>
                          </x>
                        </query>\
                        """,
                        Search.class);
        final var results = UserSearchManager.parse(result);
        Assert.assertEquals(
                ImmutableList.of(
                        new UserSearchManager.Result(
                                Jid.of("bob@example.com"), "Bob Smith", "bob@mail.example.com"),
                        new UserSearchManager.Result(Jid.of("bobby@example.com"), null, null)),
                results);
    }

    @Test
    public void parseLegacyResult() throws IOException {
        final var result =
                XmlElementReader.read(
                        """
                        <query xmlns="jabber:iq:search">
                          <item jid="juliet@capulet.example/balcony">
                            <first>Juliet</first><last>Capulet</last><nick>JuliC</nick>
                            <email>juliet@shakespeare.example</email>
                          </item>
                          <item jid="tybalt@capulet.example"><nick>ty</nick></item>
                        </query>\
                        """,
                        Search.class);
        final var results = UserSearchManager.parse(result);
        Assert.assertEquals(
                ImmutableList.of(
                        new UserSearchManager.Result(
                                Jid.of("juliet@capulet.example"),
                                "Juliet Capulet",
                                "juliet@shakespeare.example"),
                        new UserSearchManager.Result(Jid.of("tybalt@capulet.example"), "ty", null)),
                results);
    }

    @Test
    public void mergeRemovesDuplicatesAndSorts() {
        final var bob = Jid.of("bob@example.com");
        final var alice = Jid.of("alice@example.com");
        final var merged =
                UserSearchManager.merge(
                        ImmutableList.of(
                                ImmutableList.of(new UserSearchManager.Result(bob, null, null)),
                                ImmutableList.of(
                                        new UserSearchManager.Result(bob, "Bob", null),
                                        new UserSearchManager.Result(alice, "alice", null))));
        Assert.assertEquals(
                ImmutableList.of(
                        new UserSearchManager.Result(alice, "alice", null),
                        new UserSearchManager.Result(bob, "Bob", null)),
                merged);
    }

    @Test
    public void everyUserDirectoryIsSearchedButNotRooms() throws IOException {
        final var search = Jid.of("search.example.com");
        final var federated = Jid.of("directory.example.com");
        Assert.assertEquals(
                ImmutableList.of(search, federated),
                UserSearchManager.getServices(
                        ImmutableMap.of(
                                search,
                                info("directory", "user"),
                                Jid.of("conference.example.com"),
                                info("conference", "text"),
                                federated,
                                info("directory", "user"))));
    }

    @Test
    public void withoutUserDirectoryTheFirstOtherSearchService() throws IOException {
        final var vjud = Jid.of("vjud.example.com");
        Assert.assertEquals(
                ImmutableList.of(vjud),
                UserSearchManager.getServices(
                        ImmutableMap.of(
                                Jid.of("conference.example.com"),
                                info("conference", "text"),
                                vjud,
                                info("component", "generic"),
                                Jid.of("other.example.com"),
                                info("component", "generic"))));
        Assert.assertEquals(
                ImmutableList.of(),
                UserSearchManager.getServices(
                        ImmutableMap.of(
                                Jid.of("conference.example.com"), info("conference", "text"))));
    }

    private static InfoQuery info(final String category, final String type) throws IOException {
        return XmlElementReader.read(
                String.format(
                        """
                        <query xmlns="http://jabber.org/protocol/disco#info">
                          <identity category="%s" type="%s"/>
                          <feature var="jabber:iq:search"/>
                        </query>\
                        """,
                        category, type),
                InfoQuery.class);
    }
}
