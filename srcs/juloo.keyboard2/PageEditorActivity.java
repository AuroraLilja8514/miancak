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
  private int scrollY;
  private Config previewConfig;

  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved);
    page = saved == null ? getIntent().getStringExtra("page") : saved.getString("page");
    if (!PageLayouts.editable(page)) { finish(); return; }
    preferences = DirectBootAwarePreferences.get_shared_preferences(this);
    MiancakPreferences.initialize(preferences);
    previewConfig = Config.forPreview(this, preferences);
    original = PageKeyOverrides.raw(preferences);
    draft = PageKeyOverrides.load(preferences).copy();
    directionNames = getResources().getStringArray(R.array.page_direction_names);
    actions = PageActions.list(getResources(), true);
    if (saved != null)
      try
      {
        draft = PageKeyOverrides.restore(saved.getString("draft"));
        original = saved.getString("original"); shifted = saved.getBoolean("shifted");
        scrollY = saved.getInt("scrollY");
      }
      catch (Exception ignored) {}
    loadBase();
    render();
  }
  @Override protected void onSaveInstanceState(Bundle state)
  {
    super.onSaveInstanceState(state);
    rememberScroll(); state.putString("page", page); state.putInt("scrollY", scrollY);
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
    if (vertical != null) scrollY = vertical.getScrollY();
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
    Keyboard2View preview = new Keyboard2View(this, null, previewConfig);
    preview.setId(R.id.miancak_editor_keyboard); preview.setContentDescription("Layout preview: " + page);
    preview.setKeyboard(layout);
    preview.setEditorSelection((row,col,slot,gesture) -> {
      if(slot<9 && LayoutControls.shared(layout.rows.get(row).keys.get(col),slot))
      {error(getString(R.string.page_shared_status));return;}
      if(gesture==Gesture.Name.Circle || gesture==Gesture.Name.Roundtrip)
      {
        shifted=true;render();
        new AlertDialog.Builder(this).setMessage(R.string.page_gesture_shift_link).setNegativeButton(android.R.string.cancel,null)
          .setPositiveButton(android.R.string.ok,(dialog,which)->chooseAction(row,col,slot)).show();
      }
      else chooseAction(row,col,slot);
    },shifted);
    content.addView(preview,new LinearLayout.LayoutParams(-1,-2));
    content.addView(button(getString(R.string.page_choose_key_list),"Choose key and position",this::chooseKey));
    TextView description = new TextView(this);
    description.setText(page.equals("emoji") || page.equals("clipboard") ? R.string.page_dynamic_controls : R.string.page_editor_help);
    content.addView(description);
    content.addView(button(getString(R.string.page_reset_layout), "Reset this layout", () ->
      new AlertDialog.Builder(this).setMessage(R.string.page_reset_layout_confirm)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
            (dialog, which) -> { draft.reset(page); render(); }).show()));
    scroll.post(() -> scroll.scrollTo(0, scrollY));
  }
  /** Accessible alternative to small gestures. */
  private void chooseKey()
  {
    List<String> labels=new ArrayList<>();List<int[]> positions=new ArrayList<>();
    KeyboardData data=draft.apply(page,base);
    for(int r=0;r<data.rows.size();r++)for(int c=0;c<data.rows.get(r).keys.size();c++)
    {labels.add((r+1)+":"+(c+1)+" · "+label(data.rows.get(r).keys.get(c).keys[0]));positions.add(new int[]{r,c});}
    new AlertDialog.Builder(this).setTitle(R.string.page_choose_key_list).setItems(labels.toArray(new String[0]),(dialog,which)->{
      int[] pos=positions.get(which);editKey(pos[0],pos[1]);
    }).setNegativeButton(android.R.string.cancel,null).show();
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
    Button anticircle=button(getString(R.string.page_anticircle)+"\n"+label(key.anticircle),"Edit anticlockwise output",
        ()->{dialog.dismiss();chooseAction(row,col,9);});
    anticircle.setId(R.id.miancak_position_anticircle);content.addView(anticircle);
    dialog.show();
  }
  private void chooseAction(int row, int col, int direction)
  {
    String[] choices = {getString(R.string.page_literal), getString(R.string.page_engine),
      getString(R.string.page_action), getString(R.string.page_empty), getString(R.string.page_inherit), getString(R.string.page_reset_position)};
    KeyboardData.Key selected=draft.apply(page,base).rows.get(row).keys.get(col);
    String directionName=direction==9?getString(R.string.page_anticircle):directionNames[direction];
    String current=label(direction==9?selected.anticircle:selected.keys[direction]);
    String title=label(selected.keys[0])+" · "+getString(R.string.page_position_accessibility,directionName,current,status(selected,row,col,direction))
      +(shifted?" · Shift":"");
    new AlertDialog.Builder(this).setTitle(title).setItems(choices, (dialog, which) -> {
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
