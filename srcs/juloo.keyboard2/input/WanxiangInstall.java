package juloo.keyboard2.input;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The staging directory contains only verified public resources and a disposable native probe. */
final class WanxiangInstall
{
  static final String PLAN = "rime/delivery.json";
  static final long BUILD_RESERVE = 256L * 1024 * 1024;

  static final class SetupRequired extends IOException
  { SetupRequired() { super(OfflineDataException.SETUP_MESSAGE); } }

  static final class InsufficientSpace extends IOException {}
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

  WanxiangInstall(RimeData.Paths paths, byte[] plan, byte[] manifest) throws IOException
  {
    this.manifest = manifest.clone();
    target = new File(paths.shared.getParentFile().getCanonicalFile(), paths.shared.getName());
    pending = new File(target.getParentFile(), ".wanxiang-stage");
    try
    {
      JSONObject spec = parse(plan);
      if (spec.getInt("format") != 1) throw new JSONException("Unknown resource format");
      version = spec.getString("version");
      base = new Artifact(spec.getJSONObject("base"), "rime-wanxiang");
      model = new Artifact(spec.getJSONObject("model"), "rime-wanxiang-data");
      modelName = spec.getJSONObject("model").getString("filename");
      if (!modelName.matches("[a-z0-9_-]+\\.gram")) throw new JSONException("Invalid grammar filename");
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

  /** One calling worker prepares at a time. The directory is reserved solely for
   * this operation, so a later explicit retry can remove interrupted transfers. */
  Staged prepare(Source source, Assets assets, LongConsumer transferred, Runnable extracting) throws IOException
  {
    erase(pending.toPath());
    if (installed()) return null;
    File root = target.getParentFile();
    Files.createDirectories(root.toPath());
    if (root.getUsableSpace() < requiredBytes) throw new InsufficientSpace();
    Files.createDirectory(pending.toPath());
    boolean complete = false;
    try
    {
      File shared = new File(pending, "shared");
      // Check the larger (and less often available) model first, without
      // spending bandwidth on the Base archive if its pinned endpoint is gone.
      copy(source.open(model), RimeData.managedFile(shared, modelName), model, transferred);
      File archive = new File(pending, "base.zip");
      copy(source.open(base), archive, base, bytes -> transferred.accept(model.bytes + bytes));
      extracting.run();
      extract(archive, shared);
      Files.delete(archive.toPath());
      for (Map.Entry<String, Entry> file : overlays.entrySet())
        copy(assets.open(file.getKey()), RimeData.managedFile(shared, file.getKey()), file.getValue(), bytes -> {});
      RimeData.saveManifest(shared, manifest);
      File user = new File(pending, "probe-user"); Files.createDirectory(user.toPath());
      complete = true;
      return new Staged(new RimeData.Paths(shared, user));
    }
    finally { if (!complete) erase(pending.toPath()); }
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
    private Staged(RimeData.Paths paths) { this.paths = paths; }

    /** Call on the native worker with the live engine closed. A failure never
     * changes the active vocabulary pointer or the user's database. */
    void activate(Validator validator) throws Exception
    {
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

    @Override public void close() throws IOException { erase(pending.toPath()); }
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
