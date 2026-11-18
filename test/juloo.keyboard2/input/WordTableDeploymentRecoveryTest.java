package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real JSON, hashes and filesystem; no fake native-deployment success. */
public class WordTableDeploymentRecoveryTest
{
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private File files, state;
  private BrokenPackage assets;
  private WordTableStore store;
  @Before public void setup() throws Exception
  {
    files = temporary.newFolder(); assets = new BrokenPackage(files); store = new WordTableStore(files, assets);
    state = new File(files, "rime/word-tables/selection.json");
  }
  private static class BrokenPackage implements WordTableStore.Packaged
  {
    final RimeData.Paths identity; int prepares, identities; boolean unreadable;
    BrokenPackage(File root) { identity = new RimeData.Paths(new File(root, "rime/shared-" + String.join("", Collections.nCopies(64, "a"))), new File(root, "rime/user")); }
    public RimeData.Paths identify() throws IOException
    { identities++; if (unreadable) throw new IOException("synthetic unreadable manifest"); return identity; }
    public RimeData.Paths prepare() throws IOException { prepares++; throw new IOException("synthetic asset preparation failure"); }
    public InputStream open(String name) throws IOException { throw new IOException("synthetic APK read failure"); }
  }
  private String hash(byte[] bytes) throws Exception
  { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b & 255)); return result.toString(); }
  private File deployment(String slot, boolean packaged) throws Exception
  {
    JSONObject manifest = new JSONObject();
    String[] names = {"default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml", "pinyin_simp.dict.yaml"};
    for (String name : names) manifest.put(name, hash(name.getBytes(StandardCharsets.UTF_8)));
    byte[] bytes = (manifest.toString() + "\n").getBytes(StandardCharsets.UTF_8);
    File shared = new File(files, packaged ? "rime/shared-" + hash(bytes) : "rime/word-tables/versions/" + slot); assertTrue(shared.mkdirs());
    for (String name : names) Files.write(new File(shared, name).toPath(), name.getBytes(StandardCharsets.UTF_8));
    Files.write(new File(shared, "manifest.json").toPath(), bytes); assertTrue(RimeData.validDeployment(shared)); return shared;
  }
  private WordTableStore.Selection selection(String slot)
  { return new WordTableStore.Selection(WordTableStore.BUNDLED, Collections.emptyList(), slot); }
  private void saved(WordTableStore.Selection current, WordTableStore.Selection previous) throws Exception
  {
    JSONObject data = new JSONObject().put("format", 1).put("current", current.json()).put("previous", previous.json()).put("packaged", "earlier-package").put("recovery", "");
    Files.write(state.toPath(), (data.toString() + "\n").getBytes(StandardCharsets.UTF_8));
  }
  private JSONObject saved() throws Exception { return new JSONObject(new String(Files.readAllBytes(state.toPath()), StandardCharsets.UTF_8)); }
  private File original(String relative) throws Exception
  { File file = new File(files, relative); assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory()); Files.write(file.toPath(), "original bytes".getBytes(StandardCharsets.UTF_8)); return file; }

  @Test public void firstPreparationFailureIsTypedAndLeavesOriginals() throws Exception
  {
    File learning = original("rime/user/pinyin_simp.userdb/data"), yaml = original("rime/word-tables/config-patches/kept/source.custom.yaml");
    try { store.currentPaths(); fail("unavailable new data passed"); }
    catch (OfflineDataException expected) { assertTrue(expected.getCause().getMessage().contains("synthetic")); }
    assertEquals(1, assets.prepares); assertFalse(state.exists());
    assertEquals("original bytes", new String(Files.readAllBytes(learning.toPath()), StandardCharsets.UTF_8));
    assertEquals("original bytes", new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8));
  }
  @Test public void activeDeploymentPreservesBaseExtrasPatchesAndPrevious() throws Exception
  {
    String slot = UUID.randomUUID().toString(); File active = deployment(slot, false);
    WordTableStore.Selection choices = new WordTableStore.Selection("custom-base", Arrays.asList("extra-one", "extra-two"), slot, Arrays.asList("patch-one", "patch-two"));
    saved(choices, selection("")); JSONObject before = saved();
    RimeData.Paths paths = store.currentPaths(); assertEquals(active, paths.shared); assertTrue(paths.retained);
    assertEquals(before.getJSONObject("current").toString(), saved().getJSONObject("current").toString());
    assertEquals(before.getJSONObject("previous").toString(), saved().getJSONObject("previous").toString());
    assertTrue(saved().getBoolean("assetPreparationDeferred")); assertFalse(store.view().recovery.isEmpty());
    assertEquals(active, new WordTableStore(files, assets).currentPaths().shared); assertEquals(1, assets.prepares);
  }
  @Test public void failedMetadataWriteStillAllowsVerifiedReadOnlyFallback() throws Exception
  {
    String slot = UUID.randomUUID().toString(); File active = deployment(slot, false); saved(selection(slot), selection(""));
    byte[] original = Files.readAllBytes(state.toPath()); assertTrue(new File(state.getPath() + ".tmp").mkdir());
    RimeData.Paths paths = store.currentPaths(); assertTrue(paths.retained); assertEquals(active, paths.shared);
    assertArrayEquals(original, Files.readAllBytes(state.toPath())); assertFalse(store.view().recovery.isEmpty());
  }
  @Test public void previousCompiledSelectionIsReachableWithoutChangingIntendedChoices() throws Exception
  {
    String slot = UUID.randomUUID().toString(); File previous = deployment(slot, false); saved(selection(""), selection(slot));
    RimeData.Paths paths = store.currentPaths(); assertEquals(previous, paths.shared); assertTrue(paths.retained);
    assertEquals("", store.view().selection.slot); assertEquals(slot, saved().getString("recoverySlot"));
    assertEquals(previous, new WordTableStore(files, assets).currentPaths().shared); assertEquals(1, assets.prepares);
  }
  @Test public void intactEarlierPackageIsUsedAndReopenDoesNotRepeatFailedCopy() throws Exception
  {
    File previous = deployment("", true); RimeData.Paths paths = store.currentPaths();
    assertEquals(previous, paths.shared); assertTrue(paths.retained); assertEquals(previous.getName(), saved().getString("recoveryShared"));
    assertEquals(previous, new WordTableStore(files, assets).currentPaths().shared); assertEquals(1, assets.prepares);
  }
  @Test public void unreadableNewManifestDoesNotHideVerifiedOldDeployment() throws Exception
  {
    File previous = deployment("", true); assets.unreadable = true;
    assertEquals(previous, store.currentPaths().shared); assertEquals(0, assets.prepares);
  }
  @Test public void corruptAssetsOrMismatchedContentAddressAreNeverFallbacks() throws Exception
  {
    File previous = deployment("", true); Files.write(new File(previous, "default.yaml").toPath(), new byte[] {1});
    try { store.currentPaths(); fail("corrupt fallback accepted"); } catch (OfflineDataException expected) {}
    Files.write(new File(previous, "default.yaml").toPath(), "default.yaml".getBytes(StandardCharsets.UTF_8));
    File moved = new File(files, "rime/shared-" + String.join("", Collections.nCopies(64, "b")));
    assertTrue(previous.renameTo(moved)); assertFalse(RimeData.validDeployment(moved));
    try { store.currentPaths(); fail("wrong content address accepted"); } catch (OfflineDataException expected) {}
  }
  @Test public void explicitRetryFailurePreservesSelectionAndRetainedData() throws Exception
  {
    String slot = UUID.randomUUID().toString(); File active = deployment(slot, false); saved(selection(slot), selection(""));
    assertEquals(active, store.currentPaths().shared); byte[] before = Files.readAllBytes(state.toPath());
    try { store.retryDeployment(); fail("failed retry accepted"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("synthetic")); }
    assertEquals(2, assets.prepares); assertArrayEquals(before, Files.readAllBytes(state.toPath()));
    assertEquals(active, store.currentPaths().shared); assertEquals(2, assets.prepares);
  }
  @Test public void nativeRecoveryDoesNotMaterializeNewAssets() throws Exception
  {
    String slot = UUID.randomUUID().toString(); File previous = deployment(slot, false); saved(selection(""), selection(slot));
    store.recover(); assertEquals(0, assets.prepares); assertEquals(previous, store.currentPaths().shared);
    assertTrue(store.currentPaths().retained); assertEquals(0, assets.prepares);
  }
  @Test public void damagedCurrentPointerRecoversPreviousWithoutPreparingFirst() throws Exception
  {
    String slot = UUID.randomUUID().toString(); deployment(slot, false); saved(selection(UUID.randomUUID().toString()), selection(slot));
    assertEquals(slot, store.view().selection.slot); assertEquals(0, assets.prepares);
    assertTrue(store.currentPaths().retained); assertEquals(1, assets.prepares);
  }
}
