package juloo.keyboard2.input;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.TextUtils;
import java.util.ArrayList;
import juloo.keyboard2.R;
import juloo.keyboard2.Theme;

/** Measured compact candidates; expansion occupies the existing keyboard area. */
public final class RimeCandidatesView extends LinearLayout
{
  private final TextView language, preedit, cancel, previous, next, expand;
  private final LinearLayout candidates;
  private final LinearLayout candidateRow;
  private final Theme theme;
  private final int rowHeight;
  private InputController controller;
  private InputSnapshot shown;
  private View keyboard;
  private ScrollView expandedScroll;
  private CandidateFlow expandedFlow;
  private boolean expanded;
  private int compactWidth;
  private String pageLabel;
  public void setPageLabel(String label)
  {
    pageLabel = label;
    if (shown != null && !shown.initializing && !shown.engine.composing && shown.error.isEmpty())
      preedit.setText(label != null ? label : getContext().getString(shown.mode == InputRouter.Mode.CHINESE ? R.string.miancak_pinyin : R.string.miancak_direct));
    updateCandidateRow();
  }
  private Runnable languageAction, pageAction, expansionChanged;
  public void setLanguageAction(Runnable action) { languageAction = action; }
  public void setPageAction(Runnable action) { pageAction = action; }
  public void setExpansionListener(Runnable action) { expansionChanged = action; }

  public RimeCandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    theme = new Theme(context, attrs);
    setOrientation(VERTICAL);
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    TextView probe = text(26);
    rowHeight = Math.max(dp(44), (int)Math.ceil(probe.getPaint().getFontSpacing()) + dp(8));
    LinearLayout top = new LinearLayout(context);
    top.setGravity(Gravity.CENTER_VERTICAL);
    int topHeight = Math.max(dp(32), (int)Math.ceil(text(22).getPaint().getFontSpacing()) + dp(4));
    addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, topHeight));
    language = text(15);
    language.setId(R.id.miancak_language);
    language.setTypeface(null, android.graphics.Typeface.BOLD);
    clickable(language);
    top.addView(language, new LayoutParams(dp(72), LayoutParams.MATCH_PARENT));
    TextView pages = text(22); pages.setId(R.id.miancak_pages); pages.setText("▦");
    pages.setContentDescription(context.getString(R.string.pages_title)); clickable(pages);
    pages.setOnClickListener(view -> { if (pageAction != null) pageAction.run(); });
    top.addView(pages, new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
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
    LinearLayout row = candidateRow = new LinearLayout(context);
    addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, rowHeight));
    previous = text(26);
    previous.setId(R.id.miancak_previous_candidates);
    previous.setText("‹");
    previous.setContentDescription(context.getString(R.string.miancak_previous));
    clickable(previous);
    row.addView(previous, new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
    candidates = new LinearLayout(context);
    candidates.setId(R.id.miancak_compact_candidates);
    row.addView(candidates, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
    next = text(26);
    next.setId(R.id.miancak_next_candidates);
    next.setText("›");
    next.setContentDescription(context.getString(R.string.miancak_next));
    clickable(next);
    row.addView(next, new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
    expand = text(24);
    expand.setId(R.id.miancak_expand_candidates);
    clickable(expand);
    expand.setOnClickListener(view -> setExpanded(!expanded));
    row.addView(expand, new LayoutParams(dp(40), LayoutParams.MATCH_PARENT));
    updateExpansion();
  }

  public void attachSurface(FrameLayout surface, View keyboard)
  {
    this.keyboard = keyboard;
    expandedScroll = new ScrollView(getContext());
    expandedScroll.setId(R.id.miancak_expanded_candidates);
    expandedScroll.setFillViewport(true);
    expandedScroll.setOnApplyWindowInsetsListener((view, insets) -> {
      view.setPadding(dp(4), dp(4), dp(4), dp(4) + insets.getSystemWindowInsetBottom());
      return insets;
    });
    expandedFlow = new CandidateFlow(getContext());
    expandedScroll.addView(expandedFlow, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    expandedScroll.setVisibility(GONE);
    surface.addView(expandedScroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    updateExpansion();
  }

  public void attach(InputController controller)
  {
    this.controller = controller;
    show(controller.snapshot());
  }

  public boolean isExpanded() { return expanded; }
  public void collapse() { setExpanded(false); }

  @Override protected void onMeasure(int widthSpec, int heightSpec)
  {
    int available = Math.max(0, MeasureSpec.getSize(widthSpec) - dp(120));
    if (available != compactWidth) { compactWidth = available; renderCandidates(); }
    super.onMeasure(widthSpec, heightSpec);
  }

  public void show(InputSnapshot snapshot)
  {
    shown = snapshot;
    updateCandidateRow();
    boolean chinese = snapshot.mode == InputRouter.Mode.CHINESE;
    language.setText(chinese ? R.string.miancak_chinese : R.string.miancak_english);
    language.setEnabled(snapshot.engineAllowed);
    language.setAlpha(snapshot.engineAllowed ? 1f : 0.55f);
    language.setContentDescription(getContext().getString(chinese ? R.string.miancak_switch_english : R.string.miancak_switch_chinese));
    language.setOnClickListener(view -> {
      if (languageAction != null) languageAction.run();
      else if (controller != null) controller.submit(SemanticAction.TOGGLE_LANGUAGE);
    });
    preedit.setText(!snapshot.error.isEmpty() ? getContext().getString(snapshot.engineAllowed ? R.string.miancak_retry : R.string.miancak_refocus)
        : snapshot.initializing ? getContext().getString(R.string.miancak_initializing)
        : snapshot.engine.composing ? snapshot.engine.preedit
        : pageLabel != null ? pageLabel : getContext().getString(chinese ? R.string.miancak_pinyin : R.string.miancak_direct));
    preedit.setContentDescription(snapshot.initializing ? "Input initializing"
        : snapshot.engine.composing ? "Pinyin: " + snapshot.engine.preedit : "Input status");
    cancel.setVisibility(snapshot.engine.composing ? VISIBLE : INVISIBLE);
    cancel.setOnClickListener(view -> { if (controller != null) controller.cancelComposition(); });
    previous.setEnabled(snapshot.engine.composing);
    next.setEnabled(snapshot.engine.composing && !snapshot.engine.lastPage);
    previous.setAlpha(previous.isEnabled() ? 1f : 0.25f);
    next.setAlpha(next.isEnabled() ? 1f : 0.25f);
    previous.setOnClickListener(view -> { if (controller != null) controller.page(snapshot, true); });
    next.setOnClickListener(view -> { if (controller != null) controller.page(snapshot, false); });
    if (!snapshot.engine.composing || snapshot.engine.candidates.isEmpty()) expanded = false;
    updateExpansion();
    renderCandidates();
  }

  private void updateCandidateRow()
  {
    // Auxiliary layouts have no candidate work to do. Reserving this empty
    // second row can push their category header into the landscape status bar.
    // Main text layouts retain a stable height when composition starts/ends.
    if (candidateRow != null)
      candidateRow.setVisibility(pageLabel != null && shown != null
          && !shown.engine.composing && shown.engine.candidates.isEmpty() ? GONE : VISIBLE);
  }

  private void setExpanded(boolean value)
  {
    expanded = value && expandedScroll != null && shown != null && !shown.engine.candidates.isEmpty();
    updateExpansion();
    renderCandidates();
  }

  private void updateExpansion()
  {
    expand.setText(expanded ? "▴" : "▾");
    expand.setContentDescription(getContext().getString(expanded ? R.string.miancak_collapse_candidates : R.string.miancak_expand_candidates));
    expand.setEnabled(shown != null && !shown.engine.candidates.isEmpty());
    expand.setAlpha(expand.isEnabled() ? 1f : 0.25f);
    if (keyboard != null) keyboard.setVisibility(expanded ? INVISIBLE : VISIBLE);
    if (expandedScroll != null) expandedScroll.setVisibility(expanded ? VISIBLE : GONE);
    if (expanded && expandedScroll != null) expandedScroll.requestApplyInsets();
    if (expandedScroll != null && expanded) expandedScroll.bringToFront();
    if (expansionChanged != null) expansionChanged.run();
  }

  private void renderCandidates()
  {
    if (shown == null) return;
    candidates.removeAllViews();
    if (expanded)
    {
      TextView label = text(15);
      label.setText(R.string.miancak_all_candidates);
      candidates.addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
      expandedFlow.removeAllViews();
      for (int i = 0; i < shown.engine.candidates.size(); i++)
        expandedFlow.addView(candidate(shown, i, true));
      expandedScroll.scrollTo(0, 0);
      return;
    }
    int available = compactWidth;
    ArrayList<TextView> cells = new ArrayList<>();
    int[] widths = new int[shown.engine.candidates.size()];
    for (int i = 0; i < widths.length; i++)
    {
      TextView cell = candidate(shown, i, false);
      cell.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
          MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
      widths[i] = cell.getMeasuredWidth();
      cells.add(cell);
    }
    int count = CandidateRowFitter.count(available, widths);
    for (int i = 0; i < count; i++)
      candidates.addView(cells.get(i), new LayoutParams(Math.min(widths[i], available), LayoutParams.MATCH_PARENT));
  }

  private TextView candidate(InputSnapshot snapshot, int index, boolean full)
  {
    TextView cell = text(22);
    String value = snapshot.engine.candidates.get(index);
    cell.setText(value);
    cell.setContentDescription("Candidate " + (index + 1) + ": " + value);
    cell.setSingleLine(false);
    cell.setHorizontallyScrolling(false);
    if (!full) cell.setMaxLines(1);
    cell.setEllipsize(full ? null : TextUtils.TruncateAt.END);
    cell.setPadding(dp(14), full ? dp(6) : 0, dp(14), full ? dp(6) : 0);
    cell.setMinWidth(dp(64));
    cell.setMinHeight(rowHeight);
    if (index == snapshot.engine.highlighted) cell.setTypeface(null, android.graphics.Typeface.BOLD);
    clickable(cell);
    cell.setOnClickListener(view -> {
      collapse();
      if (controller != null) controller.select(snapshot, index);
    });
    return cell;
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

  /** Wraps complete items; overlong text wraps inside one bounded item. */
  private static final class CandidateFlow extends ViewGroup
  {
    CandidateFlow(Context context) { super(context); setLayoutDirection(LAYOUT_DIRECTION_LTR); }
    @Override protected void onMeasure(int widthSpec, int heightSpec)
    {
      int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, row = 0;
      for (int i = 0; i < getChildCount(); i++)
      {
        View child = getChildAt(i);
        child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (x > 0 && x + child.getMeasuredWidth() > width) { y += row; x = 0; row = 0; }
        x += child.getMeasuredWidth();
        row = Math.max(row, child.getMeasuredHeight());
      }
      setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(y + row, heightSpec));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b)
    {
      int width = r - l, x = 0, y = 0, row = 0;
      for (int i = 0; i < getChildCount(); i++)
      {
        View child = getChildAt(i);
        int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
        if (x > 0 && x + w > width) { y += row; x = 0; row = 0; }
        child.layout(x, y, x + w, y + h);
        x += w; row = Math.max(row, h);
      }
    }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams()
    {
      return new ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
  }
}
