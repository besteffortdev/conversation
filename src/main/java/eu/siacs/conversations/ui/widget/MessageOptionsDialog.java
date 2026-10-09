package eu.siacs.conversations.ui.widget;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentDialog;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.color.MaterialColors;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogMessageOptionsBinding;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Reaction;
import eu.siacs.conversations.ui.AddReactionActivity;
import im.conversations.android.xmpp.model.reactions.Restrictions;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shown when long pressing a message: the message bubble moves to the middle of the screen with a
 * row of reactions above and the message options below it.
 */
public class MessageOptionsDialog {

    private static final long ANIMATION_DURATION = 200;
    private static final float MAX_SNAPSHOT_HEIGHT = 0.45f;

    private final Activity activity;
    private final Message message;
    private final View bubble;
    private final List<MenuItem> options;
    private final Consumer<MenuItem> onOptionSelected;
    private final boolean showReactions;
    private final Consumer<Collection<String>> onReactionsChanged;

    private final int[] bubbleLocation = new int[2];
    private ComponentDialog dialog;
    private DialogMessageOptionsBinding binding;
    private boolean dismissing = false;

    public MessageOptionsDialog(
            final Activity activity,
            final Message message,
            final View bubble,
            final List<MenuItem> options,
            final Consumer<MenuItem> onOptionSelected,
            final boolean showReactions,
            final Consumer<Collection<String>> onReactionsChanged) {
        this.activity = activity;
        this.message = message;
        this.bubble = bubble;
        this.options = options;
        this.onOptionSelected = onOptionSelected;
        this.showReactions = showReactions;
        this.onReactionsChanged = onReactionsChanged;
    }

    public void show() {
        this.dialog = new ComponentDialog(activity);
        this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.binding =
                DataBindingUtil.inflate(
                        LayoutInflater.from(dialog.getContext()),
                        R.layout.dialog_message_options,
                        null,
                        false);
        this.dialog.setContentView(binding.getRoot());
        final Window window = this.dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.6f);
            window.setWindowAnimations(android.R.style.Animation_Toast);
        }
        this.dialog
                .getOnBackPressedDispatcher()
                .addCallback(
                        new OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                dismissAnimated(null);
                            }
                        });
        this.binding.root.setOnClickListener(v -> dismissAnimated(null));

        final Bitmap snapshot = snapshot(bubble);
        bubble.getLocationOnScreen(bubbleLocation);
        layout(snapshot);
        if (showReactions) {
            addReactions();
        } else {
            binding.reactionsCard.setVisibility(View.GONE);
        }
        if (options.isEmpty()) {
            binding.optionsCard.setVisibility(View.GONE);
        } else {
            addOptions();
        }

        binding.getRoot()
                .getViewTreeObserver()
                .addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                binding.getRoot()
                                        .getViewTreeObserver()
                                        .removeOnPreDrawListener(this);
                                animateIn();
                                return true;
                            }
                        });
        this.dialog.setOnDismissListener(d -> bubble.setAlpha(1f));
        this.dialog.show();
        if (snapshot != null) {
            bubble.setAlpha(0f);
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private void layout(@Nullable final Bitmap snapshot) {
        final var resources = activity.getResources();
        final int parentWidth = activity.getWindow().getDecorView().getWidth();
        final int gutter = resources.getDimensionPixelSize(R.dimen.message_options_gutter);
        final int[] bubbleInWindow = new int[2];
        bubble.getLocationInWindow(bubbleInWindow);
        final int insetLeft = bubbleInWindow[0];
        final int insetRight = parentWidth - bubbleInWindow[0] - bubble.getWidth();
        // received messages hug the start of the screen, sent messages the end
        final boolean alignLeft = insetLeft <= insetRight;

        final var snapshotView = binding.messageSnapshot;
        if (snapshot == null) {
            snapshotView.setMinimumWidth(bubble.getWidth());
            snapshotView.setMinimumHeight(bubble.getHeight());
        } else {
            snapshotView.setImageBitmap(snapshot);
        }
        snapshotView.setMaxHeight(
                (int) (resources.getDisplayMetrics().heightPixels * MAX_SNAPSHOT_HEIGHT));
        final var snapshotParams = (LinearLayout.LayoutParams) snapshotView.getLayoutParams();
        snapshotParams.gravity = alignLeft ? Gravity.LEFT : Gravity.RIGHT;
        snapshotParams.leftMargin = alignLeft ? insetLeft : 0;
        snapshotParams.rightMargin = alignLeft ? 0 : insetRight;
        snapshotView.setLayoutParams(snapshotParams);

        final int emojiSize = resources.getDimensionPixelSize(R.dimen.message_options_emoji_size);
        final int reactionsPadding =
                resources.getDimensionPixelSize(R.dimen.message_options_reactions_padding);
        final int reactionsWidth = getShortcutCount() * emojiSize + 2 * reactionsPadding;
        align(
                binding.reactionsCard,
                reactionsWidth,
                alignLeft,
                alignLeft ? insetLeft : insetRight,
                parentWidth,
                gutter);

        final int optionsWidth =
                Math.min(
                        resources.getDimensionPixelSize(R.dimen.message_options_width),
                        parentWidth - 2 * gutter);
        binding.optionsCard.getLayoutParams().width = optionsWidth;
        align(
                binding.optionsCard,
                optionsWidth,
                alignLeft,
                alignLeft ? insetLeft : insetRight,
                parentWidth,
                gutter);
    }

    private static void align(
            final View view,
            final int width,
            final boolean alignLeft,
            final int inset,
            final int parentWidth,
            final int gutter) {
        final var params = (LinearLayout.LayoutParams) view.getLayoutParams();
        final int margin = Math.max(gutter, Math.min(inset, parentWidth - width - gutter));
        params.gravity = alignLeft ? Gravity.LEFT : Gravity.RIGHT;
        params.leftMargin = alignLeft ? margin : 0;
        params.rightMargin = alignLeft ? 0 : margin;
        view.setLayoutParams(params);
    }

    private void animateIn() {
        // the window of the dialog might be offset from the activity window
        final int[] decorLocation = new int[2];
        activity.getWindow().getDecorView().getLocationOnScreen(decorLocation);
        final int[] rootLocation = new int[2];
        binding.root.getLocationOnScreen(rootLocation);
        binding.column.setTranslationX(decorLocation[0] - rootLocation[0]);

        final int[] snapshotLocation = new int[2];
        binding.messageSnapshot.getLocationOnScreen(snapshotLocation);
        binding.messageSnapshot.setTranslationY(bubbleLocation[1] - snapshotLocation[1]);
        binding.messageSnapshot
                .animate()
                .translationY(0)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        popIn(binding.reactionsCard, binding.reactionsCard.getHeight());
        popIn(binding.optionsCard, 0);
    }

    private static void popIn(final View view, final float pivotY) {
        final var params = (LinearLayout.LayoutParams) view.getLayoutParams();
        view.setPivotX(params.gravity == Gravity.LEFT ? 0 : view.getWidth());
        view.setPivotY(pivotY);
        view.setAlpha(0f);
        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void dismissAnimated(@Nullable final Runnable afterDismiss) {
        if (dismissing) {
            return;
        }
        dismissing = true;
        final int[] snapshotLocation = new int[2];
        binding.messageSnapshot.getLocationOnScreen(snapshotLocation);
        final float restingY = snapshotLocation[1] - binding.messageSnapshot.getTranslationY();
        binding.reactionsCard.animate().alpha(0f).setDuration(ANIMATION_DURATION).start();
        binding.optionsCard.animate().alpha(0f).setDuration(ANIMATION_DURATION).start();
        binding.messageSnapshot
                .animate()
                .translationY(bubbleLocation[1] - restingY)
                .setDuration(ANIMATION_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(
                        () -> {
                            bubble.setAlpha(1f);
                            dismiss();
                            if (afterDismiss != null) {
                                afterDismiss.run();
                            }
                        })
                .start();
    }

    private Collection<String> getShortcutEmojis() {
        final Conversational conversation = message.getConversation();
        final Restrictions restrictions =
                conversation instanceof Conversation c
                                && conversation.getMode() == Conversational.MODE_MULTI
                        ? c.getMucOptions().getReactionsRestrictions()
                        : null;
        if (isEmojiChoiceRestricted(restrictions)) {
            return restrictions.allowList();
        }
        return Reaction.SUGGESTIONS;
    }

    private boolean isEmojiChoiceRestricted() {
        final Conversational conversation = message.getConversation();
        return conversation instanceof Conversation c
                && conversation.getMode() == Conversational.MODE_MULTI
                && isEmojiChoiceRestricted(c.getMucOptions().getReactionsRestrictions());
    }

    private static boolean isEmojiChoiceRestricted(@Nullable final Restrictions restrictions) {
        return restrictions != null
                && restrictions.allowList() != null
                && !restrictions.allowList().isEmpty()
                && restrictions.allowList().size() <= 6;
    }

    private int getShortcutCount() {
        return getShortcutEmojis().size() + (isEmojiChoiceRestricted() ? 0 : 1);
    }

    private void addReactions() {
        final var context = binding.reactions.getContext();
        final int size =
                context.getResources().getDimensionPixelSize(R.dimen.message_options_emoji_size);
        final Set<String> ourReactions = message.getAggregatedReactions().ourReactions;
        for (final String emoji : getShortcutEmojis()) {
            final TextView emojiView = new TextView(context);
            emojiView.setText(emoji);
            emojiView.setGravity(Gravity.CENTER);
            emojiView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            if (ourReactions.contains(emoji)) {
                final GradientDrawable selected = new GradientDrawable();
                selected.setShape(GradientDrawable.OVAL);
                selected.setColor(
                        MaterialColors.getColor(
                                emojiView,
                                com.google.android.material.R.attr.colorSecondaryContainer));
                emojiView.setBackground(selected);
            } else {
                emojiView.setBackgroundResource(getSelectableItemBackgroundBorderless());
            }
            emojiView.setOnClickListener(v -> onEmojiClicked(emoji));
            binding.reactions.addView(emojiView, new LinearLayout.LayoutParams(size, size));
        }
        if (isEmojiChoiceRestricted()) {
            return;
        }
        final ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.ic_add_reaction_24dp);
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setContentDescription(context.getString(R.string.more_reactions));
        ImageViewCompat.setImageTintList(
                more,
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                more, com.google.android.material.R.attr.colorOnSurfaceVariant)));
        more.setBackgroundResource(getSelectableItemBackgroundBorderless());
        more.setOnClickListener(v -> dismissAnimated(this::openEmojiPicker));
        binding.reactions.addView(more, new LinearLayout.LayoutParams(size, size));
    }

    private int getSelectableItemBackgroundBorderless() {
        final TypedValue typedValue = new TypedValue();
        binding.reactions
                .getContext()
                .getTheme()
                .resolveAttribute(
                        android.R.attr.selectableItemBackgroundBorderless, typedValue, true);
        return typedValue.resourceId;
    }

    private void onEmojiClicked(final String emoji) {
        final Set<String> ourReactions = message.getAggregatedReactions().ourReactions;
        if (ourReactions.contains(emoji)) {
            onReactionsChanged.accept(
                    ImmutableSet.copyOf(Collections2.filter(ourReactions, r -> !r.equals(emoji))));
        } else {
            onReactionsChanged.accept(
                    new ImmutableSet.Builder<String>().addAll(ourReactions).add(emoji).build());
        }
        dismissAnimated(null);
    }

    private void openEmojiPicker() {
        final var intent = new Intent(activity, AddReactionActivity.class);
        intent.putExtra("conversation", message.getConversation().getUuid());
        intent.putExtra("message", message.getUuid());
        activity.startActivity(intent);
    }

    private void addOptions() {
        final var inflater = LayoutInflater.from(binding.options.getContext());
        for (final MenuItem item : options) {
            final TextView option =
                    (TextView)
                            inflater.inflate(R.layout.item_message_option, binding.options, false);
            option.setText(item.getTitle());
            option.setOnClickListener(v -> dismissAnimated(() -> onOptionSelected.accept(item)));
            binding.options.addView(option);
        }
    }

    @Nullable
    private static Bitmap snapshot(final View view) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        try {
            final Bitmap bitmap =
                    Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            // software drawing ignores clipToOutline; clip to the rounded bubble manually
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && view.getClipToOutline()
                    && view.getOutlineProvider() != null) {
                final Outline outline = new Outline();
                view.getOutlineProvider().getOutline(view, outline);
                final Rect bounds = new Rect();
                final float radius = outline.getRadius();
                if (outline.getRect(bounds) && radius >= 0) {
                    final Path path = new Path();
                    path.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
                    canvas.clipPath(path);
                }
            }
            view.draw(canvas);
            return bitmap;
        } catch (final RuntimeException e) {
            // for example hardware bitmaps can not be drawn onto a software canvas
            Log.d(Config.LOGTAG, "unable to take snapshot of message", e);
            return null;
        }
    }
}
