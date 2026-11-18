package juloo.keyboard2.input;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Isolated synthetic catalog; no live IME and no edits to real user sources. */
@RunWith(AndroidJUnit4.class)
public class RimeConfigNativeTest
{
  private File root;
  private WordTableStore store;
  @Before public void setup() throws Exception
  {
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    root = new File(app.getCacheDir(), "rime-config-" + UUID.randomUUID()); assertTrue(root.mkdir());
    Context isolated = new ContextWrapper(app) {
      @Override public Context getApplicationContext() { return this; }
      @Override public File getFilesDir() { return root; }
    };
    store = new WordTableStore(isolated);
  }
  @After public void cleanup() throws Exception { if (root != null) remove(root); }
  private WordTableStore.Source source(String name, String text)
  {
    return new WordTableStore.Source() {
      public String filename() { return name; }
      public InputStream open() { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    };
  }
  private String importPatch(String name, String text) throws Exception
  {
    store.importConfigs(Collections.singletonList(source(name, text)));
    for (RimeConfigStore.Entry entry : store.view().patches) if (entry.filename.equals(name)) return entry.id;
    throw new AssertionError("missing patch");
  }
  @Test public void earlierSelectionWithoutPatchFieldIsReadWithoutRewrite() throws Exception
  {
    String old = "{\"format\":1,\"current\":{\"base\":\"bundled\",\"extras\":[],\"slot\":\"\"},\"previous\":{\"base\":\"bundled\",\"extras\":[],\"slot\":\"\"},\"recovery\":\"\"}\n";
    File selection = new File(root, "rime/word-tables/selection.json");
    Files.write(selection.toPath(), old.getBytes(StandardCharsets.UTF_8));
    assertTrue(store.view().selection.patches.isEmpty());
    assertEquals(WordTableStore.BUNDLED, store.view().selection.base);
    assertEquals(old, new String(Files.readAllBytes(selection.toPath()), StandardCharsets.UTF_8));
  }
  @Test public void originalsRemainExactAndFailedBatchDoesNotPartiallyImport() throws Exception
  {
    String original = "\ufeff# 我的设置\npatch: {menu/page_size: 3}\n";
    String id = importPatch("default.custom.yaml", original);
    File kept = new File(root, "rime/word-tables/config-patches/" + id + "/source.custom.yaml");
    assertArrayEquals(original.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(kept.toPath()));
    try {
      store.importConfigs(Arrays.asList(source("wanxiang.custom.yaml", "patch: {translator/max_homophones: 4}"), source("miancak_pinyin_private.custom.yaml", "patch: {translator/enable_user_dict: true}")));
      fail("unsafe batch accepted");
    } catch (IOException expected) { assertNotNull(expected.getMessage()); }
    assertEquals(1, store.view().patches.size()); assertTrue(store.view().selection.patches.isEmpty());
  }
  @Test public void applyAndDisableRetainOriginalsAndRealInput() throws Exception
  {
    String id = importPatch("wanxiang.custom.yaml", "patch:\n  menu: {page_size: 3}\n  translator: {max_homophones: 4}\n");
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(id));
    assertEquals(Collections.singletonList(id), store.view().selection.patches);
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); assertEquals(3, engine.processText("shi").candidates.size()); assertFalse(engine.confirm().committed.isEmpty()); }
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.emptyList());
    assertTrue(store.view().selection.patches.isEmpty()); assertEquals(1, store.view().patches.size());
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); assertEquals(10, engine.processText("shi").candidates.size()); }
  }
  @Test public void unsupportedExecutableAndAmbiguousYamlNeverEnterCatalog() throws Exception
  {
    for (String text : new String[] {
        "patch: {engine/processors: [lua_processor@evil]}", "patch: {translator/user_dict: other}",
        "patch: {translator/enable_user_dict: yes}", "patch: {grammar/language: other}",
        "patch: {schema/schema_id: other}", "patch: {speller/algebra/+: [evil]}",
        "patch: &a {menu/page_size: 3}", "patch: {menu/page_size: *a}",
        "patch: {menu/page_size: !!int 3}", "patch: {menu/page_size: 0}",
        "patch: {menu/page_size: null}", "patch: {menu/page_size: 3, menu/page_size: 5}",
        "patch: {menu: {page_size: 3}, menu/page_size: 5}",
        "patch: {switches/@0/reset: 1}", "patch: {switches/@7/reset: 4}",
        "patch: {menu/page_size: 3}\n---\npatch: {menu/page_size: 5}"})
    {
      try { store.importConfigs(Collections.singletonList(source("wanxiang.custom.yaml", text))); fail("Unsupported patch accepted"); }
      catch (IllegalStateException expected) { assertNotNull(expected.getMessage()); }
      assertTrue(store.view().patches.isEmpty()); assertTrue(store.view().selection.patches.isEmpty());
    }
  }
  @Test public void corruptionCannotReplaceWorkingSelectionAndPreviousKeepsItsPatch() throws Exception
  {
    String id = importPatch("default.custom.yaml", "patch: {menu/page_size: 5}");
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(id));
    String working = store.view().selection.slot;
    File original = new File(root, "rime/word-tables/config-patches/" + id + "/source.custom.yaml");
    Files.write(original.toPath(), "patch: {engine/processors: [evil]}".getBytes(StandardCharsets.UTF_8));
    try { store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(id)); fail("corrupt patch applied"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("integrity")); }
    assertEquals(working, store.view().selection.slot);
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); assertEquals(5, engine.processText("shi").candidates.size()); }
    // The compiled previous deployment remains usable even if an original
    // becomes damaged; no automatic deletion or source rewrite repairs it.
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.emptyList());
    try { store.removeConfig(id); fail("previous recovery source removed"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("recovery")); }
    store.recover(); assertEquals(working, store.view().selection.slot);
  }
  @Test public void damagedCatalogDoesNotBlockDisablingCompiledPatches() throws Exception
  {
    String id = importPatch("default.custom.yaml", "patch: {menu/page_size: 3}");
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(id));
    File catalog = new File(root, "rime/word-tables/config-patches/catalog.json");
    Files.write(catalog.toPath(), "damaged catalog".getBytes(StandardCharsets.UTF_8));
    store.disableConfigs();
    assertEquals("damaged catalog", new String(Files.readAllBytes(catalog.toPath()), StandardCharsets.UTF_8));
    assertTrue(new File(catalog.getParentFile(), id + "/source.custom.yaml").isFile());
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); assertEquals(10, engine.processText("shi").candidates.size()); }
  }
  @Test public void pinnedDoublePinyinAppliesToNormalPrivateAndCompanions() throws Exception
  {
    String id = importPatch("wanxiang.custom.yaml", "patch:\n  speller/algebra:\n    __patch: [wanxiang_algebra:/base/小鹤双拼]\n  translator/enable_user_dict: false\n");
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(id));
    assertTrue(RimeData.validDeployment(store.currentPaths().shared));
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); assertTrue(engine.processText("nihc").candidates.contains("你好")); }
    store.disableConfigs();
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); assertTrue(engine.processText("nihao").candidates.contains("你好")); }
  }
  @Test public void learningRecoveryMissingSourceAndLiveEnginePreserveSelection() throws Exception
  {
    String slot = store.view().selection.slot;
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    {
      try { store.previewLearningRecovery(); fail("Live engine accepted for snapshot"); }
      catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Close")); }
    }
    try { store.previewLearningRecovery(); fail("Missing legacy source accepted"); } catch (IOException expected) {}
    assertFalse(store.view().retainedLearningSnapshot); assertEquals(slot, store.view().selection.slot);
  }
  private File learnOldPinyin() throws Exception
  {
    store.useLegacyBase();
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(true); engine.processText("nihaoshijie"); assertFalse(engine.confirm().committed.isEmpty()); }
    File original = new File(root, "rime/user/pinyin_simp.userdb"); assertTrue(original.isDirectory());
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.emptyList()); return original;
  }
  @Test public void explicitLearningRecoveryPreservesOriginalSnapshotAndAppliedConfiguration() throws Exception
  {
    File original = learnOldPinyin(); String originalHash = LearningSnapshot.digest(original, LearningSnapshot.deadline());
    String patch = importPatch("default.custom.yaml", "patch: {menu/page_size: 3}");
    store.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(patch));
    store.previewLearningRecovery(); LearningRecovery.Preview preview = store.view().learning;
    assertTrue(preview.canApply); assertTrue(preview.active > 0); assertEquals(0, preview.unsupported);
    assertEquals(originalHash, preview.sourceHash);
    File pristine = new File(root, "rime/word-tables/learning-recovery/snapshot/pinyin_simp.userdb");
    assertEquals(originalHash, LearningSnapshot.digest(pristine, LearningSnapshot.deadline()));
    store.applyLearningRecovery();
    assertEquals(WordTableStore.BUNDLED, store.view().selection.base);
    assertEquals(Collections.singletonList(patch), store.view().selection.patches);
    assertEquals(1, store.view().selection.extras.size()); assertEquals("applied", store.view().learning.status);
    assertEquals(originalHash, LearningSnapshot.digest(original, LearningSnapshot.deadline()));
    assertEquals(originalHash, LearningSnapshot.digest(pristine, LearningSnapshot.deadline()));
    int count = store.view().entries.size(); store.discardLearningRecovery();
    assertFalse(store.view().retainedLearningSnapshot); assertEquals(count, store.view().entries.size());
    assertEquals(originalHash, LearningSnapshot.digest(original, LearningSnapshot.deadline()));
  }
  @Test public void changedOldLearningAndDamagedRecoveredOutputRefuseConfirmation() throws Exception
  {
    File original = learnOldPinyin(); store.previewLearningRecovery(); String slot = store.view().selection.slot;
    File output = new File(root, "rime/word-tables/learning-recovery/recovered.dict.yaml");
    Files.write(output.toPath(), "damaged".getBytes(StandardCharsets.UTF_8));
    try { store.applyLearningRecovery(); fail("Damaged recovered output accepted"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("integrity")); }
    assertEquals(slot, store.view().selection.slot); assertTrue(store.view().retainedLearningSnapshot);
    store.discardLearningRecovery(); store.previewLearningRecovery();
    File changed = new File(original, "synthetic-stale-marker"); Files.write(changed.toPath(), new byte[] {1});
    try { store.applyLearningRecovery(); fail("Stale snapshot accepted"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("changed")); }
    assertEquals(slot, store.view().selection.slot); assertTrue(changed.isFile());
  }
  private static void remove(File file) throws Exception
  { if (file.isDirectory()) for (File child : file.listFiles()) remove(child); if (!file.delete()) throw new IOException("Cannot remove synthetic custom fixture"); }
}
