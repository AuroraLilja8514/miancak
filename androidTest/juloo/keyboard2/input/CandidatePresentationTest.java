package juloo.keyboard2.input;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import juloo.keyboard2.KeyboardSurface;
import juloo.keyboard2.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android TextView measurement, complementary to native IME touch tests. */
@RunWith(AndroidJUnit4.class)
public class CandidatePresentationTest
{
  private Context themed(float fontScale)
  {
    Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.fontScale = fontScale;
    return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Light);
  }

  private int dp(Context context, int value)
  {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private RimeCandidatesView strip(Context context, String[] values)
  {
    RimeCandidatesView view = new RimeCandidatesView(context, null);
    view.show(new InputSnapshot(1, 1, InputRouter.Mode.CHINESE, true,
        new EngineResult(true, "", "shi", values, 0, true, false), ""));
    return view;
  }

  private void layout(View view, int width)
  {
    int w = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
    int h = View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), 900), View.MeasureSpec.AT_MOST);
    view.measure(w, h); view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    view.measure(w, h); view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
  }

  @Test public void actualTextWidthsFitNarrowWideAndLargeFontRows()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      String[] values = {"是", "时间", "中华人民共和国", "世界", "事", "使", "市", "十", "师", "诗"};
      for (float scale : new float[] {1f, 1.8f})
        for (int width : new int[] {240, 700})
        {
          Context context = themed(scale);
          RimeCandidatesView view = strip(context, values);
          layout(view, dp(context, width));
          ViewGroup row = view.findViewById(R.id.miancak_compact_candidates);
          assertTrue(row.getChildCount() > 0);
          int previousRight = 0;
          for (int i = 0; i < row.getChildCount(); i++)
          {
            TextView cell = (TextView)row.getChildAt(i);
            assertEquals(values[i], cell.getText().toString());
            assertTrue(cell.getLeft() >= previousRight);
            assertTrue("Candidate exceeds available row width", cell.getRight() <= row.getWidth());
            assertEquals("Only an overlong first item may be ellipsized", 0, cell.getLayout().getEllipsisCount(0));
            previousRight = cell.getRight();
          }
        }
    });
  }

  @Test public void overlongTextAndExpansionDoNotGrowTheKeyboardSurface()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
      Context context = themed(1.8f);
      String longText = "中华人民共和国的完整长候选内容必须可以展开查看，不得超过键盘分配的宽度和高度";
      RimeCandidatesView view = strip(context, new String[] {longText, "后续候选"});
      layout(view, dp(context, 240));
      ViewGroup row = view.findViewById(R.id.miancak_compact_candidates);
      assertEquals(1, row.getChildCount());
      TextView first = (TextView)row.getChildAt(0);
      assertTrue("Long first candidate must show truncation: cell=" + first.getWidth()
          + " layout=" + first.getLayout().getWidth() + " text=" + first.getPaint().measureText(longText),
          first.getLayout().getEllipsisCount(0) > 0);
      KeyboardSurface surface = new KeyboardSurface(context, null);
      View base = new View(context);
      surface.addView(base, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 180)));
      view.attachSurface(surface, base);
      layout(surface, dp(context, 240));
      int before = surface.getMeasuredHeight();
      view.findViewById(R.id.miancak_expand_candidates).performClick();
      layout(surface, dp(context, 240));
      assertEquals(before, surface.getMeasuredHeight());
      assertEquals(View.INVISIBLE, base.getVisibility());
      ViewGroup scroll = surface.findViewById(R.id.miancak_expanded_candidates);
      ViewGroup flow = (ViewGroup)scroll.getChildAt(0);
      assertEquals(2, flow.getChildCount());
      assertEquals(longText, ((TextView)flow.getChildAt(0)).getText().toString());
      assertTrue(flow.getChildAt(0).getMeasuredWidth() <= scroll.getMeasuredWidth());
      view.collapse();
      assertEquals(View.VISIBLE, base.getVisibility());
    });
  }
}
