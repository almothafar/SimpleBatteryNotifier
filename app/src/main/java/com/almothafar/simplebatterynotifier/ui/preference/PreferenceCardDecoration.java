package com.almothafar.simplebatterynotifier.ui.preference;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.almothafar.simplebatterynotifier.R;

import java.util.Set;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Draws the "card group" (Material You) look on a preference list (#222): consecutive preferences
 * that share a parent are painted as one rounded, filled card, and each {@link PreferenceCategory}
 * title is left as a plain section label sitting on the neutral background above its card.
 * <p>
 * The whole look is a paint-time decoration — no per-preference custom layouts and no new dependency.
 * Every row is inset horizontally (via {@link #getItemOffsets}) so the row content and the card
 * outline share one margin; a gap is added above each new section and below the last row so the cards
 * read as separate blocks with breathing room top and bottom. A screen can also list keys in
 * {@code breakBeforeKeys} to force a preference onto its own card even when it shares a parent — used
 * for the root screen's About/Version footer. Row backgrounds are the framework's transparent
 * {@code selectableItemBackground}, so the card painted behind them shows through (and taps ripple).
 */
public class PreferenceCardDecoration extends RecyclerView.ItemDecoration {

	private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF cardRect = new RectF();
	private final float cornerRadius;
	private final int horizontalMargin;
	private final int groupGap;
	private final Set<String> breakBeforeKeys;

	/**
	 * @param context         context used to resolve the card colour and spacing dimensions
	 * @param breakBeforeKeys preference keys that always start a new card, even within one parent
	 */
	public PreferenceCardDecoration(Context context, Set<String> breakBeforeKeys) {
		final Resources resources = context.getResources();
		cardPaint.setStyle(Paint.Style.FILL);
		cardPaint.setColor(ContextCompat.getColor(context, R.color.settings_group_card));
		cornerRadius = resources.getDimension(R.dimen.settings_card_corner_radius);
		horizontalMargin = resources.getDimensionPixelSize(R.dimen.settings_card_horizontal_margin);
		groupGap = resources.getDimensionPixelSize(R.dimen.settings_card_group_gap);
		this.breakBeforeKeys = breakBeforeKeys;
	}

	/**
	 * Apply the card-group look to a preference fragment's list, with no forced card breaks.
	 *
	 * @param fragment the preference fragment whose divider is being cleared
	 * @param list     the fragment's preference RecyclerView
	 */
	public static void apply(PreferenceFragmentCompat fragment, RecyclerView list) {
		apply(fragment, list, Set.of());
	}

	/**
	 * Apply the card-group look: paint a neutral background behind the list, drop the default row
	 * dividers (the cards provide the grouping instead), and attach the decoration.
	 *
	 * @param fragment        the preference fragment whose divider is being cleared
	 * @param list            the fragment's preference RecyclerView
	 * @param breakBeforeKeys preference keys that should each start their own card (e.g. a footer)
	 */
	public static void apply(PreferenceFragmentCompat fragment, RecyclerView list, Set<String> breakBeforeKeys) {
		final Context context = list.getContext();
		list.setBackgroundColor(ContextCompat.getColor(context, R.color.settings_group_background));
		fragment.setDivider(null);
		fragment.setDividerHeight(0);
		list.addItemDecoration(new PreferenceCardDecoration(context, breakBeforeKeys));
	}

	@Override
	public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
	                           @NonNull RecyclerView.State state) {
		final PreferenceGroupAdapter adapter = adapterOf(parent);
		if (isNull(adapter)) {
			return;
		}
		final int position = parent.getChildAdapterPosition(view);
		if (position == RecyclerView.NO_POSITION) {
			return;
		}
		outRect.left = horizontalMargin;
		outRect.right = horizontalMargin;
		// A gap above every new section so the cards read as separate blocks. The list also opens with
		// a gap when its first row is a card (e.g. the root screen's nav card); a leading category
		// header carries its own top padding, so it needs none.
		if (position == 0) {
			if (isCardRow(adapter, 0)) {
				outRect.top = groupGap;
			}
		} else if (startsNewGroup(adapter, position)) {
			outRect.top = groupGap;
		}
		// Matching breathing room below the last row so the final card floats off the bottom edge.
		if (position == adapter.getItemCount() - 1) {
			outRect.bottom = groupGap;
		}
	}

	@Override
	public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
		final PreferenceGroupAdapter adapter = adapterOf(parent);
		if (isNull(adapter)) {
			return;
		}
		final float left = parent.getPaddingLeft() + horizontalMargin;
		final float right = parent.getWidth() - parent.getPaddingRight() - horizontalMargin;

		final int childCount = parent.getChildCount();
		int i = 0;
		while (i < childCount) {
			final View child = parent.getChildAt(i);
			final int position = parent.getChildAdapterPosition(child);
			if (position == RecyclerView.NO_POSITION || !isCardRow(adapter, position)) {
				i++;
				continue;
			}
			// Extend the run over every following card row in the same group; those rows share one card.
			final PreferenceGroup group = adapter.getItem(position).getParent();
			View lastChild = child;
			int runEnd = i;
			for (int j = i + 1; j < childCount; j++) {
				final View next = parent.getChildAt(j);
				final int nextPosition = parent.getChildAdapterPosition(next);
				if (nextPosition != position + (j - i) || !isCardRow(adapter, nextPosition)
						|| adapter.getItem(nextPosition).getParent() != group
						|| breakBeforeKeys.contains(adapter.getItem(nextPosition).getKey())) {
					break;
				}
				lastChild = next;
				runEnd = j;
			}
			final float top = child.getTop() + child.getTranslationY();
			final float bottom = lastChild.getBottom() + lastChild.getTranslationY();
			cardRect.set(left, top, right, bottom);
			canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, cardPaint);
			i = runEnd + 1;
		}
	}

	/**
	 * A section header sits on the background, so a gap belongs above it; a card row needs a leading
	 * gap when it opens a new group — a forced footer break, a different parent, or the list start —
	 * but not when a header directly above it has already spaced it.
	 *
	 * @param adapter  the preference adapter
	 * @param position the adapter position being measured
	 * @return whether a group gap should precede this position
	 */
	private boolean startsNewGroup(PreferenceGroupAdapter adapter, int position) {
		final Preference pref = adapter.getItem(position);
		if (pref instanceof PreferenceCategory) {
			return true;
		}
		if (breakBeforeKeys.contains(pref.getKey())) {
			return true;
		}
		final Preference previous = adapter.getItem(position - 1);
		if (previous instanceof PreferenceCategory) {
			return false;
		}
		return previous.getParent() != pref.getParent();
	}

	/**
	 * @param adapter  the preference adapter
	 * @param position the adapter position to classify
	 * @return whether the position is a normal preference (a card row), not a category section header
	 */
	private boolean isCardRow(PreferenceGroupAdapter adapter, int position) {
		if (position < 0 || position >= adapter.getItemCount()) {
			return false;
		}
		final Preference pref = adapter.getItem(position);
		return nonNull(pref) && !(pref instanceof PreferenceCategory);
	}

	/**
	 * @param parent the preference RecyclerView
	 * @return the {@link PreferenceGroupAdapter} backing the list, or null if some other adapter is set
	 */
	private PreferenceGroupAdapter adapterOf(RecyclerView parent) {
		final RecyclerView.Adapter<?> adapter = parent.getAdapter();
		if (adapter instanceof PreferenceGroupAdapter groupAdapter) {
			return groupAdapter;
		}
		return null;
	}
}
