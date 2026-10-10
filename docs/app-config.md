# Managed configuration (MDM app config)

An MDM (SOTI MobiControl, Intune, Workspace ONE…) that installs Conversations can set up its
account and settings through Android's managed configurations ("app config").
`src/main/res/xml/app_restrictions.xml` declares them and the manifest points to it
(`android.content.APP_RESTRICTIONS`), so the MDM console lists them, with the titles and
descriptions of `src/main/res/values/fork_app_config.xml`.

This is the TAK Convo plugin's managed configuration, minus what only exists inside ATAK (TAK
server credentials, callsign, quick messages, the plugin's notifications).

## Keys

### Account

| Key | Meaning |
|---|---|
| `xmpp_domain` | The server part of addresses. Manages the host and port of every account on this domain, at their defaults unless set below |
| `xmpp_host` | Connect to this host instead of looking up the domain in DNS. Also turns on (and locks) `show_connection_options`, without which Conversations ignores an account's host |
| `xmpp_port` | The port on that host. Default 5222 |
| `xmpp_username` | A username (`alice`, with `xmpp_domain`) or a full address (`alice@example.com`, which needs no domain) |
| `xmpp_password` | With the username: Conversations adds the account by itself. Without it, the address is filled in on the sign-in screen and the user types the password |
| `xmpp_trusted_ca_certificate` | A CA certificate to trust: PEM (several may follow each other, whatever the console does to the line breaks) or Base64 DER |
| `xmpp_enabled` | Whether the managed account and the accounts on `xmpp_domain` connect. Not managed: users decide |

### Settings

Every other key is the Conversations preference it sets, with the same values: see
`AppConfig.SETTINGS` for the list (privacy, security, connection, availability, interface and
attachment settings). Booleans and lists are choices that start at **Not managed** (`unset`),
so a console that sends every key with its default value manages nothing until the admin picks
a value. SOTI MobiControl's own **Do nothing** sends nothing: the same. An empty text isn't
managed either. A console that sends its own key/value pairs may give Booleans as `true`/`false`
strings or as Booleans, and numbers as strings or numbers. Unknown keys and invalid values are
logged (`app config:` in logcat) and left out.

The choices' labels are plain text, not `@string` references: SOTI MobiControl, which reads the
schema from the APK, showed other strings of the APK for references inside an array.

## Reading it

`AppConfigService`, owned by `XmppConnectionService`, reads `RestrictionsManager
.getApplicationRestrictions()` when the service starts (once the accounts are loaded) and each
time Android broadcasts `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, which it receives while the
service runs. `AppConfig.get()` keeps the configuration read last for the screens.

## Applying it

```text
AppConfigService.refresh():
    settings: each managed value written into the preferences where it differs;
              keys managed last time and not now removed (back to their defaults)
    side effects of the changed settings, as the settings screens have them (OMEMO, trust
              manager, presences, Tor, message deletion, reconnects)
    CA certificates: stored in the MemorizingTrustManager's key store as app_config:<sha256>,
              which trusts what they issue; those no longer managed removed
    account: username and password set and no such account: added
    every account: managed host, port, password and state put in; updated if that changed it
```

**Locked.** Managed settings show greyed out with "Managed by your organization"; a setting that
depends on a managed one follows its value. Anything else that writes a managed preference is
undone by a preference listener. The managed account's address and password fields are read
only, it can't be deleted, and its password can't be changed on the server; the host and port of
accounts on the managed domain are read only; with `xmpp_enabled`, their on/off switch is
locked. `XmppConnectionService.createAccount()` and `updateAccount()` put the managed values back
into any account they save. The managed CA certificates don't show in "Remove trusted
certificates".

**Removed.** A setting the MDM no longer sets goes back to its default. An account it no longer
manages is unlocked and kept, with its history. A CA certificate it no longer sets is no longer
trusted.

## Testing without an MDM

Debug builds have a receiver that only the shell can call (it requires `DUMP`), in
`src/debug`. Its extras are added to the MDM's values, as if the MDM had set them; each broadcast
replaces the previous ones, and one without extras clears them:

```sh
adb shell am broadcast -a eu.siacs.conversations.DEBUG_APP_CONFIG -p eu.siacs.conversations \
    --es xmpp_domain example.com --es xmpp_username alice --es xmpp_password secret \
    --ez confirm_messages false --es omemo always

# back to nothing managed
adb shell am broadcast -a eu.siacs.conversations.DEBUG_APP_CONFIG -p eu.siacs.conversations
```

Google's Test DPC app, set as device owner, can also set a real managed configuration.
