package juloo.keyboard2;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import juloo.keyboard2.input.PageConfig;
import juloo.keyboard2.input.InputRouter;

/** One ordered page registry, shared by direct keys, Fn and the chooser. */
public class PageSettingsActivity extends Activity
{
  protected SharedPreferences preferences;
  protected PageConfig config;
  protected LinearLayout content;
  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved);
    preferences = DirectBootAwarePreferences.get_shared_preferences(this);
    MiancakPreferences.initialize(preferences);
    setTitle(R.string.pages_title);
    render();
  }
  protected void render()
  {
    config = PagePreferences.load(preferences);
    ScrollView scroll = new ScrollView(this);
    scroll.setId(R.id.miancak_page_settings);
    scroll.setFitsSystemWindows(true);
    content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(12 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    scroll.addView(content); setContentView(scroll);
    ActivityInsets.install(this, scroll);
    for (String id : config.order)
    {
      LinearLayout row = new LinearLayout(this);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      CheckBox enabled = new CheckBox(this);
      enabled.setText(PagePreferences.label(id));
      enabled.setContentDescription("page-enabled-" + id);
      enabled.setChecked(config.enabled.contains(id));
      enabled.setEnabled(!PageConfig.isText(id) && !PageConfig.isVariant(id));
      enabled.setOnCheckedChangeListener((button, checked) -> save(config.enable(id, checked)));
      row.addView(enabled, new LinearLayout.LayoutParams(0, -2, 1));
      Button up = new Button(this); up.setText("↑");
      up.setContentDescription(getString(R.string.page_move_up, getString(PagePreferences.label(id))));
      up.setEnabled(config.order.indexOf(id) > 0);
      up.setOnClickListener(view -> save(config.move(id, -1)));
      row.addView(up, new LinearLayout.LayoutParams(padding * 5, -2));
      content.addView(row);
      Button edit = new Button(this); edit.setText(R.string.page_edit_layout);
      edit.setContentDescription("Edit layout: " + id);
      edit.setOnClickListener(view -> startActivity(new android.content.Intent(this, PageEditorActivity.class).putExtra("page", id)));
      content.addView(edit);
    }
    fnButton("zh"); fnButton("en");
    Button reset = new Button(this); reset.setText(R.string.pages_reset);
    reset.setOnClickListener(view -> new AlertDialog.Builder(this).setMessage(R.string.pages_reset_confirm)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> save(new PageConfig(null, null))).show());
    content.addView(reset);
    Button export = new Button(this); export.setText(R.string.page_export);
    export.setOnClickListener(view -> startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/json")
        .putExtra(android.content.Intent.EXTRA_TITLE, "miancak-layouts.json"), 2)); content.addView(export);
    Button imports = new Button(this); imports.setText(R.string.page_import);
    imports.setOnClickListener(view -> startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"), 1)); content.addView(imports);
    Button restore = new Button(this); restore.setText(R.string.page_restore_previous);
    restore.setEnabled(preferences.contains(PageKeyOverrides.KEY + "_previous_bundle"));
    restore.setOnClickListener(view -> new AlertDialog.Builder(this).setMessage(R.string.page_restore_confirm)
        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, (dialog, which) -> {
          try { PageKeyOverrides.importJson(preferences, getResources(), preferences.getString(PageKeyOverrides.KEY + "_previous_bundle", "")); render(); }
          catch (Exception error) { error(error.getMessage()); }
        }).show()); content.addView(restore);
    android.widget.TextView note = new android.widget.TextView(this);
    note.setText(getString(R.string.pages_explanation) + "\n\n" + getString(R.string.page_phonetic_help)); content.addView(note);
  }
  private void fnButton(String base)
  {
    String target = config.fn(base);
    Button button = new Button(this);
    button.setContentDescription("Edit Fn variant: " + base);
    button.setText(getString(base.equals("zh") ? R.string.page_chinese_fn : R.string.page_english_fn,
          getString(PagePreferences.label(target))));
    button.setOnClickListener(view -> startActivity(new android.content.Intent(this, PageEditorActivity.class).putExtra("page", target)));
    content.addView(button);
  }
  protected void save(PageConfig next) { PagePreferences.save(preferences, next); render(); }
  @Override protected void onActivityResult(int request, int result, android.content.Intent data)
  {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    try
    {
      if (request == 2)
      {
        try (java.io.OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt"))
        { stream.write(PageKeyOverrides.export(preferences).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        android.widget.Toast.makeText(this, R.string.page_exported, android.widget.Toast.LENGTH_SHORT).show();
      }
      else if (request == 1)
      {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream stream = getContentResolver().openInputStream(data.getData()))
        {
          byte[] buffer = new byte[4096]; int count;
          while ((count = stream.read(buffer)) != -1)
          {
            if (bytes.size() + count > PageKeyOverrides.MAX_BYTES) throw new IllegalArgumentException(getString(R.string.page_import_too_large));
            bytes.write(buffer, 0, count);
          }
        }
        String json = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        new AlertDialog.Builder(this).setMessage(R.string.page_import_confirm).setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok, (dialog, which) -> {
            try { PageKeyOverrides.importJson(preferences, getResources(), json); render(); }
            catch (Exception error) { error(error.getMessage()); }
          }).show();
      }
    }
    catch (Exception error) { error(error.getMessage()); }
  }
  private void error(String message)
  { new AlertDialog.Builder(this).setTitle(R.string.page_import_error).setMessage(message).setPositiveButton(android.R.string.ok, null).show(); }
}
