package juloo.keyboard2;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.input.PageConfig;
import juloo.keyboard2.input.SymbolCatalog;

/** Visual editing of logical key positions; changes remain a draft until Save. */
public final class PageEditorActivity extends Activity
{
  private static final int[][] DIRECTIONS = {{1,7,2},{5,0,6},{3,8,4}};
  private static final int[] POSITION_IDS = {R.id.miancak_position_c, R.id.miancak_position_nw, R.id.miancak_position_ne,
    R.id.miancak_position_sw, R.id.miancak_position_se, R.id.miancak_position_w, R.id.miancak_position_e,
    R.id.miancak_position_n, R.id.miancak_position_s};
  private String page, original;
  private PageKeyOverrides draft;
  private KeyboardData base;
  private boolean shifted;
  private android.content.SharedPreferences preferences;
  private String[] directionNames;
  private List<PageActions.Action> actions;
  private int scrollX, scrollY;

  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved);
    page = saved == null ? getIntent().getStringExtra("page") : saved.getString("page");
    if (!PageLayouts.editable(page)) { finish(); return; }
    preferences = DirectBootAwarePreferences.get_shared_preferences(this);
    MiancakPreferences.initialize(preferences);
    original = PageKeyOverrides.raw(preferences);
    draft = PageKeyOverrides.load(preferences).copy();
    directionNames = getResources().getStringArray(R.array.page_direction_names);
    actions = PageActions.list(getResources(), true);
    if (saved != null)
      try
      {
        draft = PageKeyOverrides.restore(saved.getString("draft"));
        original = saved.getString("original"); shifted = saved.getBoolean("shifted");
        scrollX = saved.getInt("scrollX"); scrollY = saved.getInt("scrollY");
      }
      catch (Exception ignored) {}
    loadBase();
    render();
  }
  @Override protected void onSaveInstanceState(Bundle state)
  {
    super.onSaveInstanceState(state);
    rememberScroll(); state.putString("page", page); state.putInt("scrollX", scrollX); state.putInt("scrollY", scrollY);
    state.putString("draft", draft.snapshot()); state.putString("original", original); state.putBoolean("shifted", shifted);
  }
  private void loadBase()
  {
    base = PageLayouts.forEditor(this, page, preferences);
    if (!page.startsWith("symbols/")) setTitle(PagePreferences.label(page));
    else
    {
      String[] parts = page.split("/");
      setTitle(getString(PagePreferences.symbolLabel(parts[1])) + " · " + getString(R.string.symbol_page,
          Integer.parseInt(parts[2]) + 1, SymbolCatalog.pageCount(parts[1])));
    }
  }
  private void rememberScroll()
  {
    ScrollView vertical = findViewById(R.id.miancak_editor_vertical);
    HorizontalScrollView horizontal = findViewById(R.id.miancak_editor_keyboard);
    if (vertical != null) scrollY = vertical.getScrollY();
    if (horizontal != null) scrollX = horizontal.getScrollX();
  }
  private Button button(String label, String description, Runnable action)
  {
    Button button = new Button(this); button.setText(label); button.setAllCaps(false);
    button.setContentDescription(description); button.setOnClickListener(view -> action.run());
    return button;
  }
  private void render()
  {
    rememberScroll();
    ScrollView scroll = new ScrollView(this); scroll.setFitsSystemWindows(true);
    scroll.setId(R.id.miancak_editor_vertical);
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
    LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setFitsSystemWindows(true);
    root.setId(R.id.miancak_page_editor); setContentView(root); ActivityInsets.install(this, root);
    LinearLayout actions = new LinearLayout(this);
    actions.addView(button(getString(R.string.page_save), "Save layout", () -> {
      if (!original.equals(PageKeyOverrides.raw(preferences)))
      { error(getString(R.string.page_changed_elsewhere)); return; }
      try { draft.save(preferences); setResult(RESULT_OK); finish(); }
      catch (Exception invalid) { error(invalid.getMessage()); }
    }), new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(button(getString(android.R.string.cancel), "Cancel layout edit", this::finish), new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(actions); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Switch layer = new Switch(this); layer.setText(R.string.page_shift_preview); layer.setChecked(shifted);
    layer.setContentDescription("Shift layer preview");
    layer.setOnCheckedChangeListener((view, checked) -> { shifted = checked; render(); }); content.addView(layer);
    String partner = PageConfig.isVariant(page) ? PageConfig.base(page) : page + "_fn";
    if (PageConfig.IDS.contains(partner))
      content.addView(button(getString(R.string.page_edit_pair, getString(PagePreferences.label(partner))),
          "Edit paired layout: " + partner, () -> { page = partner; loadBase(); render(); }));
    if (SymbolCatalog.validScope(page))
      content.addView(button(getString(R.string.page_symbol_content), "Choose symbol content", this::chooseSymbolContent));
    TextView help = new TextView(this); help.setText(R.string.page_editor_scroll_help); content.addView(help);
    KeyboardData layout = draft.apply(page, base);
    HorizontalScrollView horizontal = new HorizontalScrollView(this); horizontal.setId(R.id.miancak_editor_keyboard);
    LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
    int previewWidth = Math.max(getResources().getDisplayMetrics().widthPixels, Math.round(dp(108) * layout.keysWidth));
    grid.setMinimumWidth(previewWidth);
    horizontal.addView(grid, new android.widget.FrameLayout.LayoutParams(previewWidth, -2)); content.addView(horizontal);
    for (int r = 0; r < layout.rows.size(); r++)
    {
      LinearLayout row = new LinearLayout(this);
      row.setContentDescription("Editor row: " + page + ":" + r);
      for (int c = 0; c < layout.rows.get(r).keys.size(); c++)
      {
        KeyboardData.Key key = layout.rows.get(r).keys.get(c);
        if (key.shift > 0) row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, key.shift));
        final int rowIndex = r, col = c;
        LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
        Button palette = new Button(this);
        cell.setBackground(palette.getBackground()); cell.setClickable(true); cell.setFocusable(true);
        cell.setContentDescription("Edit key: " + page + ":" + r + ":" + c); cell.setOnClickListener(view -> editKey(rowIndex, col));
        for (int[] line : DIRECTIONS)
        {
          LinearLayout slots = new LinearLayout(this);
          for (int direction : line)
          {
            TextView text = new TextView(this); String full = label(key.keys[direction]);
            text.setTextColor(palette.getTextColors());
            text.setText(full.codePointCount(0, full.length()) > 8 ? full.substring(0, full.offsetByCodePoints(0, 7)) + "…" : full);
            text.setTextSize(direction == 0 ? 12 : 9); text.setMaxLines(2); text.setGravity(android.view.Gravity.CENTER);
            text.setContentDescription(getString(R.string.page_position_accessibility, directionNames[direction], full, status(key, r, c, direction)));
            if (draft.action(page, r, c, direction, shifted) != null) text.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            if (LayoutControls.shared(key, direction)) text.setAlpha(.6f);
            slots.addView(text, new LinearLayout.LayoutParams(0, dp(40), 1));
          }
          cell.addView(slots);
        }
        row.addView(cell, new LinearLayout.LayoutParams(0, -2, key.width));
      }
      float gap = layout.keysWidth - layout.rows.get(r).keysWidth;
      if (gap > 0) row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, gap));
      // HorizontalScrollView measures its child with unspecified width. Give
      // every row a real width so weight-only keys cannot collapse to labels.
      grid.addView(row, new LinearLayout.LayoutParams(previewWidth, -2));
    }
    TextView description = new TextView(this);
    description.setText(page.equals("emoji") || page.equals("clipboard") ? R.string.page_dynamic_controls : R.string.page_editor_help);
    content.addView(description);
    content.addView(button(getString(R.string.page_reset_layout), "Reset this layout", () ->
      new AlertDialog.Builder(this).setMessage(R.string.page_reset_layout_confirm)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
            (dialog, which) -> { draft.reset(page); render(); }).show()));
    scroll.post(() -> scroll.scrollTo(0, scrollY)); horizontal.post(() -> horizontal.scrollTo(scrollX, 0));
  }
  private String label(KeyValue value)
  {
    value = KeyModifier.preview(value, shifted, base.modmap);
    if (value == null) return "·";
    for (PageActions.Action action : actions) if (PageActions.same(value, action.value)) return action.label;
    String text = value.getKind() == KeyValue.Kind.Literal || value.getKind() == KeyValue.Kind.Engine ? value.getSemanticText() : value.getString();
    return text.isEmpty() ? "·" : text;
  }
  private String status(KeyboardData.Key key, int row, int col, int direction)
  {
    if (LayoutControls.shared(key, direction)) return getString(R.string.page_shared_status);
    String value = draft.action(page, row, col, direction, shifted);
    if (value != null) return getString(value.isEmpty() ? R.string.page_empty_status : R.string.page_edited_status);
    return getString(shifted && draft.action(page, row, col, direction, false) != null ? R.string.page_follow_normal_status : R.string.page_inherited_status);
  }
  private void editKey(int row, int col)
  {
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    ScrollView positions = new ScrollView(this); positions.setId(R.id.miancak_editor_positions); positions.addView(content);
    KeyboardData.Key key = draft.apply(page, base).rows.get(row).keys.get(col);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.page_choose_position).setView(positions)
      .setNegativeButton(android.R.string.cancel, null).create();
    for (int[] line : DIRECTIONS)
    {
      LinearLayout cells = new LinearLayout(this);
      for (int direction : line)
      {
        String output = label(key.keys[direction]), source = status(key, row, col, direction);
        Button slot = button(directionNames[direction] + "\n" + output + "\n" + source,
            getString(R.string.page_position_accessibility, directionNames[direction], output, source), () -> {
          dialog.dismiss(); chooseAction(row, col, direction);
        });
        slot.setId(POSITION_IDS[direction]); slot.setTextSize(12); slot.setEnabled(!LayoutControls.shared(key, direction));
        cells.addView(slot, new LinearLayout.LayoutParams(0, -2, 1));
      }
      content.addView(cells);
    }
    dialog.show();
  }
  private void chooseAction(int row, int col, int direction)
  {
    String[] choices = {getString(R.string.page_literal), getString(R.string.page_engine),
      getString(R.string.page_action), getString(R.string.page_empty), getString(R.string.page_inherit), getString(R.string.page_reset_position)};
    new AlertDialog.Builder(this).setTitle(R.string.page_key_action).setItems(choices, (dialog, which) -> {
      if (which < 2) editText(row, col, direction, which == 1);
      else if (which == 2) chooseSpecial(row, col, direction);
      else if (which == 5)
      {
        try { draft.set(page, row, col, direction, false, null); draft.set(page, row, col, direction, true, null); render(); }
        catch (Exception error) { error(error.getMessage()); }
      }
      else change(row, col, direction, which == 3 ? "" : null);
    }).setNegativeButton(android.R.string.cancel, null).show();
  }
  private void editText(int row, int col, int direction, boolean engine)
  {
    EditText input = new EditText(this); input.setContentDescription("Key output text");
    input.setSingleLine(false);
    String current = draft.action(page, row, col, direction, shifted);
    if (current != null && !current.isEmpty())
    {
      KeyValue key = KeyValue.getKeyByName(current);
      input.setText(key.getKind() == KeyValue.Kind.Literal || key.getKind() == KeyValue.Kind.Engine ? key.getSemanticText() : key.getString());
    }
    new AlertDialog.Builder(this).setTitle(engine ? R.string.page_engine : R.string.page_literal).setView(input)
      .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
          (dialog, which) -> change(row, col, direction, (engine ? ":engine:" : ":literal:") + "'"
            + input.getText().toString().replace("\\", "\\\\").replace("'", "\\'") + "'")).show();
  }
  private void chooseSpecial(int row, int col, int direction)
  {
    List<PageActions.Action> choices = PageActions.list(getResources(), SymbolCatalog.validScope(page));
    String[] labels = new String[choices.size()]; for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label;
    new AlertDialog.Builder(this).setTitle(R.string.page_action).setItems(labels,
        (dialog, which) -> change(row, col, direction, choices.get(which).id)).setNegativeButton(android.R.string.cancel, null).show();
  }
  private void chooseSymbolContent()
  {
    List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
    for (String category : SymbolCatalog.categories()) for (int number = 0; number < SymbolCatalog.pageCount(category); number++)
    {
      ids.add(SymbolCatalog.scope(category, number)); labels.add(getString(PagePreferences.symbolLabel(category)) + " · "
        + getString(R.string.symbol_page, number + 1, SymbolCatalog.pageCount(category)));
    }
    new AlertDialog.Builder(this).setTitle(R.string.page_symbol_content).setItems(labels.toArray(new String[0]), (dialog, which) -> {
      page = ids.get(which); loadBase(); render();
    }).setNegativeButton(android.R.string.cancel, null).show();
  }
  private void change(int row, int col, int direction, String value)
  {
    try { draft.set(page, row, col, direction, shifted, value); render(); }
    catch (Exception error) { error(error.getMessage()); }
  }
  private void error(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show(); }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
