package eu.siacs.conversations.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import eu.siacs.conversations.Config;

/**
 * Debug builds: sets the values {@link AppConfig} adds to the MDM's, so a device without an MDM can
 * test the managed configuration. Each broadcast replaces the previous values; one without extras
 * clears them. Only the shell can send it (see src/debug/AndroidManifest.xml):
 *
 * <pre>
 * adb shell am broadcast -a eu.siacs.conversations.DEBUG_APP_CONFIG -p eu.siacs.conversations \
 *     --es xmpp_domain example.com --es xmpp_username alice --es xmpp_password secret \
 *     --ez confirm_messages false
 * </pre>
 */
public class AppConfigDebugReceiver extends BroadcastReceiver {

    @Override
    @SuppressWarnings("deprecation") // Bundle.get: the values come in several types
    public void onReceive(final Context context, final Intent intent) {
        final Bundle extras = intent.getExtras();
        final SharedPreferences.Editor editor =
                context.getSharedPreferences(AppConfig.DEBUG_PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .clear();
        if (extras != null) {
            for (final String key : extras.keySet()) {
                final Object value = extras.get(key);
                if (value instanceof Boolean bool) {
                    editor.putBoolean(key, bool);
                } else if (value instanceof Integer integer) {
                    editor.putInt(key, integer);
                } else if (value != null) {
                    editor.putString(key, String.valueOf(value));
                }
            }
        }
        editor.commit();
        Log.d(
                Config.LOGTAG,
                "app config: debug values set " + (extras == null ? "[]" : extras.keySet()));
        context.sendBroadcast(
                new Intent(AppConfig.ACTION_DEBUG_CHANGED).setPackage(context.getPackageName()));
    }
}
