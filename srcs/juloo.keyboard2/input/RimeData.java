package juloo.keyboard2.input;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned deployment, called during worker initialization, never for ordinary keys. */
public final class RimeData
{
  public static final class Paths
  {
    public final File shared;
    public final File user;
    public final File build;
    Paths(File shared, File user) { this.shared = shared; this.user = user; this.build = new File(shared, "build"); }
  }

  private RimeData() {}

  public static synchronized Paths deploy(Context context) throws IOException
  { return new WordTableStore(context).currentPaths(); }

  public static synchronized Paths bundled(Context context) throws IOException
  {
    if (android.os.Build.VERSION.SDK_INT >= 24 && context.isDeviceProtectedStorage())
      throw new IOException("Rime user data requires credential-protected storage");
    byte[] manifest;
    try (InputStream in = context.getAssets().open("rime/manifest.json"))
    {
      manifest = read(in);
    }
    File root = new File(context.getFilesDir(), "rime");
    File shared = new File(root, "shared-" + hash(manifest));
    File user = new File(root, "user");
    if ((!shared.isDirectory() && !shared.mkdirs()) || (!user.isDirectory() && !user.mkdirs()))
      throw new IOException("Cannot create Rime data directories");
    try
    {
      JSONObject files = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
      Iterator<String> names = files.keys();
      while (names.hasNext())
      {
        String name = names.next();
        File target = managedFile(shared, name);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create Rime asset directory");
        String expected = files.getString(name);
        if (target.isFile())
          try (InputStream in = new java.io.FileInputStream(target))
          {
            if (hash(in).equals(expected))
              continue;
          }
        File temporary = new File(parent, target.getName() + ".tmp");
        boolean installed = false;
        try
        {
          MessageDigest digest = digest();
          try (InputStream in = context.getAssets().open("rime/" + name);
               FileOutputStream out = new FileOutputStream(temporary))
          {
            byte[] buffer = new byte[16384]; int count;
            while ((count = in.read(buffer)) != -1)
            { digest.update(buffer, 0, count); out.write(buffer, 0, count); }
            out.getFD().sync();
          }
          if (!hex(digest.digest()).equals(expected))
            throw new IOException("Packaged Rime asset failed integrity check");
          if (!temporary.renameTo(target))
            throw new IOException("Cannot atomically deploy Rime asset");
          installed = true;
        }
        finally { if (!installed) temporary.delete(); }
      }
      saveManifest(shared, manifest);
    }
    catch (JSONException ex)
    {
      throw new IOException("Invalid Rime asset manifest", ex);
    }
    return new Paths(shared, user);
  }

  static File managedFile(File root, String name) throws IOException
  {
    if (!validAssetName(name)) throw new IOException("Invalid packaged Rime asset name");
    File target = new File(root, name);
    if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException("Rime asset escapes its deployment");
    return target;
  }

  static boolean validAssetName(String name)
  {
    if (name.isEmpty() || name.length() > 256 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
    for (String part : name.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
    return true;
  }

  static JSONObject manifest(File shared) throws IOException
  {
    // Older packaged deployments copied the four assets but not manifest.json.
    // Verify their exact historical manifest against the directory hash before
    // permitting recovery; an arbitrary directory is never trusted.
    if (!new File(shared, "manifest.json").exists())
      return new JSONObject(legacyAssetDigests(shared));
    try (InputStream input = new java.io.FileInputStream(new File(shared, "manifest.json")))
    {
      byte[] bytes = read(input);
      if (bytes.length > 1024 * 1024) throw new IOException("Rime manifest is too large");
      return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    catch (JSONException invalid) { throw new IOException("Invalid Rime asset manifest", invalid); }
  }

  static void saveManifest(File shared, byte[] manifest) throws IOException
  {
    File target = new File(shared, "manifest.json"), temporary = new File(shared, "manifest.json.tmp");
    if (target.isFile())
      try (InputStream input = new java.io.FileInputStream(target))
      { if (hash(input).equals(hash(manifest))) return; }
    try
    {
      try (FileOutputStream output = new FileOutputStream(temporary))
      { output.write(manifest); output.getFD().sync(); }
      if (!temporary.renameTo(target)) throw new IOException("Cannot save Rime asset manifest");
    }
    finally { temporary.delete(); }
  }

  static java.util.Map<String, String> legacyAssetDigests(File shared) throws IOException
  {
    if (!shared.getName().matches("shared-[a-f0-9]{64}")) throw new IOException("Missing Rime asset manifest");
    java.util.Map<String, String> files = new java.util.LinkedHashMap<>();
    StringBuilder canonical = new StringBuilder("{");
    for (String name : new String[] {"default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml", "pinyin_simp.dict.yaml"})
    {
      String digest;
      try (InputStream input = new java.io.FileInputStream(managedFile(shared, name))) { digest = hash(input); }
      if (!files.isEmpty()) canonical.append(", ");
      files.put(name, digest); canonical.append('"').append(name).append("\": \"").append(digest).append('"');
    }
    canonical.append("}\n");
    if (!shared.getName().equals("shared-" + hash(canonical.toString().getBytes(StandardCharsets.UTF_8))))
      throw new IOException("Earlier Rime assets failed integrity check");
    return files;
  }

  static boolean validDeployment(File shared)
  {
    try
    {
      JSONObject files = manifest(shared);
      for (String name : new String[] {"default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml"})
        if (!files.has(name)) return false;
      if (!files.has("pinyin_simp.dict.yaml") && !files.has("wanxiang.dict.yaml")) return false;
      Iterator<String> names = files.keys();
      while (names.hasNext())
      {
        String name = names.next(); File file = managedFile(shared, name);
        if (!file.isFile()) return false;
        try (InputStream input = new java.io.FileInputStream(file))
        { if (!hash(input).equals(files.getString(name))) return false; }
      }
      return true;
    }
    catch (Exception invalid) { return false; }
  }

  /** Clone immutable packaged resources; a large grammar need not be duplicated
   * for every selected supplement. Never link learning or original import files. */
  static void copyBundled(File source, File target) throws Exception
  {
    JSONObject files = manifest(source); Iterator<String> names = files.keys();
    while (names.hasNext())
    {
      String name = names.next(); File from = managedFile(source, name), to = managedFile(target, name);
      File parent = to.getParentFile();
      if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create selection assets");
      if (name.endsWith(".gram"))
        try { Files.createLink(to.toPath(), from.toPath()); continue; }
        catch (IOException | UnsupportedOperationException noLink) { /* Copy on filesystems without hard links. */ }
      Files.copy(from.toPath(), to.toPath());
    }
  }

  /** A failed new deployment may use an intact previous packaged bundle. The
   * old directories and their stable user/learning directory are never removed. */
  static File previousBundle(Context context, File current)
  {
    File[] candidates = current.getParentFile().listFiles();
    if (candidates == null) return null;
    java.util.Arrays.sort(candidates, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
    for (File candidate : candidates)
      if (!candidate.equals(current) && candidate.getName().matches("shared-[a-f0-9]{64}") && validDeployment(candidate))
        return candidate;
    return null;
  }

  private static byte[] read(InputStream in) throws IOException
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[16384];
    int n;
    while ((n = in.read(buffer)) != -1)
    {
      if (out.size() + n > 1024 * 1024) throw new IOException("Rime manifest is too large");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static String hash(byte[] bytes)
  {
    return hex(digest().digest(bytes));
  }
  private static String hash(InputStream in) throws IOException
  {
    MessageDigest digest = digest(); byte[] buffer = new byte[16384]; int count;
    while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
    return hex(digest.digest());
  }
  private static MessageDigest digest()
  {
    try
    { return MessageDigest.getInstance("SHA-256"); }
    catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
  }
  private static String hex(byte[] bytes)
  {
    StringBuilder result = new StringBuilder(); String hex = "0123456789abcdef";
    for (byte b : bytes) result.append(hex.charAt((b & 255) >>> 4)).append(hex.charAt(b & 15));
    return result.toString();
  }
}
