package juloo.keyboard2;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.PatternMatcher;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** APK resource/link checks on the prepared interaction guest, never first-install setup. */
@RunWith(AndroidJUnit4.class)
public final class LauncherIdentityTest
{
  private static final String HOME = "https://github.com/AuroraLilja8514/miancak";
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

  private Activity launcher()
  {
    Intent intent = new Intent(instrumentation.getTargetContext(), LauncherActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Activity activity = instrumentation.startActivitySync(intent);
    instrumentation.waitForIdleSync();
    return activity;
  }

  private void checkLink(TextView link, String expected, boolean click)
  {
    assertEquals(expected, link.getText().toString());
    assertTrue(link.getLinksClickable());
    assertTrue(link.getMovementMethod() instanceof LinkMovementMethod);
    assertTrue(link.getText() instanceof Spanned);
    Spanned text = (Spanned)link.getText();
    URLSpan[] spans = text.getSpans(0, text.length(), URLSpan.class);
    assertEquals(1, spans.length);
    assertEquals(expected, spans[0].getURL());
    assertEquals(0, text.getSpanStart(spans[0]));
    assertEquals(text.length(), text.getSpanEnd(spans[0]));
    assertNotNull(link.getLayout());
    for (int line = 0; line < link.getLineCount(); line++)
    {
      assertEquals(0, link.getLayout().getEllipsisCount(line));
      assertTrue("Link exceeds rendered width", link.getLayout().getLineWidth(line)
          <= link.getWidth() - link.getTotalPaddingLeft() - link.getTotalPaddingRight() + 1);
    }
    if (!click) return;
    // Exact target monitor checks ACTION_VIEW + scheme + host + path. A second
    // monitor blocks any other web destination as well; no browser is opened.
    Uri uri = Uri.parse(expected);
    IntentFilter exact = new IntentFilter(Intent.ACTION_VIEW);
    exact.addDataScheme(uri.getScheme()); exact.addDataAuthority(uri.getHost(), null);
    exact.addDataPath(uri.getPath(), PatternMatcher.PATTERN_LITERAL);
    IntentFilter anyWeb = new IntentFilter(Intent.ACTION_VIEW);
    anyWeb.addDataScheme("https"); anyWeb.addDataScheme("http");
    Instrumentation.ActivityResult blocked = new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
    Instrumentation.ActivityMonitor target = instrumentation.addMonitor(exact, blocked, true);
    Instrumentation.ActivityMonitor other = instrumentation.addMonitor(anyWeb, blocked, true);
    try
    {
      // Exercise the actual TextView movement method with a rendered link touch,
      // rather than constructing an Intent or calling a test-only click handler.
      int offset = text.getSpanStart(spans[0]) + 1;
      int line = link.getLayout().getLineForOffset(offset);
      float x = link.getTotalPaddingLeft() + link.getLayout().getPrimaryHorizontal(offset);
      float y = link.getTotalPaddingTop() + (link.getLayout().getLineTop(line) + link.getLayout().getLineBottom(line)) / 2f;
      long now = SystemClock.uptimeMillis();
      MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
      MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0);
      try { assertTrue(link.dispatchTouchEvent(down)); assertTrue(link.dispatchTouchEvent(up)); }
      finally { down.recycle(); up.recycle(); }
      assertEquals("Outgoing ACTION_VIEW must match the exact project target", 1, target.getHits());
      assertEquals("Unexpected outgoing web target", 0, other.getHits());
    }
    finally { instrumentation.removeMonitor(other); instrumentation.removeMonitor(target); }
  }

  @Test public void shippedLauncherClicksProjectAndIssuesWithoutOpeningBrowser()
  {
    Activity activity = launcher();
    try
    {
      instrumentation.runOnMainSync(() -> {
        checkLink(activity.findViewById(R.id.launcher_repo_url), HOME, true);
        checkLink(activity.findViewById(R.id.launcher_issues_url), HOME + "/issues", true);
      });
    }
    finally { instrumentation.runOnMainSync(activity::finish); }
  }

  @Test public void englishChineseAndFallbackLinksWrapAndClickAtNarrowWideAndLargeFontSizes()
  {
    Activity activity = launcher();
    try
    {
      instrumentation.runOnMainSync(() -> {
        String english = activity.createConfigurationContext(englishConfiguration(activity))
            .getString(R.string.launcher_description);
        for (String locale : new String[] {"en-US", "zh-CN", "de", "ar", "zz-ZZ"})
          for (float scale : new float[] {1f, 1.8f})
            for (int width : new int[] {240, 700})
            {
              Configuration configuration = new Configuration(activity.getResources().getConfiguration());
              configuration.setLocale(Locale.forLanguageTag(locale)); configuration.fontScale = scale;
              ContextThemeWrapper context = new ContextThemeWrapper(activity, R.style.Light);
              context.applyOverrideConfiguration(configuration);
              assertEquals("MIANCAK", context.getString(R.string.app_name_release));
              assertEquals("MIANCAK (Debug)", context.getString(R.string.app_name));
              String instructions = context.getString(R.string.launcher_description);
              if (locale.equals("zh-CN")) assertTrue(instructions.startsWith("MIANCAK 使用"));
              else assertEquals("Unreviewed launcher translation must fall back", english, instructions);
              View root = LayoutInflater.from(context).inflate(R.layout.launcher_activity, null);
              int pixels = Math.round(width * context.getResources().getDisplayMetrics().density);
              root.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
              root.layout(0, 0, pixels, root.getMeasuredHeight());
              checkLink(root.findViewById(R.id.launcher_repo_url), HOME, true);
              checkLink(root.findViewById(R.id.launcher_issues_url), HOME + "/issues", true);
            }
      });
    }
    finally { instrumentation.runOnMainSync(activity::finish); }
  }

  private Configuration englishConfiguration(Activity activity)
  {
    Configuration configuration = new Configuration(activity.getResources().getConfiguration());
    configuration.setLocale(Locale.US);
    return configuration;
  }
}
