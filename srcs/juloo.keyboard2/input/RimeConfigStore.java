package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Originals live outside Rime's automatic custom-file search path. Only
 * validated values enter a managed, independently compiled deployment. */
public final class RimeConfigStore
{
  public static final class Entry
  {
    public final String id, filename, sha256, canonicalHash;
    public final long bytes;
    Entry(JSONObject value) throws Exception
    {
      id = value.getString("id"); filename = value.getString("filename"); sha256 = value.getString("sha256");
      canonicalHash = value.getString("canonicalHash"); bytes = value.getLong("bytes");
      if (!RimeConfigPatch.validFilename(filename) || !id.matches("[a-f0-9]{64}") ||
          !sha256.matches("[a-f0-9]{64}") || !canonicalHash.matches("[a-f0-9]{64}") || bytes < 1 || bytes > RimeConfigPatch.MAX_BYTES)
        throw new IOException("Invalid configuration catalog");
    }
    JSONObject json() throws Exception
    { return new JSONObject().put("id", id).put("filename", filename).put("sha256", sha256).put("canonicalHash", canonicalHash).put("bytes", bytes); }
  }
  private final File root, catalog;
  RimeConfigStore(File words) throws IOException
  {
    root = new File(words, "config-patches").getCanonicalFile(); catalog = new File(root, "catalog.json");
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create configuration catalog");
  }
  List<Entry> entries() throws Exception
  {
    List<Entry> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
    if (!catalog.exists()) return result;
    if (catalog.length() > 128 * 1024) throw new IOException("Configuration catalog is too large");
    JSONArray values = new JSONObject(new String(Files.readAllBytes(catalog.toPath()), StandardCharsets.UTF_8)).getJSONArray("patches");
    if (values.length() > 32) throw new IOException("At most 32 custom files may be retained");
    for (int i = 0; i < values.length(); i++)
    { Entry entry = new Entry(values.getJSONObject(i)); if (!ids.add(entry.id)) throw new IOException("Duplicate configuration ID"); result.add(entry); }
    return result;
  }
  void importFiles(List<WordTableStore.Source> sources) throws Exception
  {
    if (sources.isEmpty() || sources.size() > 32) throw new IOException("Choose 1..32 custom YAML files");
    List<Entry> all = entries(); List<File> created = new ArrayList<>(); boolean committed = false;
    try
    {
      for (WordTableStore.Source source : sources)
      {
        String name = source.filename();
        if (!RimeConfigPatch.validFilename(name)) throw new IOException("Supported names: default.custom.yaml, wanxiang.custom.yaml, miancak_pinyin.custom.yaml");
        byte[] original; try (InputStream input = source.open()) { original = RimeConfigPatch.read(input); }
        byte[] validated = RimeConfigPatch.validate(name, original).getBytes(StandardCharsets.UTF_8);
        byte[] prefix = (name + "\0").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream identity = new ByteArrayOutputStream(); identity.write(prefix); identity.write(original);
        String id = digest(identity.toByteArray()); boolean exists = false;
        for (Entry entry : all) if (entry.id.equals(id)) exists = true;
        if (exists) continue;
        if (all.size() == 32) throw new IOException("At most 32 custom files may be retained");
        Entry entry = new Entry(new JSONObject().put("id", id).put("filename", name).put("sha256", digest(original))
            .put("canonicalHash", digest(validated)).put("bytes", original.length));
        File dir = new File(root, id);
        if (dir.exists())
        {
          // A prior interrupted catalog commit may leave an original. Reuse
          // only identical bytes; never erase a retained original to import.
          if (!Arrays.equals(original, readOriginal(entry))) throw new IOException("Retained custom file differs");
        }
        else
        {
          if (!dir.mkdir()) throw new IOException("Cannot save custom file"); created.add(dir);
          write(new File(dir, "source.custom.yaml"), original);
        }
        all.add(entry);
      }
      save(all); committed = true;
    }
    finally { if (!committed) for (File dir : created) { new File(dir, "source.custom.yaml").delete(); dir.delete(); } }
  }
  private byte[] readOriginal(Entry entry) throws Exception
  {
    File file = new File(new File(root, entry.id), "source.custom.yaml");
    if (!file.getCanonicalPath().equals(file.getAbsolutePath())) throw new IOException("Custom source must be a managed regular file");
    byte[] original; try (InputStream input = new FileInputStream(file)) { original = RimeConfigPatch.read(input); }
    if (original.length != entry.bytes || !digest(original).equals(entry.sha256)) throw new IOException("Custom original failed integrity check");
    ByteArrayOutputStream identity = new ByteArrayOutputStream(); identity.write((entry.filename + "\0").getBytes(StandardCharsets.UTF_8)); identity.write(original);
    if (!digest(identity.toByteArray()).equals(entry.id)) throw new IOException("Custom source identity failed integrity check");
    return original;
  }
  List<Entry> resolve(List<String> ids, boolean wanxiang) throws Exception
  {
    if (ids.size() > 3 || new HashSet<>(ids).size() != ids.size()) throw new IOException("Select at most one custom file per target");
    if (ids.isEmpty()) return Collections.emptyList();
    Map<String, Entry> byId = new HashMap<>(); for (Entry entry : entries()) byId.put(entry.id, entry);
    List<Entry> selected = new ArrayList<>(); Set<String> names = new HashSet<>();
    for (String id : ids)
    {
      Entry entry = byId.get(id);
      if (entry == null || !names.add(entry.filename)) throw new IOException("Missing or duplicate custom target");
      if (!wanxiang && entry.filename.equals("wanxiang.custom.yaml")) throw new IOException("Wanxiang custom settings require bundled Base; disable that patch before choosing another base");
      byte[] original = readOriginal(entry);
      String canonical = RimeConfigPatch.validate(entry.filename, original);
      if (!wanxiang && canonical.contains("switches/")) throw new IOException("Switch reset patches require bundled Base");
      if (!digest(canonical.getBytes(StandardCharsets.UTF_8)).equals(entry.canonicalHash))
        throw new IOException("Custom validation changed; retained original requires review");
      selected.add(entry);
    }
    selected.sort(Comparator.comparingInt(e -> e.filename.equals("default.custom.yaml") ? 0 : e.filename.equals("wanxiang.custom.yaml") ? 1 : 2));
    return selected;
  }
  void apply(List<Entry> selected, File shared) throws Exception
  {
    for (Entry entry : selected)
    {
      byte[] original = readOriginal(entry);
      // default's supported menu setting applies to the mobile schema too,
      // whose stock page-size override would otherwise hide the global value.
      List<String> targets = entry.filename.equals("default.custom.yaml")
          ? Arrays.asList("default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml")
          : Arrays.asList("miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml");
      for (String name : targets)
      {
        File target = new File(shared, name);
        String before = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        write(target, RimeConfigPatch.merge(entry.filename, original, before).getBytes(StandardCharsets.UTF_8));
      }
    }
  }
  void remove(String id) throws Exception
  {
    List<Entry> all = entries(); Entry found = null;
    for (Entry entry : all) if (entry.id.equals(id)) found = entry;
    if (found == null) throw new IOException("Custom file is missing");
    all.remove(found); save(all);
    File dir = new File(root, found.id), original = new File(dir, "source.custom.yaml");
    if (!original.delete() || !dir.delete()) throw new IOException("Custom catalog updated; retained source cleanup failed");
  }
  private void save(List<Entry> entries) throws Exception
  {
    JSONArray values = new JSONArray(); for (Entry entry : entries) values.put(entry.json());
    File temporary = new File(root, "catalog.json.tmp");
    try { write(temporary, (new JSONObject().put("format", 1).put("patches", values).toString() + "\n").getBytes(StandardCharsets.UTF_8));
      if (!temporary.renameTo(catalog)) throw new IOException("Cannot commit custom catalog"); }
    finally { temporary.delete(); }
  }
  private static void write(File file, byte[] bytes) throws IOException
  { try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); output.getFD().sync(); } }
  private static String digest(byte[] bytes) throws Exception
  {
    StringBuilder hex = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
    return hex.toString();
  }
}
