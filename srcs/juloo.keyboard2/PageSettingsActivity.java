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
    scroll.setFitsSystemWindows(true);
    content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(12 * getResources().getDisplayMetrics().density);
    content.setPadding(padding, padding, padding, padding);
    scroll.addView(content); setContentView(scroll);
    for (String id : config.order)
    {
      LinearLayout row = new LinearLayout(this);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      CheckBox enabled = new CheckBox(this);
      enabled.setText(PagePreferences.label(id));
      enabled.setContentDescription("page-enabled-" + id);
      enabled.setChecked(config.enabled.contains(id));
      enabled.setEnabled(!PageConfig.isText(id));
      enabled.setOnCheckedChangeListener((button, checked) -> save(config.enable(id, checked)));
      row.addView(enabled, new LinearLayout.LayoutParams(0, -2, 1));
      Button up = new Button(this); up.setText("↑");
      up.setContentDescription(getString(R.string.page_move_up, getString(PagePreferences.label(id))));
      up.setEnabled(config.order.indexOf(id) > 0);
      up.setOnClickListener(view -> save(config.move(id, -1)));
      row.addView(up, new LinearLayout.LayoutParams(padding * 5, -2));
      content.addView(row);
    }
    fnButton(InputRouter.Mode.CHINESE); fnButton(InputRouter.Mode.ENGLISH);
    Button reset = new Button(this); reset.setText(R.string.pages_reset);
    reset.setOnClickListener(view -> new AlertDialog.Builder(this).setMessage(R.string.pages_reset_confirm)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, (dialog, which) -> save(new PageConfig(null, null, null, null))).show());
    content.addView(reset);
    android.widget.TextView note = new android.widget.TextView(this);
    note.setText(R.string.pages_explanation); content.addView(note);
  }
  private void fnButton(InputRouter.Mode mode)
  {
    String target = config.fn(mode);
    Button button = new Button(this);
    button.setContentDescription(mode == InputRouter.Mode.CHINESE ? "fn-target-zh" : "fn-target-en");
    button.setText(getString(mode == InputRouter.Mode.CHINESE ? R.string.page_chinese_fn : R.string.page_english_fn,
          target.isEmpty() ? getString(R.string.page_none) : getString(PagePreferences.label(target))));
    button.setOnClickListener(view -> {
      ArrayList<String> ids = new ArrayList<>(); ids.add("");
      for (String id : config.visible()) if (!PageConfig.isText(id)) ids.add(id);
      String[] labels = new String[ids.size()];
      for (int i = 0; i < labels.length; i++) labels[i] = ids.get(i).isEmpty() ? getString(R.string.page_none) : getString(PagePreferences.label(ids.get(i)));
      new AlertDialog.Builder(this).setTitle(R.string.page_fn_target)
        .setSingleChoiceItems(labels, ids.indexOf(target), (dialog, which) -> {
          save(config.withFn(mode, ids.get(which))); dialog.dismiss();
        }).setNegativeButton(android.R.string.cancel, null).show();
    });
    content.addView(button);
  }
  protected void save(PageConfig next) { PagePreferences.save(preferences, next); render(); }
}
