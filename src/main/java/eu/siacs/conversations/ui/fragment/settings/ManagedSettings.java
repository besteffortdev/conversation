package eu.siacs.conversations.ui.fragment.settings;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.TwoStatePreference;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.AppConfig;
import java.util.ArrayList;
import java.util.List;

/** Shows the settings an MDM sets (see {@link AppConfig}) as set by it, not editable. */
final class ManagedSettings {

    private static final String LOCKED = "app_config_locked";

    private ManagedSettings() {}

    static void lock(final PreferenceFragmentCompat fragment) {
        final AppConfig config = AppConfig.get(fragment.requireContext());
        if (config.getSettings().isEmpty()) {
            return;
        }
        final CharSequence managed = fragment.getString(R.string.managed_by_organization);
        final List<Preference> preferences = new ArrayList<>();
        collect(fragment.getPreferenceScreen(), preferences);
        for (final Preference preference : preferences) {
            if (config.isManaged(preference.getKey())) {
                lock(preference, managed);
            }
        }
        // a disabled preference disables those that depend on it: they follow its value instead
        for (final Preference preference : preferences) {
            final String dependency = preference.getDependency();
            if (dependency == null || !config.isManaged(dependency)) {
                continue;
            }
            final Preference parent = fragment.findPreference(dependency);
            preference.setDependency(null);
            if (parent != null) {
                preference.onDependencyChanged(parent, false);
            }
            preference.setEnabled(
                    !config.isManaged(preference.getKey()) && !disablesDependents(parent));
        }
    }

    private static void lock(final Preference preference, final CharSequence managed) {
        if (preference.getExtras().getBoolean(LOCKED)) {
            return;
        }
        preference.getExtras().putBoolean(LOCKED, true);
        preference.setEnabled(false);
        final Preference.SummaryProvider<Preference> summaryProvider =
                preference.getSummaryProvider();
        if (summaryProvider == null) {
            preference.setSummary(join(preference.getSummary(), managed));
        } else {
            preference.setSummaryProvider(p -> join(summaryProvider.provideSummary(p), managed));
        }
    }

    private static boolean disablesDependents(final Preference parent) {
        if (parent instanceof TwoStatePreference twoState) {
            return twoState.getDisableDependentsState() == twoState.isChecked();
        }
        return false;
    }

    @NonNull
    private static CharSequence join(final CharSequence summary, final CharSequence managed) {
        return TextUtils.isEmpty(summary) ? managed : TextUtils.concat(summary, "\n", managed);
    }

    private static void collect(final PreferenceGroup group, final List<Preference> preferences) {
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getPreferenceCount(); ++i) {
            final Preference preference = group.getPreference(i);
            preferences.add(preference);
            if (preference instanceof PreferenceGroup child) {
                collect(child, preferences);
            }
        }
    }
}
