package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
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
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogUserSearchBinding;
import eu.siacs.conversations.databinding.ItemUserSearchResultBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.manager.UserSearchManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Full screen XEP-0055 user search over the users found on the servers of the accounts. It either
 * adds one contact ('Add contact', with a way out to the regular dialog for entering an address by
 * hand) or picks several people, to invite into a conversation or to create a private group chat
 * with.
 */
public class UserSearchDialog extends DialogFragment {

    private static final String QUERY_KEY = "query";
    private static final String CONVERSATION_KEY = "conversation";
    private static final String GROUP_NAME_KEY = "group_name";
    private static final String ACCOUNT_KEY = "account";
    private static final String MEMBERS_KEY = "members";
    private static final String SELECTED_ADDRESSES_KEY = "selected_addresses";
    private static final String SELECTED_NAMES_KEY = "selected_names";
    private static final String PICKER_TAG = "user_search_picker";
    private static final String ENTER_JID_TAG = "enter_jid";
    private static final long SEARCH_DELAY = 400;
    // results shown at first, and added by each 'Load more'
    private static final int PAGE_SIZE = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::search;
    private final ResultAdapter adapter = new ResultAdapter();
    // people picked so far, in the order they were picked
    private final Map<Jid, UserSearchManager.Result> selected = new LinkedHashMap<>();
    // people that are already in the conversation the invite is for
    private Set<Jid> members = ImmutableSet.of();

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

    /**
     * Opens the search for inviting people into the conversation (a group chat, or a 1:1 chat that
     * becomes one) if the server of its account has a user directory.
     *
     * @return false if there is no directory to search; the caller falls back to {@link
     *     ChooseContactActivity}
     */
    public static boolean showInvite(final XmppActivity activity, final Conversation conversation) {
        final var account = conversation.getAccount();
        if (!isSearchable(account)) {
            return false;
        }
        final var members = new ArrayList<String>();
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            final var mucOptions = conversation.getMucOptions();
            for (final var member : mucOptions.getMembers()) {
                members.add(member.asBareJid().toString());
            }
            for (final var user : mucOptions.getOnlineUsers()) {
                final var realJid = user.getRealJid();
                if (realJid != null) {
                    members.add(realJid.asBareJid().toString());
                }
            }
        } else {
            members.add(conversation.getAddress().asBareJid().toString());
        }
        final var arguments = new Bundle();
        arguments.putString(CONVERSATION_KEY, conversation.getUuid());
        arguments.putString(ACCOUNT_KEY, account.getJid().asBareJid().toString());
        arguments.putStringArrayList(MEMBERS_KEY, members);
        showPicker(activity, arguments);
        return true;
    }

    /**
     * Opens the search for choosing the participants of a new private group chat if the server of
     * the account has a user directory.
     *
     * @return false if there is no directory to search; the caller falls back to {@link
     *     ChooseContactActivity}
     */
    public static boolean showCreateGroup(
            final XmppActivity activity, final Account account, final String name) {
        if (!isSearchable(account)) {
            return false;
        }
        final var arguments = new Bundle();
        arguments.putString(GROUP_NAME_KEY, name);
        arguments.putString(ACCOUNT_KEY, account.getJid().asBareJid().toString());
        showPicker(activity, arguments);
        return true;
    }

    private static void showPicker(final XmppActivity activity, final Bundle arguments) {
        final var fragmentManager = activity.getSupportFragmentManager();
        if (fragmentManager.findFragmentByTag(PICKER_TAG) != null) {
            return;
        }
        final var dialog = new UserSearchDialog();
        dialog.setArguments(arguments);
        dialog.show(fragmentManager, PICKER_TAG);
    }

    public static List<Account> getSearchableAccounts(final XmppConnectionService service) {
        final var accounts = new ArrayList<Account>();
        for (final var account : service.getAccounts()) {
            if (isSearchable(account)) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    private static boolean isSearchable(final Account account) {
        return account.isEnabled()
                && account.isOnlineAndConnected()
                && account.getXmppConnection().getManager(UserSearchManager.class).hasFeature();
    }

    private boolean isInvite() {
        return requireArguments().getString(CONVERSATION_KEY) != null;
    }

    private boolean isCreateGroup() {
        return requireArguments().getString(GROUP_NAME_KEY) != null;
    }

    // picking several people, as opposed to adding one contact
    private boolean isPicker() {
        return isInvite() || isCreateGroup();
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_Conversations3_FullScreenDialog);
        final var members = requireArguments().getStringArrayList(MEMBERS_KEY);
        if (members != null) {
            this.members = ImmutableSet.copyOf(Collections2.transform(members, Jid::of));
        }
        if (savedInstanceState != null) {
            final var addresses = savedInstanceState.getStringArrayList(SELECTED_ADDRESSES_KEY);
            final var names = savedInstanceState.getStringArrayList(SELECTED_NAMES_KEY);
            if (addresses != null && names != null && addresses.size() == names.size()) {
                for (int i = 0; i < addresses.size(); ++i) {
                    final var address = Jid.of(addresses.get(i));
                    selected.put(
                            address, new UserSearchManager.Result(address, names.get(i), null));
                }
            }
        }
        // also reaches a dialog for entering an address that survived a configuration change
        getChildFragmentManager()
                .addFragmentOnAttachListener(
                        (fragmentManager, fragment) -> {
                            if (fragment instanceof EnterJidDialog enterJidDialog) {
                                enterJidDialog.setOnEnterJidDialogPositiveListener(
                                        this::onAddressEntered);
                            }
                        });
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        final var addresses = new ArrayList<String>();
        final var names = new ArrayList<String>();
        for (final var result : selected.values()) {
            addresses.add(result.address().toString());
            names.add(result.name());
        }
        outState.putStringArrayList(SELECTED_ADDRESSES_KEY, addresses);
        outState.putStringArrayList(SELECTED_NAMES_KEY, names);
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
        // the search field sits in the toolbar in place of a title, so the title is its hint
        if (isInvite()) {
            binding.search.setHint(R.string.invite_contact);
        } else if (isCreateGroup()) {
            binding.search.setHint(R.string.choose_participants);
        } else {
            binding.search.setHint(R.string.add_contact);
        }
        binding.clearSearch.setOnClickListener(v -> binding.search.setText(null));
        if (isPicker()) {
            binding.toolbar.inflateMenu(R.menu.user_search_picker);
            binding.toolbar
                    .getMenu()
                    .findItem(R.id.action_add)
                    .setTitle(isInvite() ? R.string.add : R.string.create);
            binding.toolbar.setOnMenuItemClickListener(
                    item -> {
                        if (item.getItemId() != R.id.action_add) {
                            return false;
                        }
                        if (isInvite()) {
                            invite();
                        } else {
                            createGroup();
                        }
                        return true;
                    });
        }
        binding.results.setAdapter(adapter);
        binding.manualAddress.setOnClickListener(
                v -> {
                    if (isPicker()) {
                        showEnterJidDialog();
                        return;
                    }
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
        updateSelection();
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
        final var text = binding.search.getText();
        binding.clearSearch.setVisibility(
                text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
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
        final var service = activity.xmppConnectionService;
        if (service == null) {
            // restored after a configuration change; the service will be back shortly
            handler.postDelayed(searchRunnable, SEARCH_DELAY);
            return;
        }
        final List<Account> accounts;
        final List<Entry> contacts;
        if (isPicker()) {
            final var account =
                    service.findAccountByJid(Jid.of(requireArguments().getString(ACCOUNT_KEY)));
            accounts =
                    account != null && isSearchable(account)
                            ? ImmutableList.of(account)
                            : ImmutableList.of();
            contacts = account == null ? ImmutableList.of() : findContacts(account, term);
        } else {
            accounts = getSearchableAccounts(service);
            contacts = ImmutableList.of();
        }
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
                        onSearchResults(generation, accounts.size() > 1, contacts, lists);
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        onSearchResults(generation, false, contacts, ImmutableList.of());
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

    // when picking people, existing contacts match too: people on other servers aren't in the
    // directory
    private static List<Entry> findContacts(final Account account, final String term) {
        final var needle = term.toLowerCase(Locale.ROOT);
        final var entries = new ImmutableList.Builder<Entry>();
        for (final var contact : account.getRoster().getContacts()) {
            final var address = contact.getAddress().asBareJid();
            if (!contact.showInContactList() || contact.isSelf() || address.isDomainJid()) {
                continue;
            }
            final var name = contact.getDisplayName();
            if (address.toString().toLowerCase(Locale.ROOT).contains(needle)
                    || (name != null && name.toLowerCase(Locale.ROOT).contains(needle))) {
                entries.add(new Entry(account, new UserSearchManager.Result(address, name, null)));
            }
        }
        return entries.build();
    }

    private void onSearchResults(
            final int generation,
            final boolean showAccount,
            final List<Entry> contacts,
            final List<List<Entry>> lists) {
        if (generation != this.generation || binding == null || !isAdded()) {
            return;
        }
        binding.progress.setVisibility(View.INVISIBLE);
        // the same person can be both a contact and in the directory
        final var merged = new LinkedHashMap<String, Entry>();
        for (final var entry :
                Iterables.concat(
                        contacts, Iterables.concat(Iterables.filter(lists, Objects::nonNull)))) {
            final var key = entry.account().getJid().asBareJid() + "\0" + entry.result().address();
            final var existing = merged.get(key);
            if (existing == null
                    || (existing.result().name() == null && entry.result().name() != null)) {
                merged.put(key, entry);
            }
        }
        final var entries = new ArrayList<>(merged.values());
        entries.sort(
                Comparator.comparing(e -> e.result().getDisplayName().toLowerCase(Locale.ROOT)));
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

    private void toggle(final UserSearchManager.Result result) {
        if (selected.remove(result.address()) == null) {
            selected.put(result.address(), result);
        }
        updateSelection();
    }

    private void updateSelection() {
        if (binding == null || !isPicker()) {
            return;
        }
        final var add = binding.toolbar.getMenu().findItem(R.id.action_add);
        if (add != null) {
            add.setEnabled(!selected.isEmpty());
        }
        binding.selectedCount.setText(
                getResources()
                        .getQuantityString(
                                R.plurals.users_selected, selected.size(), selected.size()));
        final var inflater = LayoutInflater.from(binding.selected.getContext());
        binding.selected.removeAllViews();
        for (final var result : selected.values()) {
            final var chip =
                    (Chip)
                            inflater.inflate(
                                    R.layout.item_user_search_chip, binding.selected, false);
            chip.setText(result.getDisplayName());
            chip.setOnCloseIconClickListener(v -> toggle(result));
            binding.selected.addView(chip);
        }
        binding.selectedRow.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        // keep the latest pick in view; fullScroll() would take the focus off the search field
        binding.selectedScroll.post(
                () -> {
                    if (binding != null) {
                        binding.selectedScroll.smoothScrollTo(binding.selected.getWidth(), 0);
                    }
                });
        adapter.notifyDataSetChanged();
    }

    private void showEnterJidDialog() {
        final var account = requireArguments().getString(ACCOUNT_KEY);
        final var accounts = new ArrayList<String>();
        accounts.add(account);
        EnterJidDialog.newInstance(
                        accounts,
                        getString(R.string.enter_contact),
                        getString(R.string.select),
                        null,
                        account,
                        true,
                        false)
                .show(getChildFragmentManager(), ENTER_JID_TAG);
    }

    private boolean onAddressEntered(final Jid account, final Jid contact)
            throws EnterJidDialog.JidError {
        final var address = contact.asBareJid();
        if (members.contains(address)) {
            throw new EnterJidDialog.JidError(getString(R.string.already_in_this_chat));
        }
        if (!selected.containsKey(address)) {
            toggle(new UserSearchManager.Result(address, null, null));
        }
        return true;
    }

    private void invite() {
        if (selected.isEmpty()
                || !(getActivity() instanceof XmppActivity activity)
                || activity.xmppConnectionService == null) {
            return;
        }
        // the same result ChooseContactActivity hands back, so the invite goes the same way
        final var data = new Intent();
        data.putExtra(
                ChooseContactActivity.EXTRA_CONVERSATION,
                requireArguments().getString(CONVERSATION_KEY));
        data.putExtra(ChooseContactActivity.EXTRA_SELECT_MULTIPLE, true);
        data.putExtra(
                "contacts",
                Collections2.transform(selected.keySet(), Jid::toString).toArray(new String[0]));
        final var invite = XmppActivity.ConferenceInvite.parse(data);
        if (invite != null && invite.execute(activity)) {
            activity.mToast =
                    Toast.makeText(activity, R.string.creating_conference, Toast.LENGTH_LONG);
            activity.mToast.show();
        }
        dismiss();
    }

    private void createGroup() {
        if (selected.isEmpty()
                || !(getActivity() instanceof XmppActivity activity)
                || activity.xmppConnectionService == null) {
            return;
        }
        final var arguments = requireArguments();
        final var account =
                activity.xmppConnectionService.findAccountByJid(
                        Jid.of(arguments.getString(ACCOUNT_KEY)));
        if (account == null) {
            return;
        }
        // as StartConversationActivity does with the participants from ChooseContactActivity
        final var future =
                activity.xmppConnectionService.createAdhocConference(
                        account,
                        arguments.getString(GROUP_NAME_KEY),
                        ImmutableList.copyOf(selected.keySet()));
        Futures.addCallback(
                future, activity.adhocCallback, ContextCompat.getMainExecutor(activity));
        // when it's an immediate failure do not display 'creating group chat'
        if (!future.isDone()) {
            activity.mToast =
                    Toast.makeText(activity, R.string.creating_conference, Toast.LENGTH_LONG);
            activity.mToast.show();
        }
        dismiss();
    }

    private Listener getListener() {
        return getActivity() instanceof Listener listener ? listener : null;
    }

    public interface Listener {
        void onManualAddressSelected();

        void onUserSelected(Account account, UserSearchManager.Result result);
    }

    private record Entry(Account account, UserSearchManager.Result result) {}

    private class ResultAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_RESULT = 0;
        private static final int TYPE_LOAD_MORE = 1;

        private List<Entry> entries = ImmutableList.of();
        private boolean showAccount = false;
        // how many of the entries are shown; a 'Load more' row follows them while there are more
        private int limit = PAGE_SIZE;

        public void setEntries(final List<Entry> entries, final boolean showAccount) {
            this.entries = entries;
            this.showAccount = showAccount;
            this.limit = PAGE_SIZE;
            notifyDataSetChanged();
        }

        private int getShownCount() {
            return Math.min(limit, entries.size());
        }

        private boolean hasMore() {
            return entries.size() > limit;
        }

        private void loadMore() {
            // a second tap while the row goes away
            if (!hasMore()) {
                return;
            }
            final int shown = getShownCount();
            limit += PAGE_SIZE;
            // the 'Load more' row makes way for the next results, and comes back after them if
            // there are still more
            notifyItemRemoved(shown);
            notifyItemRangeInserted(shown, getItemCount() - shown);
        }

        @Override
        public int getItemViewType(final int position) {
            return position < getShownCount() ? TYPE_RESULT : TYPE_LOAD_MORE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(
                @NonNull final ViewGroup parent, final int viewType) {
            final var inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_LOAD_MORE) {
                final var view =
                        inflater.inflate(R.layout.item_user_search_load_more, parent, false);
                view.findViewById(R.id.load_more).setOnClickListener(v -> loadMore());
                return new LoadMoreViewHolder(view);
            }
            return new ResultViewHolder(
                    ItemUserSearchResultBinding.inflate(inflater, parent, false));
        }

        @Override
        public void onBindViewHolder(
                @NonNull final RecyclerView.ViewHolder viewHolder, final int position) {
            // the 'Load more' row has nothing to bind
            if (!(viewHolder instanceof ResultViewHolder holder)) {
                return;
            }
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
            final boolean member = isPicker() && members.contains(result.address());
            if (showAccount) {
                binding.note.setText(
                        context.getString(
                                R.string.user_search_via_account,
                                entry.account().getJid().asBareJid().toString()));
                binding.note.setVisibility(View.VISIBLE);
            } else if (member) {
                binding.note.setText(R.string.already_in_this_chat);
                binding.note.setVisibility(View.VISIBLE);
            } else {
                binding.note.setVisibility(View.GONE);
            }
            if (!isPicker()) {
                binding.select.setVisibility(View.GONE);
                binding.getRoot().setOnClickListener(v -> onEntrySelected(entry));
                return;
            }
            if (member) {
                binding.select.setVisibility(View.GONE);
                binding.getRoot().setOnClickListener(null);
                binding.getRoot().setClickable(false);
                return;
            }
            final boolean isSelected = selected.containsKey(result.address());
            binding.select.setVisibility(View.VISIBLE);
            binding.select.setIconResource(
                    isSelected ? R.drawable.ic_check_24dp : R.drawable.ic_add_24dp);
            binding.select.setIconTint(
                    isSelected
                            ? ColorStateList.valueOf(
                                    MaterialColors.getColor(
                                            binding.select, androidx.appcompat.R.attr.colorPrimary))
                            : holder.defaultIconTint);
            binding.select.setContentDescription(
                    context.getString(isSelected ? R.string.deselect : R.string.select));
            binding.select.setOnClickListener(v -> toggle(result));
            binding.getRoot().setOnClickListener(v -> toggle(result));
        }

        @Override
        public int getItemCount() {
            return getShownCount() + (hasMore() ? 1 : 0);
        }
    }

    private static class LoadMoreViewHolder extends RecyclerView.ViewHolder {

        private LoadMoreViewHolder(final View view) {
            super(view);
        }
    }

    private static class ResultViewHolder extends RecyclerView.ViewHolder {

        private final ItemUserSearchResultBinding binding;
        private final ColorStateList defaultIconTint;

        private ResultViewHolder(final ItemUserSearchResultBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            this.defaultIconTint = binding.select.getIconTint();
        }
    }
}
