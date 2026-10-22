package juloo.keyboard2.input;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;
import juloo.keyboard2.R;
import juloo.keyboard2.Theme;

/** Dedicated fixed-height preedit/candidates, independent of English suggestions. */
public final class RimeCandidatesView extends LinearLayout
{
  private final TextView language, preedit, cancel, previous, next;
  private final LinearLayout candidates;
  private final HorizontalScrollView scroll;
  private final Theme theme;
  private InputController controller;

  public RimeCandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    theme = new Theme(context, attrs);
    setOrientation(VERTICAL);
    setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(76)));
    LinearLayout top = new LinearLayout(context);
    top.setGravity(Gravity.CENTER_VERTICAL);
    addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));
    language = text(15);
    language.setId(R.id.miancak_language);
    language.setTypeface(null, android.graphics.Typeface.BOLD);
    clickable(language);
    top.addView(language, new LayoutParams(dp(72), LayoutParams.MATCH_PARENT));
    preedit = text(15);
    preedit.setId(R.id.miancak_preedit);
    preedit.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    preedit.setSingleLine(true);
    preedit.setEllipsize(TextUtils.TruncateAt.START);
    top.addView(preedit, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    cancel = text(22);
    cancel.setId(R.id.miancak_cancel);
    cancel.setText("×");
    cancel.setContentDescription(context.getString(R.string.miancak_cancel_composition));
    clickable(cancel);
    top.addView(cancel, new LayoutParams(dp(48), LayoutParams.MATCH_PARENT));
    LinearLayout row = new LinearLayout(context);
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)));
    previous = text(26);
    previous.setId(R.id.miancak_previous_candidates);
    previous.setText("‹");
    previous.setContentDescription(context.getString(R.string.miancak_previous));
    clickable(previous);
    row.addView(previous, new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
    scroll = new HorizontalScrollView(context);
    scroll.setHorizontalScrollBarEnabled(false);
    row.addView(scroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    candidates = new LinearLayout(context);
    scroll.addView(candidates, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    next = text(26);
    next.setId(R.id.miancak_next_candidates);
    next.setText("›");
    next.setContentDescription(context.getString(R.string.miancak_next));
    clickable(next);
    row.addView(next, new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
  }

  public void attach(InputController controller)
  {
    this.controller = controller;
    show(controller.snapshot());
  }

  public void show(InputSnapshot snapshot)
  {
    boolean chinese = snapshot.mode == InputRouter.Mode.CHINESE;
    language.setText(chinese ? R.string.miancak_chinese : R.string.miancak_english);
    language.setEnabled(snapshot.engineAllowed);
    language.setAlpha(snapshot.engineAllowed ? 1f : 0.55f);
    language.setContentDescription(getContext().getString(chinese ? R.string.miancak_switch_english : R.string.miancak_switch_chinese));
    language.setOnClickListener(view -> { if (controller != null) controller.submit(SemanticAction.TOGGLE_LANGUAGE); });
    preedit.setText(!snapshot.error.isEmpty() ? getContext().getString(snapshot.engineAllowed ? R.string.miancak_retry : R.string.miancak_refocus)
        : snapshot.engine.composing ? snapshot.engine.preedit
        : getContext().getString(chinese ? R.string.miancak_pinyin : R.string.miancak_direct));
    preedit.setContentDescription(snapshot.engine.composing ? "Pinyin: " + snapshot.engine.preedit : "Input status");
    cancel.setVisibility(snapshot.engine.composing ? View.VISIBLE : View.INVISIBLE);
    cancel.setOnClickListener(view -> { if (controller != null) controller.cancelComposition(); });
    candidates.removeAllViews();
    for (int i = 0; i < snapshot.engine.candidates.size(); i++)
    {
      final int index = i;
      TextView candidate = text(22);
      candidate.setText(snapshot.engine.candidates.get(i));
      candidate.setContentDescription("Candidate " + (i + 1) + ": " + snapshot.engine.candidates.get(i));
      candidate.setPadding(dp(14), 0, dp(14), 0);
      candidate.setMinWidth(dp(64));
      if (i == snapshot.engine.highlighted) candidate.setTypeface(null, android.graphics.Typeface.BOLD);
      clickable(candidate);
      candidate.setOnClickListener(view -> { if (controller != null) controller.select(snapshot, index); });
      candidates.addView(candidate, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }
    scroll.scrollTo(0, 0);
    previous.setEnabled(snapshot.engine.composing);
    next.setEnabled(snapshot.engine.composing && !snapshot.engine.lastPage);
    previous.setAlpha(previous.isEnabled() ? 1f : 0.25f);
    next.setAlpha(next.isEnabled() ? 1f : 0.25f);
    previous.setOnClickListener(view -> { if (controller != null) controller.page(snapshot, true); });
    next.setOnClickListener(view -> { if (controller != null) controller.page(snapshot, false); });
  }

  private TextView text(int size)
  {
    TextView view = new TextView(getContext());
    view.setTextColor(theme.labelColor);
    view.setTextSize(size);
    view.setGravity(Gravity.CENTER);
    return view;
  }
  private void clickable(TextView view)
  {
    view.setClickable(true);
    view.setFocusable(true);
    view.setBackgroundResource(android.R.drawable.list_selector_background);
  }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
