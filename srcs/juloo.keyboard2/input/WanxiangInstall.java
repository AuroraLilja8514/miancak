package juloo.keyboard2.input;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Setup stages public resources and a disposable native probe outside every active data path. */
final class WanxiangInstall
{
  static final String PLAN = "rime/delivery.json";
  static final long BUILD_RESERVE = 256L * 1024 * 1024;
  // One private Rime root per app process. Keep other threads from opening a
  // second descriptor to the same file while the process holds its OS lock.
  private static final Semaphore LOCAL_STAGE = new Semaphore(1);

  static final class SetupRequired extends IOException
  { SetupRequired() { super(OfflineDataException.SETUP_MESSAGE); } }

  static final class InsufficientSpace extends IOException {}
  static final class StagingBusy extends IOException {}
  static final class IntegrityFailure extends IOException
  { IntegrityFailure(String message) { super(message); } }

  interface Source { InputStream open(Artifact artifact) throws IOException; }
  interface Assets { InputStream open(String name) throws IOException; }
  interface Validator { void verify(RimeData.Paths paths) throws Exception; }

  static class Entry
  {
    final long bytes;
    final String sha256;
    Entry(JSONObject spec) throws JSONException
    {
      bytes = spec.getLong("bytes"); sha256 = spec.getString("sha256");
      if (bytes < 0 || bytes > 512L * 1024 * 1024 || !sha256.matches("[a-f0-9]{64}"))
        throw new JSONException("Invalid resource size or hash");
    }
  }

  static final class Artifact extends Entry
  {
    final String url;
    Artifact(JSONObject spec, String repository) throws JSONException
    {
      super(spec);
      url = spec.getString("url");
      String prefix = "https://api.github.com/repos/amzxyz/" + repository + "/releases/assets/";
      if (bytes == 0 || !url.startsWith(prefix) || !url.substring(prefix.length()).matches("[1-9][0-9]{0,19}"))
        throw new JSONException("Invalid locked asset ID");
    }
  }

  static WanxiangInstall forApp(Context context) throws IOException
  {
    RimeData.Paths paths = RimeData.identity(context);
    try (InputStream plan = context.getAssets().open(PLAN);
         InputStream manifest = context.getAssets().open("rime/manifest.json"))
    { return new WanxiangInstall(paths, RimeData.read(plan), RimeData.read(manifest)); }
  }

  final Artifact base, model;
  final String version;
  final long transferBytes, requiredBytes;
  private final String modelName;
  private final Map<String, Entry> archiveFiles = new LinkedHashMap<>(), overlays = new LinkedHashMap<>();
  private final byte[] manifest;
  private final File target, pending;
  private final Path lockPath, modelPart;

  WanxiangInstall(RimeData.Paths paths, byte[] plan, byte[] manifest) throws IOException
  {
    this.manifest = manifest.clone();
    File root = paths.shared.getParentFile();
    if (Files.isSymbolicLink(root.toPath())) throw new IOException("Invalid Rime data root");
    target = new File(root.getCanonicalFile(), paths.shared.getName());
    pending = new File(target.getParentFile(), ".wanxiang-stage");
    lockPath = pending.toPath().resolveSibling(".wanxiang-stage.lock");
    try
    {
      JSONObject spec = parse(plan);
      if (spec.getInt("format") != 1) throw new JSONException("Unknown resource format");
      version = spec.getString("version");
      base = new Artifact(spec.getJSONObject("base"), "rime-wanxiang");
      model = new Artifact(spec.getJSONObject("model"), "rime-wanxiang-data");
      modelName = spec.getJSONObject("model").getString("filename");
      if (!modelName.matches("[a-z0-9_-]+\\.gram")) throw new JSONException("Invalid grammar filename");
      modelPart = pending.toPath().resolve("model-" + model.sha256 + ".part");
      JSONObject files = parse(manifest);
      Set<String> accounted = new HashSet<>();
      long bytes = locked(files, accounted, modelName, model);
      JSONArray entries = spec.getJSONArray("files");
      if (entries.length() > 256 || entries.length() == 0) throw new JSONException("Invalid Base inventory size");
      for (int i = 0; i < entries.length(); i++)
      {
        JSONObject item = entries.getJSONObject(i);
        String name = item.getString("path"); Entry entry = new Entry(item);
        if (!isDownloadableData(name)) throw new JSONException("Executable/configuration asset requested from archive");
        bytes += locked(files, accounted, name, entry);
        archiveFiles.put(name, entry);
      }
      JSONObject assets = spec.getJSONObject("overlays");
      java.util.Iterator<String> names = assets.keys();
      while (names.hasNext())
      {
        String name = names.next(); Entry entry = new Entry(assets.getJSONObject(name));
        bytes += locked(files, accounted, name, entry);
        overlays.put(name, entry);
      }
      if (accounted.size() != files.length()) throw new JSONException("Incomplete Rime manifest");
      transferBytes = base.bytes + model.bytes;
      // The model streams straight into staging. Only the small Base archive
      // coexists with its extracted files. Reserve additional compilation space.
      requiredBytes = bytes + base.bytes + BUILD_RESERVE;
    }
    catch (JSONException invalid) { throw new IOException("Invalid APK resource plan", invalid); }
  }

  private static JSONObject parse(byte[] bytes) throws JSONException
  { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }

  private static long locked(JSONObject files, Set<String> seen, String name, Entry entry) throws JSONException
  {
    if (!RimeData.validAssetName(name) || !seen.add(name) || !entry.sha256.equals(files.getString(name)))
      throw new JSONException("Resource differs from installed manifest");
    return entry.bytes;
  }

  private static boolean isDownloadableData(String name)
  {
    return name.endsWith(".dict.yaml") || (name.startsWith("lua/data/") && (name.endsWith(".txt") || name.endsWith(".bin")))
        || (name.startsWith("opencc/wanxiang/") && (name.endsWith(".txt") || name.endsWith(".ocd2")));
  }

  boolean installed()
  { return !Files.isSymbolicLink(target.toPath()) && new File(target, "manifest.json").isFile() && RimeData.validDeployment(target); }

  boolean hasEarlierBundle() { return RimeData.previousBundle(target) != null; }

  /** Run on the setup worker. Reclaim only a lone incomplete scratch file for
   * this pin. Leave even a complete model copy and any prepared stage in place. */
  void reclaimInterruptedModel() throws IOException
  {
    Path stage = pending.toPath();
    // A stage written by an older installer may lack an ownership lock.
    if (!Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) return;
    try (StageLease lease = tryOwnStage(false))
    {
      if (lease == null || !onlyChild(stage, modelPart)) return;
      BasicFileAttributes attrs = Files.readAttributes(modelPart, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attrs.isRegularFile() && attrs.size() < model.bytes) Files.delete(modelPart);
    }
  }

  private static boolean onlyChild(Path directory, Path child) throws IOException
  {
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory))
    {
      Iterator<Path> children = entries.iterator();
      return children.hasNext() && children.next().equals(child) && !children.hasNext();
    }
  }

  /** The lock lives beside staging and is never replaced or erased. Close may
   * run on a different worker after validation, activation or cancellation. */
  private StageLease tryOwnStage(boolean create) throws IOException
  {
    if (!LOCAL_STAGE.tryAcquire()) return null;
    FileChannel channel = null;
    FileLock lock = null;
    try
    {
      if (!Files.notExists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid resource setup lock");
      channel = create ? FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS)
          : FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      lock = channel.tryLock();
      return lock == null ? null : new StageLease(lock);
    }
    finally { if (lock == null) releaseStageChannel(channel); }
  }

  private static void releaseStageChannel(FileChannel channel) throws IOException
  {
    try { if (channel != null) channel.close(); }
    finally { LOCAL_STAGE.release(); }
  }

  private static final class StageLease implements AutoCloseable
  {
    private final FileLock lock;
    StageLease(FileLock lock) { this.lock = lock; }
    @Override public void close() throws IOException { releaseStageChannel(lock.channel()); }
  }

  Staged prepare(Source source, Assets assets, LongConsumer transferred, Runnable extracting) throws IOException
  {
    File root = target.getParentFile();
    Files.createDirectories(root.toPath());
    StageLease lease = tryOwnStage(true);
    if (lease == null) throw new StagingBusy();
    boolean created = false, complete = false;
    try
    {
      if (installed()) return null;
      erase(pending.toPath());
      if (root.getUsableSpace() < requiredBytes) throw new InsufficientSpace();
      Files.createDirectory(pending.toPath());
      created = true;
      File shared = new File(pending, "shared");
      // Check the larger (and less often available) model first, without
      // spending bandwidth on the Base archive if its pinned endpoint is gone.
      copy(source.open(model), modelPart.toFile(), model, transferred);
      Files.createDirectory(shared.toPath());
      Files.move(modelPart, RimeData.managedFile(shared, modelName).toPath(), StandardCopyOption.ATOMIC_MOVE);
      File archive = new File(pending, "base.zip");
      copy(source.open(base), archive, base, bytes -> transferred.accept(model.bytes + bytes));
      extracting.run();
      extract(archive, shared);
      Files.delete(archive.toPath());
      for (Map.Entry<String, Entry> file : overlays.entrySet())
        copy(assets.open(file.getKey()), RimeData.managedFile(shared, file.getKey()), file.getValue(), bytes -> {});
      RimeData.saveManifest(shared, manifest);
      File user = new File(pending, "probe-user"); Files.createDirectory(user.toPath());
      Staged staged = new Staged(new RimeData.Paths(shared, user), lease);
      complete = true;
      return staged;
    }
    finally
    {
      if (!complete)
        try (StageLease owned = lease) { if (created) erase(pending.toPath()); }
    }
  }

  private void extract(File archive, File shared) throws IOException
  {
    Set<String> names = new HashSet<>(), extracted = new HashSet<>();
    try (ZipFile zip = new ZipFile(archive))
    {
      if (zip.size() > 512) throw new IntegrityFailure("Too many Base archive entries");
      java.util.Enumeration<? extends ZipEntry> all = zip.entries();
      while (all.hasMoreElements())
      {
        ZipEntry member = all.nextElement(); String name = member.getName();
        if (member.isDirectory()) name = name.substring(0, name.length() - 1);
        if (!RimeData.validAssetName(name) || !names.add(name)) throw new IntegrityFailure("Invalid Base archive member");
        Entry expected = archiveFiles.get(name);
        if (expected == null) continue;
        if (member.isDirectory() || member.getSize() != expected.bytes) throw new IntegrityFailure("Unexpected Base member size");
        copy(zip.getInputStream(member), RimeData.managedFile(shared, name), expected, bytes -> {});
        extracted.add(name);
      }
    }
    if (extracted.size() != archiveFiles.size()) throw new IntegrityFailure("Incomplete Base archive");
  }

  private static void copy(InputStream source, File target, Entry expected, LongConsumer progress) throws IOException
  {
    try (InputStream input = source)
    {
      interrupted();
      Files.createDirectories(target.getParentFile().toPath());
      MessageDigest digest = RimeData.digest();
      byte[] buffer = new byte[64 * 1024]; long total = 0;
      try (FileOutputStream output = new FileOutputStream(target))
      {
        int count;
        while ((count = input.read(buffer)) != -1)
        {
          interrupted();
          total += count;
          if (total > expected.bytes) throw new IntegrityFailure("Attempted oversized resource");
          digest.update(buffer, 0, count); output.write(buffer, 0, count);
          progress.accept(total);
        }
        if (total != expected.bytes || !RimeData.hex(digest.digest()).equals(expected.sha256))
          throw new IntegrityFailure("Resource size or SHA-256 mismatch");
        interrupted(); output.getFD().sync();
      }
    }
  }

  static void interrupted() throws InterruptedIOException
  { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Resource installation interrupted"); }

  static void verifyNative(RimeData.Paths paths) throws IOException
  {
    try (RimeEngine engine = new RimeEngine(paths, true))
    {
      engine.setLearning(false);
      EngineResult probe = engine.processText("nihao");
      if (!probe.composing || !probe.candidates.contains("你好"))
        throw new IOException("Verified schema produced no expected setup candidate");
    }
  }

  final class Staged implements AutoCloseable
  {
    private final RimeData.Paths paths;
    private final StageLease lease;
    private boolean closed;
    private Staged(RimeData.Paths paths, StageLease lease) { this.paths = paths; this.lease = lease; }

    /** Call on the native worker with the live engine closed. A failure never
     * changes the active vocabulary pointer or the user's database. */
    synchronized void activate(Validator validator) throws Exception
    {
      if (closed) throw new IOException("Resource setup has already closed");
      validator.verify(paths);
      if (!RimeData.validDeployment(paths.shared)) throw new IntegrityFailure("Staged resources changed during validation");
      if (installed()) return;
      Path destination = target.toPath(), displaced = null;
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
      {
        // Preserve even damaged prior files. A healthy exact install above is never moved.
        displaced = destination.resolveSibling("wanxiang-retained-" + java.util.UUID.randomUUID());
        Files.move(destination, displaced, StandardCopyOption.ATOMIC_MOVE);
      }
      try { Files.move(paths.shared.toPath(), destination, StandardCopyOption.ATOMIC_MOVE); }
      catch (IOException failure)
      {
        if (displaced != null)
          try { Files.move(displaced, destination, StandardCopyOption.ATOMIC_MOVE); }
          catch (IOException retention) { failure.addSuppressed(retention); }
        throw failure;
      }
    }

    @Override public synchronized void close() throws IOException
    {
      if (closed) return;
      closed = true;
      try (StageLease owned = lease) { erase(pending.toPath()); }
    }
  }

  private static void erase(Path path) throws IOException
  {
    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return;
    Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
      { Files.delete(file); return FileVisitResult.CONTINUE; }
      @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
      { if (failure != null) throw failure; Files.delete(directory); return FileVisitResult.CONTINUE; }
    });
  }
}
