package juloo.keyboard2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.util.*;
import juloo.keyboard2.input.WordTableRuntime;
import juloo.keyboard2.input.WordTableStore;

/** Vocabulary data, intentionally separate from page/layout configuration. */
public final class WordTableActivity extends Activity
{
  private String base = WordTableStore.BUNDLED, lastSlot = "";
  private final Set<String> extras = new LinkedHashSet<>();
  private boolean initialized;
  private LinearLayout root;
  private final Runnable observer = this::render;
  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved); setTitle(R.string.words_title);
    if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
    if (saved != null)
    {
      base = saved.getString("base", WordTableStore.BUNDLED); lastSlot = saved.getString("slot", "");
      ArrayList<String> values = saved.getStringArrayList("extras"); if (values != null) extras.addAll(values);
      initialized = saved.getBoolean("initialized");
    }
    root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
    int padding = (int)(12 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, padding, padding, padding);
    if (android.os.Build.VERSION.SDK_INT >= 35)
      root.setOnApplyWindowInsetsListener((view, insets) -> {
        android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom); return insets;
      });
    else root.setFitsSystemWindows(true);
    setContentView(root);
  }
  @Override protected void onStart()
  { super.onStart(); WordTableRuntime.observe(observer); WordTableRuntime.refresh(this); }
  @Override protected void onStop() { WordTableRuntime.forget(observer); super.onStop(); }
  @Override public void onSaveInstanceState(Bundle out)
  {
    out.putString("base", base); out.putString("slot", lastSlot); out.putStringArrayList("extras", new ArrayList<>(extras));
    out.putBoolean("initialized", initialized); super.onSaveInstanceState(out);
  }
  @Override public boolean onOptionsItemSelected(android.view.MenuItem item)
  { if (item.getItemId() == android.R.id.home) { finish(); return true; } return super.onOptionsItemSelected(item); }
  private void render()
  {
    if (root == null) return;
    root.removeAllViews();
    LinearLayout actions = row();
    actions.addView(button(R.string.words_import, () -> {
      Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      startActivityForResult(picker, 70);
    }), new LinearLayout.LayoutParams(0, -2, 1));
    Button help = button(R.string.words_help, () -> new AlertDialog.Builder(this)
      .setTitle(R.string.words_title).setMessage(R.string.words_profile).setPositiveButton(android.R.string.ok, null).show());
    help.setEnabled(true); actions.addView(help); root.addView(actions);
    TextView status = new TextView(this); status.setId(R.id.word_tables_status);
    status.setMaxLines(4); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    String message = WordTableRuntime.busy ? getString(R.string.words_busy) : getString(R.string.words_ready);
    if (!WordTableRuntime.error.isEmpty()) message = getString(R.string.words_error, WordTableRuntime.error);
    WordTableStore.View state = WordTableRuntime.view;
    if (state != null && !state.recovery.isEmpty()) message += "\n" + state.recovery;
    status.setText(message);
    final String details = message;
    status.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle(R.string.words_title).setMessage(details).setPositiveButton(android.R.string.ok, null).show());
    root.addView(status);
    ScrollView scroll = new ScrollView(this); scroll.setId(R.id.word_tables_list);
    LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (state != null)
    {
      if (!initialized || !lastSlot.equals(state.selection.slot))
      { base = state.selection.base; extras.clear(); extras.addAll(state.selection.extras); initialized = true; lastSlot = state.selection.slot; }
      Set<String> ids = new HashSet<>(); for (WordTableStore.Entry entry : state.entries) ids.add(entry.id);
      if (!base.equals(WordTableStore.BUNDLED) && !ids.contains(base)) base = state.selection.base;
      extras.retainAll(ids);
      TextView label = new TextView(this); label.setText(R.string.words_base); content.addView(label);
      RadioGroup choices = new RadioGroup(this); content.addView(choices);
      radio(choices, getString(R.string.words_bundled), WordTableStore.BUNDLED);
      for (WordTableStore.Entry entry : state.entries)
      {
        radio(choices, entry.name + (entry.version.isEmpty() ? "" : " · " + entry.version), entry.id);
        LinearLayout options = row();
        CheckBox extra = new CheckBox(this); extra.setText(R.string.words_extra); extra.setContentDescription(getString(R.string.words_extra_choice, entry.name));
        extra.setChecked(extras.contains(entry.id)); extra.setEnabled(!WordTableRuntime.busy && !base.equals(entry.id));
        extra.setOnCheckedChangeListener((button, enabled) -> { if (enabled) extras.add(entry.id); else extras.remove(entry.id); });
        options.addView(extra, new LinearLayout.LayoutParams(0, -2, 1));
        Button info = button(R.string.words_details, () -> new AlertDialog.Builder(this).setTitle(entry.name)
          .setMessage(getString(R.string.words_metadata, entry.filename, entry.version, entry.rows, entry.bytes,
              entry.id, android.text.TextUtils.join(", ", entry.imports)))
          .setPositiveButton(android.R.string.ok, null).show());
        info.setContentDescription(getString(R.string.words_details_choice, entry.name)); options.addView(info);
        Button remove = button(R.string.words_remove, () -> new AlertDialog.Builder(this)
          .setMessage(getString(R.string.words_remove_confirm, entry.name)).setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok, (dialog, which) -> WordTableRuntime.run(this, false, store -> store.remove(entry.id))).show());
        remove.setContentDescription(getString(R.string.words_remove_choice, entry.name));
        if (entry.id.equals(state.selection.base) || state.selection.extras.contains(entry.id)) remove.setEnabled(false);
        options.addView(remove); choices.addView(options);
      }
    }
    Button apply = button(R.string.words_apply, () -> {
      String selected = base; List<String> additions = new ArrayList<>(extras);
      WordTableRuntime.run(this, true, store -> store.apply(selected, additions));
    });
    apply.setId(R.id.word_tables_apply); if (state == null) apply.setEnabled(false); root.addView(apply, new LinearLayout.LayoutParams(-1, -2));
    root.addView(button(R.string.words_restore, () ->
        WordTableRuntime.run(this, true, store -> store.apply(WordTableStore.BUNDLED, Collections.emptyList()))), new LinearLayout.LayoutParams(-1, -2));
  }
  private void radio(RadioGroup parent, String label, String id)
  {
    RadioButton radio = new RadioButton(this); radio.setId(View.generateViewId()); radio.setText(label);
    radio.setContentDescription(getString(R.string.words_base_choice, label));
    parent.addView(radio); radio.setChecked(base.equals(id)); radio.setEnabled(!WordTableRuntime.busy);
    radio.setOnClickListener(view -> { base = id; extras.remove(id); render(); });
  }
  private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row; }
  private Button button(int label, Runnable action)
  {
    Button button = new Button(this); button.setText(label); button.setContentDescription(getString(label));
    if (label == R.string.words_import) button.setId(R.id.word_tables_import);
    if (label == R.string.words_apply) button.setId(R.id.word_tables_apply);
    if (label == R.string.words_restore) button.setId(R.id.word_tables_restore);
    if (label == R.string.words_help) button.setId(R.id.word_tables_help);
    button.setEnabled(!WordTableRuntime.busy); button.setOnClickListener(view -> action.run()); return button;
  }
  @Override protected void onActivityResult(int request, int result, Intent data)
  {
    super.onActivityResult(request, result, data);
    if (request != 70 || result != RESULT_OK || data == null) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null)
      for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    android.content.Context app = getApplicationContext();
    WordTableRuntime.run(this, false, store -> {
      List<WordTableStore.Source> sources = new ArrayList<>();
      for (Uri uri : uris)
      {
        String value = null;
        try (Cursor cursor = app.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null))
        { if (cursor != null && cursor.moveToFirst()) value = cursor.getString(0); }
        final String name = value == null ? String.valueOf(uri.getLastPathSegment()) : value;
        sources.add(new WordTableStore.Source() {
          @Override public String filename() { return name; }
          @Override public InputStream open() throws Exception
          { InputStream input = app.getContentResolver().openInputStream(uri); if (input == null) throw new java.io.IOException("Cannot open word table / 无法打开词表"); return input; }
        });
      }
      store.importTables(sources);
    });
  }
}
