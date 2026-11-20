package juloo.keyboard2.input;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WanxiangInstallTest
{
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final byte[] DICT = bytes("locked Base dictionary");
  private static final byte[] STOCK = bytes("original default");
  private static final byte[] OVERLAY = bytes("managed configuration");
  private static final byte[] MODEL = bytes("fixed grammar");

  @Test public void publishesOnlyAfterValidationAndReusesTheExactPreviousInstall() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    assertFalse(fixture.open().installed());
    List<Long> progress = new ArrayList<>();
    try (WanxiangInstall.Staged stage = fixture.stage(fixture.open(), progress))
    {
      assertFalse(fixture.target.exists());
      stage.activate(paths -> {
        assertTrue(RimeData.validDeployment(paths.shared));
        assertArrayEquals(MODEL, read(paths.shared, "test.gram"));
        assertArrayEquals(STOCK, read(paths.shared, "wanxiang_defaults.yaml"));
        assertArrayEquals(OVERLAY, read(paths.shared, "default.yaml"));
        assertEquals(0, read(paths.shared, "lua/data/chaifen.txt").length);
        assertFalse(new File(paths.shared, "upstream-installer.sh").exists());
        assertNotEquals(fixture.user, paths.user);
        assertEquals(0, paths.user.list().length);
      });
    }
    assertEquals(Long.valueOf(fixture.open().transferBytes), progress.get(progress.size() - 1));
    assertTrue(fixture.open().installed());
    // A new process can use the same manifest as a bundled build. No source
    // or asset callback is consulted when the installed bytes already match.
    assertNull(fixture.open().prepare(spec -> { throw new AssertionError("unexpected transfer"); },
        key -> { throw new AssertionError("unexpected APK copy"); }, bytes -> {}, () -> {}));
    fixture.assertRetained();
  }

  @Test public void corruptModelAndUnavailableNetworkLeaveThePreviousData() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    byte[] corrupt = MODEL.clone(); corrupt[0] ^= 1;
    assertThrows(WanxiangInstall.IntegrityFailure.class, () -> fixture.prepare(spec -> new ByteArrayInputStream(corrupt)));
    assertThrows(IOException.class, () -> fixture.prepare(spec -> { throw new IOException("connection unavailable"); }));
    assertFalse(fixture.target.exists());
    assertFalse(fixture.pending.exists());
    fixture.assertRetained();
  }

  @Test public void cancellationAndAnAbandonedStageCanBeRetried() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    WanxiangInstall install = fixture.open();
    try
    {
      assertThrows(InterruptedIOException.class, () -> install.prepare(fixture.source(install), fixture::asset,
          bytes -> Thread.currentThread().interrupt(), () -> {}));
    }
    finally { Thread.interrupted(); }
    assertFalse(fixture.pending.exists());
    // Simulate termination after a partial write, without running app cleanup.
    Files.createDirectory(fixture.pending.toPath());
    Files.write(new File(fixture.pending, "partial").toPath(), MODEL);
    try (WanxiangInstall.Staged stage = fixture.stage(fixture.open(), new ArrayList<>()))
    {
      assertFalse(new File(fixture.pending, "partial").exists());
      stage.activate(paths -> {});
    }
    assertFalse(fixture.pending.exists());
    assertTrue(fixture.open().installed());
    fixture.assertRetained();
  }

  @Test public void aVerifiedEarlierPackStaysUsableUntilTheRefreshActivates() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    WanxiangInstall install = fixture.open();
    assertFalse(install.hasEarlierBundle());
    byte[] earlierModel = bytes("earlier locked grammar");
    File earlier = fixture.writeEarlierPack(earlierModel);

    WordTableStore store = new WordTableStore(fixture.files, new WordTableStore.Packaged() {
      @Override public RimeData.Paths identify() { return new RimeData.Paths(fixture.target, fixture.user); }
      @Override public RimeData.Paths prepare() throws IOException
      { if (!install.installed()) throw new WanxiangInstall.SetupRequired(); return identify(); }
      @Override public ByteArrayInputStream open(String name) { return fixture.asset(name); }
    });
    assertTrue(install.hasEarlierBundle());
    assertTrue(store.currentPaths().retained);
    assertThrows(IOException.class, () -> fixture.prepare(spec -> { throw new IOException("Pinned asset is unavailable"); }));
    assertThrows(WanxiangInstall.IntegrityFailure.class, () -> fixture.prepare(spec -> new ByteArrayInputStream(new byte[]{MODEL[0]})));
    assertEquals(earlier, store.currentPaths().shared);
    try (WanxiangInstall.Staged stage = fixture.stage(install, new ArrayList<>()))
    {
      assertEquals(earlier, store.currentPaths().shared);
      stage.activate(paths -> assertArrayEquals(MODEL, read(paths.shared, "test.gram")));
    }
    assertFalse(store.currentPaths().retained);
    assertEquals(fixture.target, store.currentPaths().shared);
    assertEquals(fixture.user, store.currentPaths().user);
    assertArrayEquals(earlierModel, read(earlier, "test.gram"));
    fixture.assertRetained();
  }

  @Test public void rejectsAPathOutsideStagingEvenWhenTheArchiveItselfMatchesThePin() throws Exception
  {
    Fixture fixture = new Fixture(baseZip("../escaped.yaml"));
    assertThrows(WanxiangInstall.IntegrityFailure.class, () -> fixture.stage(fixture.open(), new ArrayList<>()));
    assertFalse(new File(fixture.root, "escaped.yaml").exists());
    assertFalse(fixture.pending.exists());
    fixture.assertRetained();
  }

  @Test public void truncatedArchiveAndFailedNativeValidationDoNotActivate() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    WanxiangInstall install = fixture.open();
    assertThrows(WanxiangInstall.IntegrityFailure.class, () -> install.prepare(spec ->
        new ByteArrayInputStream(spec == install.model ? MODEL : new byte[]{0}), fixture::asset, bytes -> {}, () -> {}));
    try (WanxiangInstall.Staged stage = fixture.stage(install, new ArrayList<>()))
    { assertThrows(IOException.class, () -> stage.activate(paths -> { throw new IOException("probe failed"); })); }
    assertFalse(fixture.target.exists());
    assertFalse(fixture.pending.exists());
    fixture.assertRetained();
  }

  @Test public void repairsADamagedExactDirectoryOnlyAfterTheNewPackValidates() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    Files.createDirectory(fixture.target.toPath());
    Files.write(new File(fixture.target, "old-bytes").toPath(), STOCK);
    try (WanxiangInstall.Staged stage = fixture.stage(fixture.open(), new ArrayList<>()))
    {
      stage.activate(paths -> assertArrayEquals(STOCK, read(fixture.target, "old-bytes")));
    }
    File[] saved = fixture.root.listFiles((parent, name) -> name.startsWith("wanxiang-retained-"));
    assertEquals(1, saved.length);
    assertArrayEquals(STOCK, read(saved[0], "old-bytes"));
    assertTrue(fixture.open().installed());
    fixture.assertRetained();
  }

  @Test public void aPreparedStageKeepsItsLeaseUntilDiscarded() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    try (WanxiangInstall.Staged stage = fixture.stage(fixture.open(), new ArrayList<>()))
    {
      assertThrows(WanxiangInstall.StagingBusy.class, () -> fixture.prepare(spec -> {
        throw new AssertionError("second writer reached the source");
      }));
      fixture.open().reclaimInterruptedModel();
      assertTrue(RimeData.validDeployment(new File(fixture.pending, "shared")));
      stage.activate(paths -> {});
    }
    assertNull(fixture.open().prepare(spec -> { throw new AssertionError("unexpected transfer"); },
        fixture::asset, count -> {}, () -> {}));
  }

  @Test public void offlineReclamationLeavesUnownedCompleteAndUnrecognizedStages() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    WanxiangInstall install = fixture.open();
    Path partial = fixture.modelPart;
    Files.createDirectories(partial.getParent());
    Files.write(partial, new byte[]{MODEL[0]});
    install.reclaimInterruptedModel();
    assertEquals(1, Files.size(partial)); // No ownership file from a locked writer.

    Files.createFile(fixture.root.toPath().resolve(".wanxiang-stage.lock"));
    Path otherModel = partial.resolveSibling("model-" + hash(STOCK) + ".part");
    Files.move(partial, otherModel);
    install.reclaimInterruptedModel();
    assertEquals(1, Files.size(otherModel));
    Files.move(otherModel, partial);

    Files.write(partial, MODEL);
    install.reclaimInterruptedModel();
    assertArrayEquals(MODEL, Files.readAllBytes(partial));

    Files.write(partial, new byte[]{MODEL[0]});
    Path receipt = Files.createDirectory(fixture.pending.toPath().resolve("shared")).resolve("manifest.json");
    Files.write(receipt, fixture.manifest);
    install.reclaimInterruptedModel();
    assertEquals(1, Files.size(partial));
    assertArrayEquals(fixture.manifest, Files.readAllBytes(receipt));
    fixture.assertRetained();
  }

  @Test public void offlineReclamationSkipsALiveProcessThenReclaimsItsKilledCopy() throws Exception
  {
    Fixture fixture = new Fixture(baseZip(null));
    File earlier = fixture.writeEarlierPack(bytes("earlier locked grammar"));
    Path files = fixture.files.toPath();
    Files.write(files.resolve("plan.json"), fixture.plan);
    Files.write(files.resolve("manifest.json"), fixture.manifest);
    File output = files.resolve("writer.log").toFile();
    Process writer = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
        "-cp", writerClassPath(), StagingWriter.class.getName(), files.toString())
        .redirectErrorStream(true).redirectOutput(output).start();
    Path partial = fixture.modelPart;
    try
    {
      Path marker = files.resolve("write-started");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!Files.exists(marker) && writer.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
      assertTrue("Child never reached the partial write: " + new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8),
          Files.exists(marker));
      assertTrue(writer.isAlive());
      assertEquals(1, Files.size(partial));
      fixture.open().reclaimInterruptedModel();
      assertEquals(1, Files.size(partial));
      assertThrows(WanxiangInstall.StagingBusy.class, () -> fixture.prepare(spec -> {
        throw new AssertionError("second process reached the source");
      }));
    }
    finally
    {
      writer.destroyForcibly();
      assertTrue("Staging child still running", writer.waitFor(5, TimeUnit.SECONDS));
    }
    assertNotEquals(0, writer.exitValue());
    assertEquals(1, Files.size(partial)); // Killed before Java cleanup could run.
    WanxiangInstall reopened = fixture.open();
    reopened.reclaimInterruptedModel();
    assertFalse(Files.exists(partial));
    assertFalse(reopened.installed());
    assertTrue(RimeData.validDeployment(earlier));
    fixture.assertRetained();
    try (WanxiangInstall.Staged retry = fixture.stage(reopened, new ArrayList<>()))
    { retry.activate(paths -> {}); }
    assertTrue(reopened.installed());
    assertTrue(RimeData.validDeployment(earlier));
    fixture.assertRetained();
  }

  private static String writerClassPath() throws Exception
  {
    // Gradle's java.class.path alone only names its worker bootstrap jar.
    List<String> paths = new ArrayList<>();
    for (Class<?> type : new Class<?>[]{WanxiangInstallTest.class, WanxiangInstall.class,
        JSONObject.class, android.content.Context.class, org.junit.Assert.class, org.hamcrest.Matcher.class})
      paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
    return String.join(File.pathSeparator, paths);
  }

  public static final class StagingWriter
  {
    public static void main(String[] args) throws Exception
    {
      File files = new File(args[0]);
      File root = new File(files, "rime");
      byte[] manifest = read(files, "manifest.json");
      RimeData.Paths paths = new RimeData.Paths(new File(root, "shared-" + hash(manifest)), new File(root, "user"));
      WanxiangInstall install = new WanxiangInstall(paths, read(files, "plan.json"), manifest);
      install.prepare(spec -> new ByteArrayInputStream(MODEL, 0, 1), name -> {
        throw new AssertionError("unexpected APK copy");
      }, count -> {
        try
        {
          Files.createFile(files.toPath().resolve("write-started"));
          Thread.sleep(30_000);
        }
        catch (IOException | InterruptedException failure) { throw new AssertionError(failure); }
      }, () -> {});
      throw new AssertionError("test must kill the writer during the copy");
    }
  }

  private final class Fixture
  {
    final File files = temporary.newFolder(), root = new File(files, "rime");
    final File user = new File(root, "user"), pending = new File(root, ".wanxiang-stage");
    final Path modelPart = pending.toPath().resolve("model-" + hash(MODEL) + ".part");
    final File target;
    final byte[] archive, manifest, plan;
    final File previous = new File(root, "existing-deployment");

    Fixture(byte[] archive) throws Exception
    {
      this.archive = archive;
      JSONObject hashes = new JSONObject(Map.of("wanxiang.dict.yaml", hash(DICT), "test.gram", hash(MODEL),
          "wanxiang_defaults.yaml", hash(STOCK), "lua/data/chaifen.txt", hash(new byte[0])));
      JSONObject overlays = new JSONObject().put("wanxiang_defaults.yaml", entry(STOCK));
      for (String name : new String[]{"default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml"})
      { hashes.put(name, hash(OVERLAY)); overlays.put(name, entry(OVERLAY)); }
      manifest = bytes(hashes.toString());
      JSONObject base = entry(archive).put("url", "https://api.github.com/repos/amzxyz/rime-wanxiang/releases/assets/1");
      JSONObject model = entry(MODEL).put("url", "https://api.github.com/repos/amzxyz/rime-wanxiang-data/releases/assets/2").put("filename", "test.gram");
      JSONArray dictionary = new JSONArray().put(entry(DICT).put("path", "wanxiang.dict.yaml"))
          .put(entry(new byte[0]).put("path", "lua/data/chaifen.txt"));
      plan = bytes(new JSONObject().put("format", 1).put("version", "test").put("base", base).put("model", model)
          .put("files", dictionary).put("overlays", overlays).toString());
      target = new File(root, "shared-" + hash(manifest));
      Files.createDirectories(user.toPath()); Files.write(new File(user, "learnt-data").toPath(), STOCK);
      Files.createDirectory(previous.toPath()); Files.write(new File(previous, "custom.yaml").toPath(), OVERLAY);
    }

    WanxiangInstall open() throws IOException { return new WanxiangInstall(new RimeData.Paths(target, user), plan, manifest); }
    ByteArrayInputStream asset(String name) { return new ByteArrayInputStream(name.equals("wanxiang_defaults.yaml") ? STOCK : OVERLAY); }
    WanxiangInstall.Source source(WanxiangInstall install) { return spec -> new ByteArrayInputStream(spec == install.model ? MODEL : archive); }
    WanxiangInstall.Staged stage(WanxiangInstall install, List<Long> progress) throws IOException
    { return install.prepare(source(install), this::asset, progress::add, () -> {}); }
    WanxiangInstall.Staged prepare(WanxiangInstall.Source source) throws IOException
    { return open().prepare(source, this::asset, bytes -> {}, () -> {}); }
    File writeEarlierPack(byte[] earlierModel) throws Exception
    {
      JSONObject hashes = new JSONObject(new String(manifest, StandardCharsets.UTF_8)).put("test.gram", hash(earlierModel));
      byte[] earlierManifest = bytes(hashes.toString());
      File earlier = new File(root, "shared-" + hash(earlierManifest));
      Files.createDirectory(earlier.toPath());
      for (java.util.Iterator<String> names = hashes.keys(); names.hasNext(); )
      {
        String name = names.next(); File file = new File(earlier, name);
        Files.createDirectories(file.getParentFile().toPath());
        byte[] data = name.equals("test.gram") ? earlierModel : name.equals("wanxiang.dict.yaml") ? DICT
            : name.equals("lua/data/chaifen.txt") ? new byte[0] : asset(name).readAllBytes();
        Files.write(file.toPath(), data);
      }
      RimeData.saveManifest(earlier, earlierManifest);
      return earlier;
    }
    void assertRetained() throws IOException
    { assertArrayEquals(STOCK, read(user, "learnt-data")); assertArrayEquals(OVERLAY, read(previous, "custom.yaml")); }
  }

  private static JSONObject entry(byte[] bytes) throws Exception
  { return new JSONObject().put("bytes", bytes.length).put("sha256", hash(bytes)); }
  private static String hash(byte[] bytes) { return RimeData.hex(RimeData.digest().digest(bytes)); }
  private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
  private static byte[] read(File directory, String name) throws IOException { return Files.readAllBytes(new File(directory, name).toPath()); }

  private static byte[] baseZip(String extra) throws IOException
  {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes))
    {
      member(zip, "wanxiang.dict.yaml", DICT); member(zip, "default.yaml", STOCK);
      member(zip, "lua/data/chaifen.txt", new byte[0]);
      member(zip, "upstream-installer.sh", OVERLAY);
      if (extra != null) member(zip, extra, OVERLAY);
    }
    return bytes.toByteArray();
  }

  private static void member(ZipOutputStream zip, String path, byte[] payload) throws IOException
  { zip.putNextEntry(new ZipEntry(path)); zip.write(payload); zip.closeEntry(); }
}
