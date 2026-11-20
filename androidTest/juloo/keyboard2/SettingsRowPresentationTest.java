package juloo.keyboard2;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.DialogInterface;
import android.content.Intent;
import android.preference.DialogPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Compare the framework rows used by the real SettingsActivity at two widths. */
@RunWith(AndroidJUnit4.class)
public class SettingsRowPresentationTest
{
  @Test public void sharedTitlesAndSummariesUseTheSameLeadingInset()
  {
    Instrumentation test = InstrumentationRegistry.getInstrumentation();
    SettingsActivity settings = openSettings(test);
    try
    {
      test.runOnMainSync(() -> {
        PreferenceScreen root = settings.getPreferenceScreen();
        assertNotNull(root);
        PreferenceScreen extras = (PreferenceScreen)settings.findPreference("extra_keys_screen");
        assertNotNull(extras);
        for (int width : new int[]{300, 640})
        {
          checkRows(root, settings.getListView(), width, "pages", "word_tables", "wanxiang_setup", "number_row",
              "split_layout", "longpress_timeout", "keyrepeat_enabled", "character_size");
          checkRows(extras, settings.getListView(), width, "extra_key_superscript", "extra_key_subscript", "add_custom_extra_key");
          Preference addKey = extras.findPreference("add_custom_extra_key");
          assertEquals(settings.getText(R.string.pref_extra_keys_add_custom_key), addKey.getTitle());
          assertTrue(addKey.isSelectable());
          assertFalse(addKey.isPersistent());
        }
      });
    }
    finally { test.runOnMainSync(settings::finish); }
  }

  @Test public void sliderPreviewAndCancelKeepTheSavedSettingAndItsSummary()
  {
    Instrumentation test = InstrumentationRegistry.getInstrumentation();
    SettingsActivity settings = openSettings(test);
    try
    {
      checkSliderCancel(test, settings, "longpress_timeout");
      checkSliderCancel(test, settings, "character_size");
    }
    finally { test.runOnMainSync(settings::finish); }
  }

  private SettingsActivity openSettings(Instrumentation test)
  {
    Intent intent = new Intent(test.getTargetContext(), SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return (SettingsActivity)test.startActivitySync(intent);
  }

  private void checkSliderCancel(Instrumentation test, SettingsActivity settings, String key)
  {
    String[] summary = new String[1];
    Object[] saved = new Object[1];
    test.runOnMainSync(() -> {
      PreferenceScreen screen = settings.getPreferenceScreen();
      ListAdapter adapter = screen.getRootAdapter();
      ListView list = settings.getListView();
      int index = rowIndex(adapter, key);
      DialogPreference preference = (DialogPreference)adapter.getItem(index);
      summary[0] = preference.getSummary().toString();
      saved[0] = preference.getSharedPreferences().getAll().get(key);
      assertNotNull("Expected a persisted setting " + key, saved[0]);
      screen.onItemClick(list, adapter.getView(index, null, list), index + list.getHeaderViewsCount(), adapter.getItemId(index));
    });
    test.waitForIdleSync();
    test.runOnMainSync(() -> {
      DialogPreference preference = (DialogPreference)settings.findPreference(key);
      AlertDialog dialog = (AlertDialog)preference.getDialog();
      assertNotNull(dialog);
      assertTrue("Expected the actual slider dialog", dialog.isShowing());
      TextView value = dialog.findViewById(R.id.pref_slider_value);
      SeekBar slider = dialog.findViewById(R.id.pref_slider_control);
      assertNotNull(value);
      assertNotNull(slider);
      assertSame(value.getParent(), slider.getParent());
      assertEquals(value.getLeft(), slider.getLeft());
      assertEquals(value.getRight(), slider.getRight());
      assertTrue("Slider should have navigable width", slider.getWidth() > 0);
      assertEquals(slider.getId(), value.getLabelFor());
      assertEquals(summary[0], value.getText().toString());
      slider.setProgress(slider.getProgress() == slider.getMin() ? slider.getMax() : slider.getMin());
      assertNotEquals("Changing the slider should update the preview", summary[0], value.getText().toString());
      assertEquals("Preview should leave the Settings row summary alone", summary[0], preference.getSummary().toString());
      assertEquals(saved[0], preference.getSharedPreferences().getAll().get(key));
      dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
    });
    test.waitForIdleSync();
    test.runOnMainSync(() -> {
      DialogPreference preference = (DialogPreference)settings.findPreference(key);
      assertFalse("Cancel should close the slider dialog", preference.getDialog() != null && preference.getDialog().isShowing());
      assertEquals(summary[0], preference.getSummary().toString());
      assertEquals(saved[0], preference.getSharedPreferences().getAll().get(key));
    });
  }

  private void checkRows(PreferenceScreen screen, ViewGroup parent, int widthDp, String... keys)
  {
    ListAdapter adapter = screen.getRootAdapter();
    int width = Math.round(widthDp * parent.getResources().getDisplayMetrics().density);
    Integer expected = null;
    for (String key : keys)
    {
      int index = rowIndex(adapter, key);
      View row = adapter.getView(index, null, parent);
      row.setLayoutDirection(parent.getLayoutDirection());
      row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
      TextView title = row.findViewById(android.R.id.title);
      assertNotNull("Visible setting must have a framework title " + key, title);
      assertTrue("Missing title text for " + key, title.getVisibility() == View.VISIBLE && title.length() > 0);
      assertNotEquals("Settings title should allow wrapping for " + key, 1, title.getMaxLines());
      int start = leadingInset(row, title);
      if (expected == null) expected = start;
      assertEquals("Uneven leading title inset for " + key, expected.intValue(), start);
      TextView summary = row.findViewById(android.R.id.summary);
      if (summary != null && summary.getVisibility() == View.VISIBLE)
        assertEquals("Uneven leading summary inset for " + key, start, leadingInset(row, summary));
    }
  }

  private int rowIndex(ListAdapter adapter, String key)
  {
    for (int index = 0; index < adapter.getCount(); index++)
      if (key.equals(((Preference)adapter.getItem(index)).getKey())) return index;
    throw new AssertionError("Missing setting " + key);
  }

  private int leadingInset(View row, TextView text)
  {
    int left = 0;
    for (View node = text; node != row; node = (View)node.getParent()) left += node.getLeft();
    if (row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)
      return row.getWidth() - left - text.getWidth() + text.getCompoundPaddingRight();
    return left + text.getCompoundPaddingLeft();
  }
}
