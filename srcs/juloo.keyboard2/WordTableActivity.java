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
import juloo.keyboard2.input.RimeConfigStore;
import juloo.keyboard2.input.LearningRecovery;

/** Vocabulary data, intentionally separate from page/layout configuration. */
public final class WordTableActivity extends Activity
{
  private String base = WordTableStore.BUNDLED, lastSlot = "";
  private final Set<String> extras = new LinkedHashSet<>();
  private final Set<String> patches = new LinkedHashSet<>();
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
      values = saved.getStringArrayList("patches"); if (values != null) patches.addAll(values);
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
    out.putStringArrayList("patches", new ArrayList<>(patches));
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
    LinearLayout configActions = row();
    configActions.addView(button(R.string.words_configs_import, () -> {
      Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
          .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      startActivityForResult(picker, 71);
    }), new LinearLayout.LayoutParams(0, -2, 1));
    Button configHelp = button(R.string.words_configs_help, () -> new AlertDialog.Builder(this)
        .setTitle(R.string.words_configs_title).setMessage(R.string.words_configs_profile).setPositiveButton(android.R.string.ok, null).show());
    configHelp.setEnabled(true); configActions.addView(configHelp);
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
    content.addView(configActions);
    if (state != null)
    {
      if (!initialized || !lastSlot.equals(state.selection.slot))
      { base = state.selection.base; extras.clear(); extras.addAll(state.selection.extras); patches.clear(); patches.addAll(state.selection.patches); initialized = true; lastSlot = state.selection.slot; }
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
      TextView configLabel = new TextView(this); configLabel.setText(R.string.words_configs_title); content.addView(configLabel);
      LinearLayout configList = new LinearLayout(this); configList.setOrientation(LinearLayout.VERTICAL); configList.setId(R.id.rime_config_list); content.addView(configList);
      Set<String> configIds = new HashSet<>(); for (RimeConfigStore.Entry entry : state.patches) configIds.add(entry.id);
      patches.retainAll(configIds);
      for (RimeConfigStore.Entry entry : state.patches)
      {
        LinearLayout options = row(); CheckBox enabled = new CheckBox(this);
        enabled.setText(entry.filename + " · " + entry.sha256.substring(0, 8)); enabled.setChecked(patches.contains(entry.id)); enabled.setEnabled(!WordTableRuntime.busy);
        enabled.setOnCheckedChangeListener((view, checked) -> { if (checked) patches.add(entry.id); else patches.remove(entry.id); });
        options.addView(enabled, new LinearLayout.LayoutParams(0, -2, 1));
        Button info = button(R.string.words_details, () -> new AlertDialog.Builder(this).setTitle(entry.filename)
            .setMessage(getString(R.string.words_configs_metadata, entry.bytes, entry.sha256))
            .setPositiveButton(android.R.string.ok, null).show());
        info.setContentDescription(getString(R.string.words_details_choice, entry.filename)); options.addView(info);
        Button remove = button(R.string.words_remove, () -> new AlertDialog.Builder(this).setMessage(R.string.words_configs_remove_confirm)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
                (dialog, which) -> WordTableRuntime.run(this, false, store -> store.removeConfig(entry.id))).show());
        remove.setContentDescription(getString(R.string.words_remove_choice, entry.filename));
        if (state.retainedPatches.contains(entry.id)) remove.setEnabled(false);
        options.addView(remove); configList.addView(options);
      }
    }
    Button apply = button(R.string.words_apply, () -> {
      String selected = base; List<String> additions = new ArrayList<>(extras);
      List<String> configSelection = new ArrayList<>(patches);
      WordTableRuntime.run(this, true, store -> store.apply(selected, additions, configSelection));
    });
    apply.setId(R.id.word_tables_apply); if (state == null) apply.setEnabled(false); root.addView(apply, new LinearLayout.LayoutParams(-1, -2));
    root.addView(button(R.string.words_restore, () ->
        WordTableRuntime.run(this, true, store -> store.apply(WordTableStore.BUNDLED, Collections.emptyList()))), new LinearLayout.LayoutParams(-1, -2));
    if (WordTableStore.hasFormalBundledPack(this))
      root.addView(button(R.string.words_legacy, () -> WordTableRuntime.run(this, true, WordTableStore::useLegacyBase)),
          new LinearLayout.LayoutParams(-1, -2));
    content.addView(button(R.string.words_configs_reset, () -> WordTableRuntime.run(this, true, WordTableStore::disableConfigs)), new LinearLayout.LayoutParams(-1, -2));
    if (state != null)
    {
      TextView heading = new TextView(this); heading.setText(R.string.words_learning_title); content.addView(heading);
      content.addView(button(R.string.words_learning_preview, () -> new AlertDialog.Builder(this)
          .setTitle(R.string.words_learning_title).setMessage(R.string.words_learning_explanation)
          .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
              (dialog, which) -> WordTableRuntime.run(this, true, WordTableStore::previewLearningRecovery)).show()));
      if (state.retainedLearningSnapshot)
      {
        LearningRecovery.Preview preview = state.learning;
        String detail = state.learningIssue;
        if (preview != null) detail = getString(R.string.words_learning_counts, preview.sourceHash, preview.active, preview.deleted,
            preview.unsupported, preview.records, preview.status) + "\n\n" + getString(R.string.words_learning_explanation);
        final String messageText = detail;
        content.addView(button(R.string.words_learning_details, () -> new AlertDialog.Builder(this).setTitle(R.string.words_learning_title)
            .setMessage(messageText).setPositiveButton(android.R.string.ok, null).show()));
        Button confirm = button(R.string.words_learning_apply, () -> new AlertDialog.Builder(this).setTitle(R.string.words_learning_title)
            .setMessage(messageText + "\n\n" + getString(R.string.words_learning_confirm))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
                (dialog, which) -> WordTableRuntime.run(this, true, WordTableStore::applyLearningRecovery)).show());
        if (preview == null || !preview.canApply) confirm.setEnabled(false); content.addView(confirm);
        content.addView(button(R.string.words_learning_discard, () -> new AlertDialog.Builder(this).setMessage(R.string.words_learning_discard_confirm)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok,
                (dialog, which) -> WordTableRuntime.run(this, false, WordTableStore::discardLearningRecovery)).show()));
      }
    }
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
    if (label == R.string.words_legacy) button.setId(R.id.word_tables_legacy);
    if (label == R.string.words_configs_import) button.setId(R.id.rime_config_import);
    if (label == R.string.words_configs_help) button.setId(R.id.rime_config_help);
    if (label == R.string.words_configs_reset) button.setId(R.id.rime_config_reset);
    if (label == R.string.words_learning_preview) button.setId(R.id.learning_recovery_preview);
    if (label == R.string.words_learning_details) button.setId(R.id.learning_recovery_details);
    if (label == R.string.words_learning_apply) button.setId(R.id.learning_recovery_apply);
    if (label == R.string.words_learning_discard) button.setId(R.id.learning_recovery_discard);
    button.setEnabled(!WordTableRuntime.busy); button.setOnClickListener(view -> action.run()); return button;
  }
  @Override protected void onActivityResult(int request, int result, Intent data)
  {
    super.onActivityResult(request, result, data);
    if ((request != 70 && request != 71) || result != RESULT_OK || data == null) return;
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
      if (request == 71) store.importConfigs(sources); else store.importTables(sources);
    });
  }
}
