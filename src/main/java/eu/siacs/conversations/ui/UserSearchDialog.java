package eu.siacs.conversations.ui;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogUserSearchBinding;
import eu.siacs.conversations.databinding.ItemUserSearchResultBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.manager.UserSearchManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Full screen 'Add contact' with XEP-0055 user search: a search field over the users found on the
 * servers of the accounts, with a way out to the regular dialog for entering an address by hand.
 */
public class UserSearchDialog extends DialogFragment {

    private static final String QUERY_KEY = "query";
    private static final long SEARCH_DELAY = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::search;
    private final ResultAdapter adapter = new ResultAdapter();

    private DialogUserSearchBinding binding;
    // bumped on every change of the search term so that stale responses are ignored
    private int generation = 0;

    public static UserSearchDialog newInstance(final String query) {
        final var dialog = new UserSearchDialog();
        final var arguments = new Bundle();
        arguments.putString(QUERY_KEY, query);
        dialog.setArguments(arguments);
        return dialog;
    }

    public static List<Account> getSearchableAccounts(final XmppConnectionService service) {
        final var accounts = new ArrayList<Account>();
        for (final var account : service.getAccounts()) {
            if (account.isEnabled()
                    && account.isOnlineAndConnected()
                    && account.getXmppConnection()
                            .getManager(UserSearchManager.class)
                            .hasFeature()) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_Conversations3_FullScreenDialog);
    }

    @Override
    public View onCreateView(
            @NonNull final LayoutInflater inflater,
            @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        binding = DataBindingUtil.inflate(inflater, R.layout.dialog_user_search, container, false);
        // the window is laid out edge to edge (see onStart), so keep clear of the system bars,
        // the display cutout and the keyboard here
        ViewCompat.setOnApplyWindowInsetsListener(
                binding.getRoot(),
                (view, windowInsets) -> {
                    final var insets =
                            windowInsets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout()
                                            | WindowInsetsCompat.Type.ime());
                    view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                    return WindowInsetsCompat.CONSUMED;
                });
        binding.toolbar.setNavigationOnClickListener(v -> dismiss());
        binding.results.setAdapter(adapter);
        binding.manualAddress.setOnClickListener(
                v -> {
                    final var listener = getListener();
                    dismiss();
                    if (listener != null) {
                        listener.onManualAddressSelected();
                    }
                });
        binding.search.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(final Editable s) {
                        onSearchTermChanged();
                    }
                });
        binding.search.setOnEditorActionListener(
                (v, actionId, event) -> {
                    handler.removeCallbacks(searchRunnable);
                    search();
                    return true;
                });
        final var query = requireArguments().getString(QUERY_KEY);
        if (savedInstanceState == null && !Strings.isNullOrEmpty(query)) {
            binding.search.append(query);
        }
        binding.search.requestFocus();
        return binding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        final var dialog = getDialog();
        final var window = dialog == null ? null : dialog.getWindow();
        if (window == null) {
            return;
        }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // edge to edge on every version, as Android 15+ enforces it anyway
        WindowCompat.setDecorFitsSystemWindows(window, false);
        final boolean lightMode = !Activities.isNightMode(requireContext());
        final var insetsController =
                WindowCompat.getInsetsController(window, window.getDecorView());
        window.setStatusBarColor(Color.TRANSPARENT);
        insetsController.setAppearanceLightStatusBars(lightMode);
        // light navigation bar icons need Android 8
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setNavigationBarColor(Color.TRANSPARENT);
            insetsController.setAppearanceLightNavigationBars(lightMode);
        }
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(searchRunnable);
        super.onDestroyView();
    }

    private String getSearchTerm() {
        return Strings.nullToEmpty(
                        binding.search.getText() == null
                                ? null
                                : binding.search.getText().toString())
                .trim();
    }

    private void onSearchTermChanged() {
        handler.removeCallbacks(searchRunnable);
        generation++;
        if (getSearchTerm().isEmpty()) {
            adapter.setEntries(ImmutableList.of(), false);
            binding.progress.setVisibility(View.INVISIBLE);
            binding.status.setVisibility(View.GONE);
            binding.results.setVisibility(View.GONE);
            binding.manualAddress.setVisibility(View.VISIBLE);
        } else {
            binding.manualAddress.setVisibility(View.GONE);
            binding.progress.setVisibility(View.VISIBLE);
            handler.postDelayed(searchRunnable, SEARCH_DELAY);
        }
    }

    private void search() {
        final var term = getSearchTerm();
        if (term.isEmpty() || !(getActivity() instanceof XmppActivity activity)) {
            return;
        }
        if (activity.xmppConnectionService == null) {
            // restored after a configuration change; the service will be back shortly
            handler.postDelayed(searchRunnable, SEARCH_DELAY);
            return;
        }
        final var accounts = getSearchableAccounts(activity.xmppConnectionService);
        final int generation = this.generation;
        final List<ListenableFuture<List<Entry>>> futures = new ArrayList<>();
        for (final var account : accounts) {
            final var manager = account.getXmppConnection().getManager(UserSearchManager.class);
            futures.add(
                    Futures.transform(
                            manager.search(term),
                            results -> toEntries(account, results),
                            MoreExecutors.directExecutor()));
        }
        binding.progress.setVisibility(View.VISIBLE);
        Futures.addCallback(
                Futures.successfulAsList(futures),
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final List<List<Entry>> lists) {
                        onSearchResults(generation, accounts.size() > 1, lists);
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        onSearchResults(generation, false, ImmutableList.of());
                    }
                },
                ContextCompat.getMainExecutor(activity));
    }

    private static List<Entry> toEntries(
            final Account account, final List<UserSearchManager.Result> results) {
        final var self = account.getJid().asBareJid();
        final var entries = new ImmutableList.Builder<Entry>();
        for (final var result : results) {
            if (!self.equals(result.address())) {
                entries.add(new Entry(account, result));
            }
        }
        return entries.build();
    }

    private void onSearchResults(
            final int generation, final boolean showAccount, final List<List<Entry>> lists) {
        if (generation != this.generation || binding == null || !isAdded()) {
            return;
        }
        binding.progress.setVisibility(View.INVISIBLE);
        final var entries =
                ImmutableList.copyOf(Iterables.concat(Iterables.filter(lists, Objects::nonNull)));
        adapter.setEntries(entries, showAccount);
        binding.results.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        if (entries.isEmpty()) {
            final boolean failed = Iterables.all(lists, Objects::isNull);
            binding.status.setText(failed ? R.string.user_search_failed : R.string.no_users_found);
            binding.status.setVisibility(View.VISIBLE);
        } else {
            binding.status.setVisibility(View.GONE);
        }
    }

    private void onEntrySelected(final Entry entry) {
        final var listener = getListener();
        dismiss();
        if (listener != null) {
            listener.onUserSelected(entry.account(), entry.result());
        }
    }

    private Listener getListener() {
        return getActivity() instanceof Listener listener ? listener : null;
    }

    public interface Listener {
        void onManualAddressSelected();

        void onUserSelected(Account account, UserSearchManager.Result result);
    }

    private record Entry(Account account, UserSearchManager.Result result) {}

    private class ResultAdapter extends RecyclerView.Adapter<ResultViewHolder> {

        private List<Entry> entries = ImmutableList.of();
        private boolean showAccount = false;

        public void setEntries(final List<Entry> entries, final boolean showAccount) {
            this.entries = entries;
            this.showAccount = showAccount;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ResultViewHolder onCreateViewHolder(
                @NonNull final ViewGroup parent, final int viewType) {
            return new ResultViewHolder(
                    ItemUserSearchResultBinding.inflate(
                            LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final ResultViewHolder holder, final int position) {
            final var entry = entries.get(position);
            final var result = entry.result();
            final var binding = holder.binding;
            final var context = binding.getRoot().getContext();
            final int size = context.getResources().getDimensionPixelSize(R.dimen.avatar);
            binding.avatar.setImageBitmap(AvatarService.get(result.address(), size));
            binding.name.setText(result.getDisplayName());
            final String secondLine =
                    result.name() == null ? result.email() : result.address().toString();
            binding.address.setText(secondLine);
            binding.address.setVisibility(secondLine == null ? View.GONE : View.VISIBLE);
            if (showAccount) {
                binding.account.setText(
                        context.getString(
                                R.string.user_search_via_account,
                                entry.account().getJid().asBareJid().toString()));
                binding.account.setVisibility(View.VISIBLE);
            } else {
                binding.account.setVisibility(View.GONE);
            }
            binding.getRoot().setOnClickListener(v -> onEntrySelected(entry));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }

    private static class ResultViewHolder extends RecyclerView.ViewHolder {

        private final ItemUserSearchResultBinding binding;

        private ResultViewHolder(final ItemUserSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
