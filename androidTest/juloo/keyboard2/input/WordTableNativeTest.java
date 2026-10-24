package juloo.keyboard2.input;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Complete existing tables and actual librime, with no active IME service. */
@RunWith(AndroidJUnit4.class)
public class WordTableNativeTest
{
  private Context app, tests;
  private WordTableStore store;
  @Before public void setup() throws Exception
  {
    app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    tests = InstrumentationRegistry.getInstrumentation().getContext(); store = new WordTableStore(app); clean();
  }
  @After public void cleanup() throws Exception { if (store != null) clean(); }
  private void clean() throws Exception
  {
    WordTableStore.Selection selected = store.view().selection;
    if (!selected.base.equals(WordTableStore.BUNDLED) || !selected.extras.isEmpty()) store.apply(WordTableStore.BUNDLED, Collections.emptyList());
    for (WordTableStore.Entry entry : store.view().entries) store.remove(entry.id);
  }
  private WordTableStore.Source asset(String filename)
  {
    return new WordTableStore.Source() {
      @Override public String filename() { return filename; }
      @Override public InputStream open() throws Exception
      { return filename.equals("pinyin_simp.dict.yaml") ? app.getAssets().open("rime/" + filename) : tests.getAssets().open("word-tables/" + filename); }
    };
  }
  private WordTableStore.Source text(String name, String value)
  {
    return new WordTableStore.Source() {
      @Override public String filename() { return name + ".dict.yaml"; }
      @Override public InputStream open() { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    };
  }
  private String id(String name) throws Exception
  { for (WordTableStore.Entry entry : store.view().entries) if (entry.name.equals(name)) return entry.id; throw new AssertionError(name); }
  private List<String> candidates(String code) throws Exception
  {
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(false); return engine.processText(code).candidates; }
  }
  private void expectFailure(InputController.Maintenance action, String reason) throws Exception
  {
    try { action.run(); fail("Expected failure: " + reason); }
    catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(reason)); }
  }
  @Test public void completeExistingBasesAndSupplementChangeRealCandidatesAndCommits() throws Exception
  {
    store.importTables(Arrays.asList(asset("pinyin_simp.dict.yaml"), asset("8105.dict.yaml"), asset("miancak_addon.dict.yaml")));
    assertEquals(3, store.view().entries.size());
    for (WordTableStore.Entry entry : store.view().entries)
      if (entry.name.equals("pinyin_simp")) assertTrue(entry.bytes > 1000000);
    String a = id("pinyin_simp"), b = id("8105"), addon = id("miancak_addon");
    File learning = store.currentPaths().user;
    store.apply(a, Collections.emptyList()); assertFalse(candidates("biang").contains("𰻝"));
    store.apply(b, Collections.emptyList()); assertTrue(candidates("biang").contains("𰻝"));
    store.apply(b, Arrays.asList(addon));
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    {
      engine.setLearning(false); EngineResult result = engine.processText("ceshi");
      int index = result.candidates.indexOf("MIANCAK·附加词表"); assertTrue(result.candidates.toString(), index >= 0);
      assertEquals("MIANCAK·附加词表", engine.select(index).committed);
    }
    store.apply(b, Collections.emptyList()); assertFalse(candidates("ceshi").contains("MIANCAK·附加词表"));
    assertEquals(learning, store.currentPaths().user); store.remove(addon);
    store.apply(WordTableStore.BUNDLED, Collections.emptyList()); assertTrue(candidates("nihao").contains("你好"));
  }
  @Test public void invalidMultiImportDoesNotCommitAnyTablesOrLeaveStagedBodies() throws Exception
  {
    expectFailure(() -> store.importTables(Arrays.asList(asset("8105.dict.yaml"), asset("others.dict.yaml"))), "Pinyin");
    assertTrue(store.view().entries.isEmpty());
    assertEquals(0, new File(app.getFilesDir(), "rime/word-tables/tables").listFiles().length);
    assertTrue(candidates("nihao").contains("你好"));
  }
  @Test public void dependenciesAreRequiredCyclesRejectedAndActiveDependenciesProtected() throws Exception
  {
    store.importTables(Arrays.asList(text("root", "---\nname: root\nimport_tables: [cn_dicts/leaf]\n...\n")));
    String before = store.view().selection.slot;
    expectFailure(() -> store.apply(id("root"), Collections.emptyList()), "Missing dependency");
    assertEquals(before, store.view().selection.slot);
    store.importTables(Arrays.asList(text("leaf", "---\nname: leaf\n...\n测\tce\t1\n试\tshi\t1\n依赖词条\tce shi\t100\n")));
    store.apply(id("root"), Collections.emptyList()); assertTrue(candidates("ceshi").contains("依赖词条"));
    expectFailure(() -> store.remove(id("leaf")), "in use");
    store.importTables(Arrays.asList(text("cycle_a", "---\nname: cycle_a\nimport_tables: [cycle_b]\n...\n"), text("cycle_b", "---\nname: cycle_b\nimport_tables: [cycle_a]\n...\n")));
    before = store.view().selection.slot;
    expectFailure(() -> store.apply(id("cycle_a"), Collections.emptyList()), "cycle");
    assertEquals(before, store.view().selection.slot); assertTrue(candidates("ceshi").contains("依赖词条"));
  }
  @Test public void corruptDeploymentRecoversPreviousThenBundledWithoutDeletingLearning() throws Exception
  {
    store.importTables(Arrays.asList(asset("8105.dict.yaml"), asset("miancak_addon.dict.yaml")));
    store.apply(id("8105"), Collections.emptyList()); String previous = store.view().selection.slot;
    store.apply(id("8105"), Arrays.asList(id("miancak_addon")));
    File user = store.currentPaths().user;
    try (FileOutputStream out = new FileOutputStream(new File(store.currentPaths().shared, "pinyin_simp.dict.yaml"))) { out.write(0); }
    assertEquals(previous, store.view().selection.slot); assertFalse(store.view().recovery.isEmpty());
    assertTrue(candidates("biang").contains("𰻝"));
    try (FileOutputStream out = new FileOutputStream(new File(store.currentPaths().shared, "pinyin_simp.dict.yaml"))) { out.write(0); }
    assertEquals(WordTableStore.BUNDLED, store.view().selection.base); assertTrue(candidates("nihao").contains("你好")); assertEquals(user, store.currentPaths().user);
  }
  @Test public void aFailedPointerCommitAfterNativeCompilationKeepsTheWorkingChoice() throws Exception
  {
    store.importTables(Arrays.asList(asset("8105.dict.yaml"))); store.apply(id("8105"), Collections.emptyList());
    String before = store.view().selection.slot;
    File obstruction = new File(app.getFilesDir(), "rime/word-tables/selection.json.tmp"); assertTrue(obstruction.mkdir());
    try
    {
      try { store.apply(WordTableStore.BUNDLED, Collections.emptyList()); fail(); } catch (IOException expected) {}
      assertEquals(before, store.view().selection.slot); assertTrue(candidates("biang").contains("𰻝"));
    }
    finally { assertTrue(obstruction.delete()); }
  }
  @Test public void actualLearnedEntryRemainsInTheSameDatabaseAcrossSwitchAndRestore() throws Exception
  {
    store.importTables(Arrays.asList(asset("8105.dict.yaml")));
    String learned;
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    {
      engine.setLearning(true); EngineResult result = engine.processText("ni'hao'ni'hao");
      assertFalse(result.candidates.isEmpty()); learned = result.candidates.get(0); assertFalse(learned.isEmpty());
      assertEquals(learned, engine.confirm().committed);
    }
    File user = store.currentPaths().user;
    assertTrue("Expected a real durable learning entry", containsLearned(user, learned.getBytes(StandardCharsets.UTF_8), false));
    store.apply(id("8105"), Collections.emptyList());
    assertTrue(containsLearned(user, learned.getBytes(StandardCharsets.UTF_8), false));
    try (RimeEngine engine = new RimeEngine(store.currentPaths()))
    { engine.setLearning(true); assertTrue(engine.processText("ni'hao'ni'hao").candidates.contains(learned)); }
    store.apply(WordTableStore.BUNDLED, Collections.emptyList());
    assertEquals(user, store.currentPaths().user); assertTrue(containsLearned(user, learned.getBytes(StandardCharsets.UTF_8), false));
  }
  private boolean containsLearned(File file, byte[] needle, boolean inDatabase) throws Exception
  {
    boolean database = inDatabase || file.getName().contains(".userdb");
    if (file.isDirectory())
    { for (File child : file.listFiles()) if (containsLearned(child, needle, database)) return true; return false; }
    if (!database || file.length() > 8 * 1024 * 1024) return false;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream input = new FileInputStream(file)) { byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n); }
    byte[] data = bytes.toByteArray();
    outer: for (int i = 0; i <= data.length - needle.length; i++)
    { for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer; return true; }
    return false;
  }
}
