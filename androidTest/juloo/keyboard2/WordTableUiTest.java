package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.inputmethod.*;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import juloo.keyboard2.input.WordTableStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Shipped settings, actual DocumentsUI imports and real IME editor output. */
@RunWith(AndroidJUnit4.class)
public final class WordTableUiTest
{
  private Instrumentation instrumentation;
  private Context context;
  private UiDevice device;
  private String pkg;
  private WordTableStore store;
  @Test public void importSwitchUseAndKeepVocabularyAcrossRealApkUpgrade() throws Exception
  {
    instrumentation = InstrumentationRegistry.getInstrumentation(); context = instrumentation.getTargetContext();
    device = UiDevice.getInstance(instrumentation); pkg = context.getPackageName(); store = new WordTableStore(context);
    device.wakeUp(); device.setOrientationNatural();
    String phase = InstrumentationRegistry.getArguments().getString("wordPhase", "prepare");
    assertEquals(phase.equals("prepare") ? 2 : 4, context.getPackageManager().getPackageInfo(pkg, 0).versionCode);
    try
    {
      rebindFixture(); openManager();
      if (phase.equals("prepare"))
      {
        String layout = PageKeyOverrides.export(DirectBootAwarePreferences.get_shared_preferences(context));
        assertTrue(store.view().entries.isEmpty());
        importFile("pinyin_simp.dict.yaml", false); choose("Base pinyin_simp"); apply();
        assertEquals(id("pinyin_simp"), store.view().selection.base);
        input("nihao", "你好", false, true); // actual learning in an ordinary field
        openManager();
        int count = store.view().entries.size(); click("Import Rime word tables"); documents(); device.pressBack(); ready(false);
        assertEquals(count, store.view().entries.size());
        importFile("others.dict.yaml", true); assertEquals(count, store.view().entries.size());
        assertTrue(device.findObject(By.res(pkg, "word_tables_status")).getText().contains("Pinyin"));
        importFile("8105.dict.yaml", false); choose("Base 8105"); apply();
        assertEquals(id("8105"), store.view().selection.base); input("biang", "𰻝", true, true);
        openManager(); importFile("miancak_addon.dict.yaml", false);
        choose("Supplement miancak_addon"); apply();
        input("ceshi", "MIANCAK·附加词表", true, true);
        openManager(); choose("Supplement miancak_addon"); apply();
        input("ceshi", "MIANCAK·附加词表", true, false);
        openManager(); click("Restore bundled word tables"); ready(false);
        assertEquals(WordTableStore.BUNDLED, store.view().selection.base); assertTrue(store.view().selection.extras.isEmpty());
        input("nihao", "你好", true, true);
        if (WordTableStore.hasFormalBundledPack(context))
        {
          openManager(); click("Use legacy vocabulary"); ready(false);
          assertEquals(id(WordTableStore.LEGACY), store.view().selection.base);
          screenshot("word-legacy-choice");
          input("nihao", "你好", true, true);
          openManager(); click("Restore bundled word tables"); ready(false);
          assertEquals(WordTableStore.BUNDLED, store.view().selection.base);
          input("nihao", "你好", true, true);
        }
        openManager(); choose("Base 8105"); choose("Supplement miancak_addon"); apply();
        assertEquals(layout, PageKeyOverrides.export(DirectBootAwarePreferences.get_shared_preferences(context)));
        JSONObject expected = new JSONObject().put("base", id("8105")).put("addon", id("miancak_addon"))
          .put("entryCount", store.view().entries.size()).put("layout", layout);
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "word-ui-expected.json")))
        { out.write(expected.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
      }
      else
      {
        JSONObject expected = expected();
        assertEquals(expected.getInt("entryCount"), store.view().entries.size()); assertEquals(expected.getString("base"), store.view().selection.base);
        assertEquals(Arrays.asList(expected.getString("addon")), store.view().selection.extras);
        assertEquals(expected.getString("layout"), PageKeyOverrides.export(DirectBootAwarePreferences.get_shared_preferences(context)));
        input("biang", "𰻝", true, true); input("ceshi", "MIANCAK·附加词表", true, true);
        openManager(); apply(); // release native handles before the host fingerprints learning data
      }
      writeLearningIndex();
      screenshot("word-tables-" + phase + "-ready");
    }
    finally { screenshot("word-tables-" + phase + "-final"); device.unfreezeRotation(); }
  }
  private JSONObject expected() throws Exception
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = new FileInputStream(new File(context.getFilesDir(), "word-ui-expected.json")))
    { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
    return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
  }
  private void writeLearningIndex() throws Exception
  {
    List<String> files = new ArrayList<>();
    learningFiles(new File(context.getFilesDir(), "rime/user"), false, files);
    assertFalse("Real learning database is missing", files.isEmpty()); Collections.sort(files);
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(context.getFilesDir(), "word-learning-index.txt")), StandardCharsets.UTF_8))
    { for (String path : files) writer.write(path + "\n"); }
  }
  private void learningFiles(File file, boolean inDatabase, List<String> out) throws Exception
  {
    boolean database = inDatabase || file.getName().contains(".userdb");
    if (file.isDirectory())
    { for (File child : file.listFiles()) learningFiles(child, database, out); }
    else if (database)
      out.add("files/" + file.getCanonicalPath().substring(context.getFilesDir().getCanonicalPath().length() + 1));
  }
  private String id(String name) throws Exception
  { for (WordTableStore.Entry entry : store.view().entries) if (entry.name.equals(name)) return entry.id; throw new AssertionError(name); }
  private void rebindFixture() throws Exception
  {
    String component = pkg + "/juloo.keyboard2.Keyboard2", alternate = null;
    InputMethodManager manager = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
    for (InputMethodInfo method : manager.getEnabledInputMethodList())
    {
      boolean keyboard = false;
      for (int i = 0; i < method.getSubtypeCount(); i++)
      { InputMethodSubtype subtype = method.getSubtypeAt(i); if (subtype.getMode().equals("keyboard") && !subtype.isAuxiliary()) keyboard = true; }
      if (keyboard && !method.getId().equals(component) && method.getId().matches("[a-zA-Z0-9_./]+")) { alternate = method.getId(); break; }
    }
    assertNotNull("Prepared fixture needs a fallback keyboard", alternate);
    instrumentation.startActivitySync(new Intent(context, LauncherActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(
        new UiSelector().resourceId(pkg + ":id/launcher_tryhere_area"));
    UiObject2 field = device.wait(Until.findObject(By.res(pkg, "launcher_tryhere_area")), 15000); assertNotNull(field); field.click();
    device.executeShellCommand("ime set " + alternate);
    assertTrue("Fallback IME did not finish binding",
      device.wait(Until.hasObject(By.pkg(alternate.substring(0, alternate.indexOf('/')))), 15000));
    device.executeShellCommand("ime set " + component);
    assertNotNull("Prepared IME did not finish binding", device.wait(Until.findObject(By.res(pkg, "keyboard_view")), 15000));
  }
  private void openManager() throws Exception
  {
    context.startActivity(new Intent(context, LauncherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    UiObject2 settings = device.wait(Until.findObject(By.res(pkg, "btnLaunchSettingsActivity")), 15000); assertNotNull(settings); settings.click();
    // The launcher's resources can still have the preceding editor's locale
    // until Settings has resumed with Android's per-app language override.
    awaitActivity(SettingsActivity.class);
    String label = uiString(R.string.words_title);
    new UiScrollable(new UiSelector().scrollable(true)).scrollToBeginning(30);
    UiObject2 entry = device.wait(Until.findObject(By.text(label)), 15000);
    if (entry == null)
    { new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text(label)); entry = device.findObject(By.text(label)); }
    assertNotNull("Shipped vocabulary settings entry missing", entry); entry.click(); ready(false);
  }
  private void awaitActivity(Class<?> expected)
  {
    long deadline = SystemClock.uptimeMillis() + 15000;
    while (SystemClock.uptimeMillis() < deadline)
    {
      boolean[] resumed = {false};
      instrumentation.runOnMainSync(() -> {
        for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
          if (expected.isInstance(activity)) resumed[0] = true;
      });
      if (resumed[0]) return;
      SystemClock.sleep(100);
    }
    fail("Activity did not resume: " + expected.getSimpleName());
  }
  private void choose(String description) throws Exception
  {
    if (description.startsWith("Base "))
    {
      String name = description.substring(5), label = uiString(R.string.words_bundled);
      if (!name.equals("bundled"))
      {
        boolean found = false;
        for (WordTableStore.Entry entry : store.view().entries)
          if (entry.name.equals(name)) { label = entry.name + (entry.version.isEmpty() ? "" : " · " + entry.version); found = true; }
        assertTrue("Imported table absent before choosing its radio: " + name, found);
      }
      description = uiString(R.string.words_base_choice, label);
    }
    else if (description.startsWith("Supplement ")) description = uiString(R.string.words_extra_choice, description.substring(11));
    if (device.findObject(By.desc(description)) == null)
      new UiScrollable(new UiSelector().resourceId(pkg + ":id/word_tables_list")).scrollIntoView(new UiSelector().description(description));
    click(description);
  }
  private void click(String description) throws Exception
  {
    String resource = description.equals("Use legacy vocabulary") ? "word_tables_legacy"
      : description.equals("Import Rime word tables") ? "word_tables_import"
      : description.equals("Apply word tables") ? "word_tables_apply"
      : description.equals("Restore bundled word tables") ? "word_tables_restore" : null;
    if (resource != null)
    {
      UiObject2 button = device.wait(Until.findObject(By.res(pkg, resource)), 15000); assertNotNull(button);
      button.click(); device.waitForIdle(); return;
    }
    assertTrue(device.wait(Until.hasObject(By.desc(description)), 15000));
    device.findObject(new UiSelector().description(description)).click(); device.waitForIdle();
  }
  private void apply() throws Exception { click("Apply word tables"); ready(false); }
  private void ready(boolean expectError) throws Exception
  {
    long deadline = SystemClock.uptimeMillis() + 120000;
    while (SystemClock.uptimeMillis() < deadline)
    {
      try
      {
        UiObject2 status = device.findObject(By.res(pkg, "word_tables_status"));
        if (status != null)
        {
          String success = uiString(R.string.words_ready), failure = uiString(R.string.words_error, "");
          String text = status.getText();
          if (text.startsWith(failure)) { assertTrue(text, expectError); return; }
          // A provider return may expose the preceding Ready frame before
          // onActivityResult. A rejected source must actually report an error.
          if (text.startsWith(success) && !expectError) return;
        }
      }
      catch (StaleObjectException changed) {}
      SystemClock.sleep(100);
    }
    fail("Vocabulary operation did not finish");
  }
  private String uiString(int resource, Object... arguments)
  {
    String[] result = {context.getString(resource, arguments)};
    instrumentation.runOnMainSync(() -> {
      for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
          .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
        if (activity.getPackageName().equals(pkg)) result[0] = activity.getString(resource, arguments);
    });
    return result[0];
  }
  private void documents()
  {
    assertTrue("Android document picker missing", device.wait(Until.hasObject(By.pkg(Pattern.compile(".*documentsui"))), 15000));
  }
  private void importFile(String filename, boolean invalid) throws Exception
  {
    int previousCount = store.view().entries.size();
    click("Import Rime word tables"); documents();
    UiObject2 file = device.wait(Until.findObject(By.text(filename)), 4000);
    if (file == null)
    {
      UiObject2 downloads = device.findObject(By.text("Downloads"));
      if (downloads == null)
      {
        UiObject2 roots = device.findObject(By.desc("Show roots"));
        if (roots == null) roots = device.findObject(By.desc("Open navigation drawer"));
        assertNotNull("Android document roots missing", roots); roots.click();
        downloads = device.wait(Until.findObject(By.text("Downloads")), 15000);
      }
      assertNotNull(downloads); downloads.click(); device.waitForIdle();
      file = device.wait(Until.findObject(By.text(filename)), 15000);
    }
    if (file == null && android.os.Build.VERSION.SDK_INT <= 23)
    {
      // Marshmallow's DownloadsProvider lists DownloadManager records, not
      // arbitrary adb-pushed files. Browse the real external-storage provider
      // through its public UI; keep the application's granted-URI import path.
      UiObject2 menu = device.findObject(By.desc("More options")); assertNotNull(menu); menu.click();
      UiObject2 show = device.wait(Until.findObject(By.text(Pattern.compile("Show (internal storage|SD card)"))), 3000);
      if (show != null) show.click(); else device.pressBack();
      UiObject2 roots = device.findObject(By.desc("Show roots")); assertNotNull(roots); roots.click();
      UiObject2 storage = device.wait(Until.findObject(By.text(Pattern.compile("Internal storage|Internal shared storage|SD card"))), 15000);
      assertNotNull("Physical storage root absent", storage); storage.click();
      UiObject2 download = device.wait(Until.findObject(By.text("Download")), 15000); assertNotNull(download); download.click();
      file = device.wait(Until.findObject(By.text(filename)), 15000);
    }
    assertNotNull("Real source file absent from Downloads: " + filename, file); file.click(); ready(invalid);
    if (!invalid)
    {
      long deadline = SystemClock.uptimeMillis() + 120000;
      while (store.view().entries.size() == previousCount && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
      assertEquals("A successful import must add the selected source", previousCount + 1, store.view().entries.size());
      ready(false);
    }
    screenshot("word-import-" + filename.replace('.', '-') + (invalid ? "-rejected" : ""));
  }
  private void input(String code, String expected, boolean privateField, boolean present) throws Exception
  {
    ImeTestActivity activity = (ImeTestActivity)instrumentation.startActivitySync(new Intent(context, ImeTestActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    EditText edit = privateField ? activity.privateField : activity.first;
    String description = privateField ? "private_editor" : "first_editor";
    UiObject2 field = device.wait(Until.findObject(By.desc(description)), 15000); assertNotNull(field); field.click();
    instrumentation.runOnMainSync(() -> { edit.requestFocus(); edit.post(() -> ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(edit, 0)); });
    assertNotNull(device.wait(Until.findObject(By.res(pkg, "keyboard_view")), 20000));
    UiObject2 language = device.wait(Until.findObject(By.res(pkg, "miancak_language")), 15000);
    if (language.getText().equals("EN")) language.click();
    for (char letter : code.toCharArray()) tap(letter, false);
    long compositionDeadline = SystemClock.uptimeMillis() + 15000;
    boolean complete = false;
    while (SystemClock.uptimeMillis() < compositionDeadline)
    {
      try
      {
        UiObject2 preedit = device.findObject(By.res(pkg, "miancak_preedit"));
        if (preedit != null && preedit.getText().replace(" ", "").replace("'", "").equals(code)) { complete = true; break; }
      }
      catch (StaleObjectException changed) {}
      SystemClock.sleep(50);
    }
    assertTrue("Full Pinyin request did not reach the visible preedit", complete); device.waitForIdle();
    assertNotNull(device.wait(Until.findObject(By.descStartsWith("Candidate 1: ")), 20000));
    if (present)
    {
      UiObject2 candidate = device.findObject(By.descStartsWith("Candidate ").text(expected));
      if (candidate == null)
      { device.findObject(By.res(pkg, "miancak_expand_candidates")).click(); candidate = device.wait(Until.findObject(By.descStartsWith("Candidate ").text(expected)), 15000); }
      assertNotNull("Expected vocabulary candidate: " + expected, candidate); candidate.click(); tap('c', true);
      assertTrue("Exact committed editor text mismatch", device.wait(Until.hasObject(By.desc(description).text(expected + "。")), 15000));
    }
    else
    {
      assertNotEquals(expected, device.findObject(By.descStartsWith("Candidate 1: ")).getText());
      device.findObject(By.res(pkg, "miancak_cancel")).click();
    }
    screenshot("word-input-" + code + (present ? "-present" : "-disabled"));
  }
  private void tap(char letter, boolean northwest)
  {
    String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    UiObject2 keyboard = device.wait(Until.findObject(By.res(pkg, "keyboard_view")), 15000); assertNotNull(keyboard);
    Rect bounds = keyboard.getVisibleBounds(); Config config = Config.globalConfig();
    float unit = (bounds.width() - 2 * config.horizontal_margin) / 10f;
    float height = Math.min(config.keyboard_rows_height_pixels, (config.screenHeightPixels - config.keyboard_rows_height_pixels) / 3.95f);
    for (int r = 0; r < rows.length; r++)
    {
      int c = rows[r].indexOf(letter); if (c < 0) continue;
      int x = Math.round(bounds.left + config.horizontal_margin + (c + (r == 0 ? 0.5f : r == 1 ? 1f : 2f)) * unit);
      int y = Math.round(bounds.top + config.marginTop + (r + 0.5f) * height);
      if (northwest) assertTrue(device.swipe(x, y, Math.round(x - unit * 0.85f), Math.round(y - height * 0.65f), 5)); else assertTrue(device.click(x, y));
      return;
    }
    throw new AssertionError(letter);
  }
  private void screenshot(String name) throws Exception
  {
    instrumentation.waitForIdleSync(); device.waitForIdle(); File folder = context.getExternalFilesDir("test-results");
    assertTrue(folder.isDirectory() || folder.mkdirs()); assertTrue(device.takeScreenshot(new File(folder, name + ".png")));
    device.dumpWindowHierarchy(new File(folder, name + ".xml"));
  }
}
