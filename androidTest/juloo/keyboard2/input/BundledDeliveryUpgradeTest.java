package juloo.keyboard2.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.system.Os;
import android.system.StructStat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import juloo.keyboard2.DirectBootAwarePreferences;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Only for the guarded, disposable, same-signer bundled-to-split fixture. */
@RunWith(AndroidJUnit4.class)
public final class BundledDeliveryUpgradeTest
{
  private static final String MODEL = "wanxiang-lts-zh-hans.gram";
  private static final String PATCH = "wanxiang.custom.yaml";
  private static final String ADDON = "miancak_addon.dict.yaml";
  private static final String LEARNING_CODE = "ni'hao'ni'hao";
  private static final byte[] CUSTOM = "\ufeff# 合成测试\npatch: {menu/page_size: 3}\n".getBytes(StandardCharsets.UTF_8);

  @Test public void keepOfflineDataAndCustomizationsOnOverwrite() throws Exception
  {
    String phase = InstrumentationRegistry.getArguments().getString("deliveryPhase", "");
    boolean prepare = "prepare".equals(phase);
    assertTrue("Use the guarded upgrade driver", prepare || "overwrite".equals(phase) || "reboot".equals(phase));
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertEquals("org.miancak.keyboard.debug", app.getPackageName());
    assertEquals(prepare ? 6L : 7L, app.getPackageManager()
        .getPackageInfo(app.getPackageName(), PackageManager.PackageInfoFlags.of(0)).getLongVersionCode());
    List<String> assets = Arrays.asList(app.getAssets().list("rime"));
    assertEquals(prepare, assets.contains(MODEL));
    assertEquals(!prepare, assets.contains("delivery.json"));
    ConnectivityManager network = app.getSystemService(ConnectivityManager.class);
    assertNotNull(network);
    assertNull("The compatible deployment must be reusable offline", network.getActiveNetwork());

    File snapshot = new File(app.getFilesDir(), "bundled-delivery-upgrade.json");
    WordTableStore store = new WordTableStore(app);
    SharedPreferences prefs = DirectBootAwarePreferences.get_shared_preferences(app);
    byte[] dictionary;
    try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
        .getAssets().open("word-tables/" + ADDON)) { dictionary = input.readAllBytes(); }
    if (prepare)
    {
      assertFalse("Need a fresh fixture install", snapshot.exists());
      assertTrue(store.view().patches.isEmpty());
      assertTrue(store.view().entries.isEmpty());
      assertTrue(prefs.edit().putString("split_layout", "landscape")
          .putBoolean("extra_key_superscript", false).putBoolean("lock_double_tap", false).commit());
      store.importConfigs(List.of(source(PATCH, CUSTOM)));
      store.importTables(List.of(source(ADDON, dictionary)));
      store.apply(WordTableStore.BUNDLED, List.of(store.view().entries.get(0).id), List.of(store.view().patches.get(0).id));
    }
    assertEquals("landscape", prefs.getString("split_layout", ""));
    assertFalse(prefs.getBoolean("extra_key_superscript", true));
    assertFalse(prefs.getBoolean("lock_double_tap", true));

    // The split app's normal packaged-data entry point must succeed without
    // download/import. A retained selection alone could hide missing Base data.
    RimeData.Paths bundled = RimeData.bundled(app);
    File model = new File(bundled.shared, MODEL);
    assertTrue(model.isFile());
    StructStat modelStat = Os.stat(model.getAbsolutePath());
    RimeData.Paths active = store.currentPaths();
    assertFalse("Must use the current compatible deployment", active.retained);
    assertEquals(bundled.user, active.user);
    WordTableStore.View view = store.view();
    assertEquals(1, view.entries.size());
    assertEquals(1, view.patches.size());
    String addonId = view.entries.get(0).id, patchId = view.patches.get(0).id;
    assertEquals(ADDON, view.entries.get(0).filename);
    assertEquals(PATCH, view.patches.get(0).filename);
    assertEquals(WordTableStore.BUNDLED, view.selection.base);
    assertEquals(List.of(addonId), view.selection.extras);
    assertEquals(List.of(patchId), view.selection.patches);
    assertFalse(view.selection.slot.isEmpty());
    assertEquals(view.selection.slot, active.shared.getName());
    File catalog = new File(app.getFilesDir(), "rime/word-tables");
    assertArrayEquals(CUSTOM, Files.readAllBytes(new File(catalog, "config-patches/" + patchId + "/source.custom.yaml").toPath()));
    assertArrayEquals(dictionary, Files.readAllBytes(new File(catalog, "tables/" + addonId + "/source.dict.yaml").toPath()));

    // Snapshot only synthetic fixture values inside the application sandbox.
    String learned;
    if (prepare)
    {
      try (RimeEngine engine = new RimeEngine(active))
      {
        engine.setLearning(true);
        assertTrue(engine.processText(LEARNING_CODE).composing);
        learned = engine.confirm().committed;
        assertFalse("The real bundled engine must commit a phrase", learned.isEmpty());
      }
    }
    else
    {
      JSONObject before = new JSONObject(new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8));
      assertEquals(before.getString("bundle"), bundled.shared.getName());
      assertEquals(before.getString("slot"), view.selection.slot);
      assertEquals("A compatible model should be reused in place", before.getLong("inode"), modelStat.st_ino);
      assertEquals(before.getLong("modified"), model.lastModified());
      learned = before.getString("learned");
      Map<String, String> actual = RimeNativeTest.learningHashes(active.user, active.user);
      JSONObject expected = before.getJSONObject("learning");
      assertTrue("The overwrite or reboot changed the original learning files",
          expected.length() == actual.size() && actual.entrySet().stream()
              .allMatch(entry -> entry.getValue().equals(expected.optString(entry.getKey()))));
    }
    assertTrue("Expected a real durable learning entry in the existing database",
        WordTableNativeTest.containsLearned(active.user, learned.getBytes(StandardCharsets.UTF_8), false));
    Map<String, String> learning = RimeNativeTest.learningHashes(active.user, active.user);
    assertFalse(learning.isEmpty());
    try (RimeEngine engine = new RimeEngine(active))
    {
      engine.setLearning(false);
      assertEquals("The selected YAML patch must still affect the engine", 3, engine.processText("shi").candidates.size());
      engine.clear();
      assertTrue(engine.processText("ceshi").composing);
      int custom = engine.candidates(50).candidates.indexOf("MIANCAK·附加词表");
      assertTrue("The selected dictionary must still provide its entry", custom >= 0);
      assertEquals("MIANCAK·附加词表", engine.select(custom).committed);
      assertTrue(engine.processText("nihao").candidates.contains("你好"));
    }
    assertTrue("The offline probe must leave the preserved learning data alone",
        learning.equals(RimeNativeTest.learningHashes(active.user, active.user)));
    assertNull(network.getActiveNetwork());
    if (prepare)
      Files.write(snapshot.toPath(), new JSONObject().put("bundle", bundled.shared.getName())
          .put("slot", view.selection.slot).put("inode", modelStat.st_ino).put("modified", model.lastModified())
          .put("learned", learned).put("learning", new JSONObject(learning)).toString().getBytes(StandardCharsets.UTF_8));
  }

  private static WordTableStore.Source source(String name, byte[] content)
  {
    return new WordTableStore.Source() {
      @Override public String filename() { return name; }
      @Override public InputStream open() { return new ByteArrayInputStream(content); }
    };
  }
}
