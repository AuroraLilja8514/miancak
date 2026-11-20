package juloo.keyboard2;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
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
    Intent intent = new Intent(test.getTargetContext(), SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Activity activity = test.startActivitySync(intent);
    try
    {
      test.runOnMainSync(() -> {
        SettingsActivity settings = (SettingsActivity)activity;
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
    finally { test.runOnMainSync(activity::finish); }
  }

  private void checkRows(PreferenceScreen screen, ViewGroup parent, int widthDp, String... keys)
  {
    ListAdapter adapter = screen.getRootAdapter();
    int width = Math.round(widthDp * parent.getResources().getDisplayMetrics().density);
    Integer expected = null;
    for (String key : keys)
    {
      int index = 0;
      while (index < adapter.getCount() && !key.equals(((Preference)adapter.getItem(index)).getKey())) index++;
      assertTrue("Missing setting " + key, index < adapter.getCount());
      View row = adapter.getView(index, null, parent);
      row.setLayoutDirection(parent.getLayoutDirection());
      row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
      TextView title = row.findViewById(android.R.id.title);
      assertNotNull("Visible setting must have a framework title " + key, title);
      assertTrue("Missing title text for " + key, title.getVisibility() == View.VISIBLE && title.length() > 0);
      int start = leadingInset(row, title);
      if (expected == null) expected = start;
      assertEquals("Uneven leading title inset for " + key, expected.intValue(), start);
      TextView summary = row.findViewById(android.R.id.summary);
      if (summary != null && summary.getVisibility() == View.VISIBLE)
        assertEquals("Uneven leading summary inset for " + key, start, leadingInset(row, summary));
    }
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
