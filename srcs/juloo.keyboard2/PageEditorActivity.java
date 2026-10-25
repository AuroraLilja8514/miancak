package juloo.keyboard2;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Visual editing of logical key positions; changes remain a draft until Save. */
public final class PageEditorActivity extends Activity
{
  private String page, original;
  private PageKeyOverrides draft;
  private KeyboardData base;
  private boolean shifted;
  private android.content.SharedPreferences preferences;

  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved);
    page = getIntent().getStringExtra("page");
    if (!juloo.keyboard2.input.PageConfig.IDS.contains(page)) { finish(); return; }
    preferences = DirectBootAwarePreferences.get_shared_preferences(this);
    original = preferences.getString(PageKeyOverrides.KEY, "{}");
    draft = PageKeyOverrides.load(preferences).copy();
    if (saved != null)
      try
      {
        draft = PageKeyOverrides.restore(saved.getString("draft"));
        original = saved.getString("original"); shifted = saved.getBoolean("shifted");
      }
      catch (Exception ignored) {}
    base = PageLayouts.base(getResources(), page);
    setTitle(getString(PagePreferences.label(page)));
    render();
  }
  @Override protected void onSaveInstanceState(Bundle state)
  {
    super.onSaveInstanceState(state);
    state.putString("draft", draft.snapshot()); state.putString("original", original); state.putBoolean("shifted", shifted);
  }
  private Button button(String label, String description, Runnable action)
  {
    Button button = new Button(this); button.setText(label); button.setAllCaps(false);
    button.setContentDescription(description); button.setOnClickListener(view -> action.run());
    return button;
  }
  private void render()
  {
    ScrollView scroll = new ScrollView(this); scroll.setFitsSystemWindows(true);
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
    setContentView(scroll);
    ActivityInsets.install(this, scroll);
    LinearLayout actions = new LinearLayout(this);
    actions.addView(button(getString(R.string.page_save), "Save layout", () -> {
      if (!original.equals(preferences.getString(PageKeyOverrides.KEY, "{}")))
      { error(getString(R.string.page_changed_elsewhere)); return; }
      try { draft.save(preferences); setResult(RESULT_OK); finish(); }
      catch (Exception invalid) { error(invalid.getMessage()); }
    }), new LinearLayout.LayoutParams(0, -2, 1));
    actions.addView(button(getString(android.R.string.cancel), "Cancel layout edit", this::finish), new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(actions);
    Switch layer = new Switch(this); layer.setText(R.string.page_shift_preview); layer.setChecked(shifted);
    layer.setContentDescription("Shift layer preview");
    layer.setOnCheckedChangeListener((view, checked) -> { shifted = checked; render(); }); content.addView(layer);
    KeyboardData layout = draft.apply(page, base);
    KeyModifier.set_modmap(base.modmap);
    for (int r = 0; r < layout.rows.size(); r++)
    {
      LinearLayout row = new LinearLayout(this);
      for (int c = 0; c < layout.rows.get(r).keys.size(); c++)
      {
        KeyboardData.Key key = layout.rows.get(r).keys.get(c);
        if (key.shift > 0) row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, key.shift));
        final int rowIndex = r, col = c;
        String label = label(key.keys[1]) + "  " + label(key.keys[2]) + "\n" + label(key.keys[0])
          + "\n" + label(key.keys[3]) + "  " + label(key.keys[4]);
        Button cell = button(label, "Edit key: " + page + ":" + r + ":" + c, () -> editKey(rowIndex, col));
        cell.setTextSize(10); cell.setMinWidth(0); cell.setMinimumWidth(0); cell.setPadding(0, 0, 0, 0);
        row.addView(cell, new LinearLayout.LayoutParams(0, dp(84), key.width));
      }
      float gap = layout.keysWidth - layout.rows.get(r).keysWidth;
      if (gap > 0) row.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, 1, gap));
      content.addView(row);
    }
    TextView description = new TextView(this);
    description.setText(page.equals("emoji") || page.equals("clipboard") ? R.string.page_dynamic_controls : R.string.page_editor_help);
    content.addView(description);
    content.addView(button(getString(R.string.page_reset_layout), "Reset this layout", () ->
      new AlertDialog.Builder(this).setMessage(R.string.page_reset_layout_confirm)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
            (dialog, which) -> { draft.reset(page); render(); }).show()));
  }
  private String label(KeyValue value)
  {
    if (value == null) return "·";
    if (value.getKind() == KeyValue.Kind.Layered) value = value.layer(shifted);
    else if (shifted) value = KeyModifier.modify(value, KeyValue.Modifier.SHIFT);
    if (value == null) return "·";
    if (value.equals(KeyValue.SHIFT)) return "⇧";
    if (value.equals(KeyValue.ENTER)) return "↵";
    String text = value.getString();
    return text.codePointCount(0, text.length()) > 5 ? text.substring(0, text.offsetByCodePoints(0, 4)) + "…" : text;
  }
  private void editKey(int row, int col)
  {
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    int[][] directions = {{1, 7, 2}, {5, 0, 6}, {3, 8, 4}};
    KeyboardData.Key key = draft.apply(page, base).rows.get(row).keys.get(col);
    AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.page_choose_position).setView(content)
      .setNegativeButton(android.R.string.cancel, null).create();
    for (int[] line : directions)
    {
      LinearLayout cells = new LinearLayout(this);
      for (int direction : line)
      {
        Button slot = button(label(key.keys[direction]), "Edit position: " + direction, () -> {
          dialog.dismiss(); chooseAction(row, col, direction);
        });
        if (LayoutControls.shared(base.rows.get(row).keys.get(col), direction))
        {
          slot.setEnabled(false);
          slot.setContentDescription("Shared control: " + direction);
        }
        cells.addView(slot, new LinearLayout.LayoutParams(0, dp(64), 1));
      }
      content.addView(cells);
    }
    dialog.show();
  }
  private void chooseAction(int row, int col, int direction)
  {
    String[] choices = {getString(R.string.page_literal), getString(R.string.page_engine),
      getString(R.string.page_action), getString(R.string.page_empty), getString(R.string.page_inherit)};
    new AlertDialog.Builder(this).setTitle(R.string.page_key_action).setItems(choices, (dialog, which) -> {
      if (which < 2) editText(row, col, direction, which == 1);
      else if (which == 2) chooseSpecial(row, col, direction);
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
    String[] ids = {"shift", "backspace", "delete", "enter", "space", "fn_page", "layout_picker", "language_switch",
      "switch_text", "switch_numeric", "switch_greekmath", "switch_emoji", "switch_clipboard", "config",
      "ctrl", "alt", "meta", "left", "right", "up", "down", "pinyin_separator", "switch_pinyin", "switch_pinyin_finals", "switch_zhuyin"};
    String[] labels = getResources().getStringArray(R.array.page_action_labels);
    new AlertDialog.Builder(this).setTitle(R.string.page_action).setItems(labels,
        (dialog, which) -> change(row, col, direction, ids[which])).setNegativeButton(android.R.string.cancel, null).show();
  }
  private void change(int row, int col, int direction, String value)
  {
    try { draft.set(page, row, col, direction, shifted, value); render(); }
    catch (Exception error) { error(error.getMessage()); }
  }
  private void error(String message) { new AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show(); }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
