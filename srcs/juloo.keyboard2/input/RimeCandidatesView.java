package juloo.keyboard2.input;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
  private final TextView language, preedit, cancel, expand;
  private final LinearLayout candidates;
  private final HorizontalScrollView compactScroll;
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
  private long requestedRevision = -1;
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
    boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    rowHeight = Math.max(dp(landscape?36:44), (int)Math.ceil(probe.getPaint().getFontSpacing()) + dp(4));
    LinearLayout top = new LinearLayout(context);
    top.setGravity(Gravity.CENTER_VERTICAL);
    int topHeight = Math.max(dp(landscape?28:32), (int)Math.ceil(text(18).getPaint().getFontSpacing()) + dp(2));
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
    compactScroll=new HorizontalScrollView(context) {
      @Override protected void onScrollChanged(int x,int y,int oldX,int oldY)
      {super.onScrollChanged(x,y,oldX,oldY);maybeLoadMore();}
    };
    compactScroll.setId(R.id.miancak_candidate_scroll);compactScroll.setHorizontalScrollBarEnabled(false);
    candidates=new LinearLayout(context);candidates.setId(R.id.miancak_compact_candidates);
    compactScroll.addView(candidates,new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.MATCH_PARENT));
    row.addView(compactScroll,new LayoutParams(0,LayoutParams.MATCH_PARENT,1));
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
    expandedScroll = new ScrollView(getContext()) {
      @Override protected void onScrollChanged(int x,int y,int oldX,int oldY)
      {super.onScrollChanged(x,y,oldX,oldY);maybeLoadMore();}
    };
    expandedScroll.setId(R.id.miancak_expanded_candidates);
    expandedScroll.setFillViewport(true);
    expandedScroll.setOnApplyWindowInsetsListener((view, insets) -> {
      view.setPadding(0,0,0,insets.getSystemWindowInsetBottom());
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
    int available = Math.max(0, MeasureSpec.getSize(widthSpec) - dp(40));
    if (available != compactWidth) { compactWidth = available; renderCandidates(); }
    super.onMeasure(widthSpec, heightSpec);
  }

  public void show(InputSnapshot snapshot)
  {
    boolean continuation=shown!=null && shown.generation==snapshot.generation
      && shown.engine.preedit.equals(snapshot.engine.preedit)
      && snapshot.engine.candidates.size()>=shown.engine.candidates.size()
      && snapshot.engine.candidates.subList(0,shown.engine.candidates.size()).equals(shown.engine.candidates);
    shown=snapshot; requestedRevision=-1;
    if(!continuation){compactScroll.scrollTo(0,0);if(expandedScroll!=null)expandedScroll.scrollTo(0,0);}
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
    compactScroll.scrollTo(0,0);if(expandedScroll!=null)expandedScroll.scrollTo(0,0);
    updateExpansion();renderCandidates();post(this::maybeLoadMore);
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
    int available=compactWidth;
    ArrayList<TextView> cells=new ArrayList<>();int[] widths=new int[shown.engine.candidates.size()];
    for(int i=0;i<widths.length;i++)
    {
      TextView cell=candidate(shown,i,false);
      cell.measure(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY));
      widths[i]=cell.getMeasuredWidth();cells.add(cell);
    }
    int count=expanded?CandidateRowFitter.count(available,widths):widths.length;
    for(int i=0;i<count;i++)candidates.addView(cells.get(i),new LayoutParams(Math.min(widths[i],Math.max(1,available)),LayoutParams.MATCH_PARENT));
    if(expandedFlow!=null)
    {
      expandedFlow.removeAllViews();
      if(expanded)for(int i=count;i<shown.engine.candidates.size();i++)expandedFlow.addView(candidate(shown,i,true));
    }
    post(this::maybeLoadMore);
  }

  private void maybeLoadMore()
  {
    InputSnapshot snapshot=shown;
    if(controller==null || snapshot==null || !snapshot.engine.composing || snapshot.engine.lastPage
        || snapshot.engine.candidates.isEmpty() || requestedRevision==snapshot.revision)return;
    boolean nearEnd=expanded?expandedFlow.getHeight()<=expandedScroll.getScrollY()+expandedScroll.getHeight()+rowHeight
      :candidates.getWidth()<=compactScroll.getScrollX()+compactScroll.getWidth()+dp(40);
    if(nearEnd){requestedRevision=snapshot.revision;controller.moreCandidates(snapshot);}
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
    cell.setPadding(dp(6), full ? dp(3) : 0, dp(6), full ? dp(3) : 0);
    cell.setMinWidth(0);
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
    private final ArrayList<int[]> rows=new ArrayList<>();
    @Override protected void onMeasure(int widthSpec,int heightSpec)
    {
      int width=MeasureSpec.getSize(widthSpec),used=0,start=0,height=0;rows.clear();
      for(int i=0;i<getChildCount();i++)
      {
        View child=getChildAt(i);child.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        if(i>start && used+child.getMeasuredWidth()>width){height+=finishRow(start,i,used,width);start=i;used=0;}
        used+=child.getMeasuredWidth();
      }
      if(start<getChildCount())height+=finishRow(start,getChildCount(),used,width);
      setMeasuredDimension(resolveSize(width,widthSpec),resolveSize(height,heightSpec));
    }
    private int finishRow(int start,int end,int used,int width)
    {
      int extra=Math.max(0,width-used),count=end-start,height=0;
      for(int i=start;i<end;i++)
      {
        View child=getChildAt(i);int allocated=child.getMeasuredWidth()+extra/count+(i-start<extra%count?1:0);
        child.measure(MeasureSpec.makeMeasureSpec(allocated,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        height=Math.max(height,child.getMeasuredHeight());
      }
      rows.add(new int[]{start,end,height});return height;
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b)
    {
      int y=0;
      for(int[] row:rows)
      {
        int x=0;
        for(int i=row[0];i<row[1];i++)
        {View child=getChildAt(i);int w=child.getMeasuredWidth();child.layout(x,y,x+w,y+row[2]);x+=w;}
        y+=row[2];
      }
    }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams()
    {
      return new ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
  }
}
