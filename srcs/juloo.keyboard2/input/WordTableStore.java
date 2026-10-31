package juloo.keyboard2.input;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Credential-protected catalog and atomic, independently compiled selections.
 * Production callers use WordTableRuntime's serialized worker. */
public final class WordTableStore
{
  public static final String BUNDLED = "bundled";
  public static final String LEGACY = "miancak_legacy_0_3";
  public static boolean hasFormalBundledPack(Context context)
  {
    try (InputStream input = context.getAssets().open("word-pack/current.json")) { return input.read() >= 0; }
    catch (IOException absent) { return false; }
  }
  private static final long MAX_TOTAL = 256L * 1024 * 1024;
  public interface Source { String filename(); InputStream open() throws Exception; }
  public static final class Entry
  {
    public final String id, name, version, filename, bodyHash;
    public final long bytes, rows;
    public final List<String> imports;
    Entry(JSONObject data) throws JSONException
    {
      id = data.getString("id"); name = data.getString("name"); version = data.getString("version");
      filename = data.getString("filename"); bytes = data.getLong("bytes"); rows = data.getLong("rows");
      bodyHash = data.getString("bodyHash"); imports = strings(data.getJSONArray("imports"));
      if (!id.matches("[a-f0-9]{64}") || !bodyHash.matches("[a-f0-9]{64}") || !WordTable.validName(name))
        throw new JSONException("Invalid word table catalog");
    }
    JSONObject json() throws JSONException
    {
      return new JSONObject().put("id", id).put("name", name).put("version", version).put("filename", filename)
        .put("bytes", bytes).put("rows", rows).put("bodyHash", bodyHash).put("imports", new JSONArray(imports));
    }
  }
  public static final class Selection
  {
    public final String base, slot;
    public final List<String> extras;
    Selection(String base, List<String> extras, String slot)
    { this.base = base; this.extras = Collections.unmodifiableList(new ArrayList<>(extras)); this.slot = slot; }
    JSONObject json() throws JSONException { return new JSONObject().put("base", base).put("extras", new JSONArray(extras)).put("slot", slot); }
  }
  public static final class View
  {
    public final List<Entry> entries;
    public final Selection selection;
    public final String recovery;
    View(List<Entry> entries, Selection selection, String recovery)
    { this.entries = Collections.unmodifiableList(entries); this.selection = selection; this.recovery = recovery; }
  }
  private final Context context;
  private final File root, tables, versions, catalog, state;
  public WordTableStore(Context context) throws IOException
  {
    this.context = context.getApplicationContext();
    if (android.os.Build.VERSION.SDK_INT >= 24 && this.context.isDeviceProtectedStorage())
      throw new IOException("Unlock before managing vocabulary / 请解锁后管理词表");
    root = new File(this.context.getFilesDir(), "rime/word-tables").getCanonicalFile();
    tables = new File(root, "tables"); versions = new File(root, "versions");
    catalog = new File(root, "catalog.json"); state = new File(root, "selection.json");
    mkdir(tables); mkdir(versions);
  }
  public View view() throws Exception
  {
    JSONObject saved = state();
    return new View(entries(), selection(saved.getJSONObject("current")), saved.optString("recovery"));
  }
  private List<Entry> entries() throws Exception
  {
    List<Entry> out = new ArrayList<>(); Set<String> names = new HashSet<>(), ids = new HashSet<>();
    if (!catalog.isFile()) return out;
    JSONArray data = json(catalog).getJSONArray("tables");
    if (data.length() > 64) throw new IOException("Catalog exceeds 64 tables / 词表目录超出上限");
    for (int i = 0; i < data.length(); i++)
    {
      Entry entry = new Entry(data.getJSONObject(i));
      if (!names.add(entry.name) || !ids.add(entry.id)) throw new IOException("Duplicate catalog name / 词表名称重复");
      out.add(entry);
    }
    return out;
  }
  public void importTables(List<Source> sources) throws Exception
  {
    if (sources.isEmpty() || sources.size() > 64) throw new IOException("Choose 1–64 word tables / 请选择 1–64 张词表");
    List<Entry> all = entries(); long total = 0;
    for (Entry entry : all) total += entry.bytes;
    File incoming = new File(root, "incoming-" + UUID.randomUUID()); mkdir(incoming);
    List<File> installed = new ArrayList<>(); boolean committed = false;
    try
    {
      for (Source source : sources)
      {
        String filename = source.filename();
        if (filename == null || filename.length() > 256) throw new IOException("Invalid filename / 文件名无效或过长");
        if (!filename.endsWith(".dict.yaml")) throw new IOException("Select .dict.yaml / 请选择 .dict.yaml 文件: " + filename);
        File stage = new File(incoming, UUID.randomUUID().toString()); mkdir(stage);
        File original = new File(stage, "source.dict.yaml");
        try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(original))
        { copy(input, output, WordTable.MAX_BYTES); output.getFD().sync(); }
        File body = new File(stage, "entries.tsv"); WordTable parsed;
        try (InputStream input = new FileInputStream(original); FileOutputStream output = new FileOutputStream(body);
             Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)))
        { parsed = WordTable.parse(input, writer); writer.flush(); output.getFD().sync(); }
        String id = hash(original); boolean duplicate = false;
        for (Entry entry : all) if (entry.name.equals(parsed.name))
        {
          if (!entry.id.equals(id)) throw new IOException("Name already imported; switch away and remove it first / 同名词表已存在，请先切换离开并移除: " + parsed.name);
          duplicate = true;
        }
        if (duplicate) continue;
        total += original.length();
        if (total > MAX_TOTAL || all.size() >= 64) throw new IOException("Catalog exceeds 64 tables / 256 MiB / 词表目录超出上限");
        Entry entry = new Entry(new JSONObject().put("id", id).put("name", parsed.name).put("version", parsed.version)
            .put("filename", filename).put("bytes", original.length()).put("rows", parsed.rows)
            .put("imports", new JSONArray(parsed.imports)).put("bodyHash", hash(body)));
        atomic(new File(stage, "metadata.json"), entry.json());
        File target = new File(tables, id);
        if (target.exists()) erase(target);
        if (!stage.renameTo(target)) throw new IOException("Cannot install imported table / 无法保存导入词表");
        installed.add(target);
        all.add(entry);
      }
      saveCatalog(all);
      committed = true;
    }
    finally
    {
      if (!committed) for (File file : installed) erase(file);
      erase(incoming);
    }
  }
  public void remove(String id) throws Exception
  {
    List<Entry> all = entries(); JSONObject saved = state(); Selection current = selection(saved.getJSONObject("current"));
    for (Entry entry : resolve(all, current.base, current.extras))
      if (entry.id.equals(id)) throw new IOException("Table is in use; switch away first / 词表正在使用，请先切换离开");
    Selection previous = selection(saved.getJSONObject("previous"));
    boolean previousUses = false;
    try { for (Entry entry : resolve(all, previous.base, previous.extras)) if (entry.id.equals(id)) previousUses = true; }
    catch (IOException invalid) { previousUses = true; }
    if (previousUses)
    { saved.put("previous", bundledSelection().json()); atomic(state, saved); }
    List<Entry> remaining = new ArrayList<>(); for (Entry entry : all) if (!entry.id.equals(id)) remaining.add(entry);
    if (remaining.size() == all.size()) throw new IOException("Table no longer exists / 词表已不存在");
    saveCatalog(remaining); erase(new File(tables, id));
  }
  public void apply(String base, List<String> extras) throws Exception
  {
    List<Entry> ordered = resolve(entries(), base, extras);
    RimeData.Paths bundled = RimeData.bundled(context);
    String slot = UUID.randomUUID().toString(); File shared = new File(versions, slot); mkdir(shared);
    boolean committed = false;
    try
    {
      for (String name : Arrays.asList("default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml"))
        try (InputStream in = new FileInputStream(new File(bundled.shared, name)); FileOutputStream out = new FileOutputStream(new File(shared, name)))
        { copy(in, out, 1024 * 1024); out.getFD().sync(); }
      File dictionary = new File(shared, "pinyin_simp.dict.yaml");
      long rows = 0;
      try (FileOutputStream output = new FileOutputStream(dictionary);
           Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8)))
      {
        writer.write("# Generated from validated word tables; originals stay in the catalog.\n---\nname: pinyin_simp\nversion: '" + slot + "'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n");
        if (base.equals(BUNDLED))
          try (InputStream input = new FileInputStream(new File(bundled.shared, "pinyin_simp.dict.yaml")))
          { rows += WordTable.parse(input, writer).rows; }
        for (Entry entry : ordered)
        {
          File body = new File(new File(tables, entry.id), "entries.tsv");
          if (!entry.bodyHash.equals(hash(body))) throw new IOException("Imported table is damaged / 导入词表已损坏: " + entry.name);
          try (Reader input = new InputStreamReader(new FileInputStream(body), StandardCharsets.UTF_8))
          { char[] buffer = new char[16384]; int n; while ((n = input.read(buffer)) != -1) writer.write(buffer, 0, n); }
          rows += entry.rows;
        }
        writer.flush(); output.getFD().sync();
      }
      if (rows == 0) throw new IOException("Selection has no entries / 所选词表没有词条");
      JSONObject manifest = new JSONObject();
      for (String name : Arrays.asList("default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml", "pinyin_simp.dict.yaml"))
        manifest.put(name, hash(new File(shared, name)));
      atomic(new File(shared, "manifest.json"), manifest);
      RimeData.Paths paths = new RimeData.Paths(shared, bundled.user);
      try (RimeEngine engine = new RimeEngine(paths, true))
      {
        engine.setLearning(false);
        String probe = probe(dictionary);
        EngineResult result = engine.processText(probe);
        if (!result.composing || result.candidates.isEmpty()) throw new IOException("Compiled table produced no candidates / 编译后未产生候选，请检查拼音数据");
      }
      JSONObject before = state();
      Selection next = new Selection(base, new ArrayList<>(new LinkedHashSet<>(extras)), slot);
      atomic(state, new JSONObject().put("format", 1).put("current", next.json()).put("previous", before.getJSONObject("current"))
          .put("packaged", bundled.shared.getName()).put("recovery", ""));
      committed = true;
      try { cleanup(); } catch (Exception ignored) { /* A committed working selection must not be reported as failed because GC failed. */ }
    }
    finally { if (!committed) erase(shared); }
  }
  /** Explicit offline fallback. It follows the same import, validation,
   * compilation and atomic pointer path as user vocabulary. */
  public void useLegacyBase() throws Exception
  {
    Selection before = view().selection;
    importTables(Collections.singletonList(new Source() {
      @Override public String filename() { return LEGACY + ".dict.yaml"; }
      @Override public InputStream open() throws Exception
      { return context.getAssets().open("word-pack/" + filename()); }
    }));
    for (Entry entry : entries()) if (entry.name.equals(LEGACY))
    {
      List<String> extras = new ArrayList<>(before.extras); extras.remove(entry.id);
      apply(entry.id, extras); return;
    }
    throw new IOException("Legacy vocabulary was not imported / 旧版词表未能导入");
  }
  public RimeData.Paths currentPaths() throws IOException
  {
    try
    {
      RimeData.Paths packaged = RimeData.bundled(context); JSONObject saved = state();
      Selection active = selection(saved.getJSONObject("current"));
      if (active.slot.isEmpty()) return packaged;
      if (!saved.optString("packaged").equals(packaged.shared.getName()))
      {
        try { apply(active.base, active.extras); active = selection(state().getJSONObject("current")); }
        catch (Exception failure)
        {
          saved.put("recovery", "Update rebuild failed; retained working vocabulary / 更新后重编译失败，已保留可用词表");
          atomic(state, saved);
        }
      }
      return new RimeData.Paths(new File(versions, active.slot), packaged.user);
    }
    catch (Exception invalid) { if (invalid instanceof IOException) throw (IOException)invalid; throw new IOException(invalid); }
  }
  /** Used only after a failed native open, before returning an engine to input. */
  public void recover() throws Exception
  {
    JSONObject saved = state(); Selection fallback;
    try { fallback = selection(saved.getJSONObject("previous")); } catch (Exception invalid) { fallback = bundledSelection(); }
    if (!valid(fallback)) fallback = bundledSelection();
    atomic(state, new JSONObject().put("format", 1).put("current", fallback.json()).put("previous", bundledSelection().json())
      .put("packaged", RimeData.bundled(context).shared.getName()).put("recovery", "Deployment recovered / 已恢复上一可用词表或内置词表"));
  }
  private JSONObject state() throws Exception
  {
    JSONObject saved;
    if (!state.isFile()) return initial();
    try { saved = json(state); if (saved.getInt("format") != 1) throw new JSONException("Unknown format"); }
    catch (Exception invalid)
    {
      saved = initial().put("recovery", "Invalid selection recovered / 词表选择损坏，已恢复内置词表"); atomic(state, saved); return saved;
    }
    Selection active;
    try { active = selection(saved.getJSONObject("current")); }
    catch (Exception invalid) { active = null; }
    if (active == null || !valid(active))
    {
      Selection previous;
      try { previous = selection(saved.getJSONObject("previous")); } catch (Exception invalid) { previous = bundledSelection(); }
      if (!valid(previous)) previous = bundledSelection();
      saved = initial().put("current", previous.json()).put("packaged", RimeData.bundled(context).shared.getName())
        .put("recovery", "Damaged deployment recovered / 部署损坏，已恢复上一可用词表或内置词表");
      atomic(state, saved);
    }
    return saved;
  }
  private boolean valid(Selection selection)
  {
    if (selection.slot.isEmpty()) return selection.base.equals(BUNDLED) && selection.extras.isEmpty();
    if (!selection.slot.matches("[a-f0-9-]{36}")) return false;
    try
    {
      File dir = new File(versions, selection.slot); JSONObject manifest = json(new File(dir, "manifest.json"));
      for (String name : Arrays.asList("default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml", "pinyin_simp.dict.yaml"))
        if (!manifest.getString(name).equals(hash(new File(dir, name)))) return false;
      return true;
    }
    catch (Exception invalid) { return false; }
  }
  private JSONObject initial() throws JSONException
  { return new JSONObject().put("format", 1).put("current", bundledSelection().json()).put("previous", bundledSelection().json()).put("recovery", ""); }
  private static Selection bundledSelection() { return new Selection(BUNDLED, Collections.emptyList(), ""); }
  private static Selection selection(JSONObject value) throws JSONException
  { return new Selection(value.getString("base"), strings(value.getJSONArray("extras")), value.getString("slot")); }
  private static List<String> strings(JSONArray array) throws JSONException
  { List<String> result = new ArrayList<>(); for (int i = 0; i < array.length(); i++) result.add(array.getString(i)); return result; }
  private List<Entry> resolve(List<Entry> entries, String base, List<String> extras) throws IOException
  {
    Map<String, Entry> byId = new HashMap<>(), byName = new HashMap<>();
    for (Entry entry : entries) { byId.put(entry.id, entry); byName.put(entry.name, entry); }
    List<Entry> result = new ArrayList<>(); Set<String> done = new HashSet<>(), visiting = new HashSet<>();
    List<String> roots = new ArrayList<>(extras); if (!base.equals(BUNDLED)) roots.add(0, base);
    if (roots.size() > 64) throw new IOException("Too many selected tables / 选择的词表过多");
    for (String root : roots)
    {
      Entry entry = byId.get(root); if (entry == null) throw new IOException("Selected table is missing / 所选词表不存在");
      visit(entry, byName, done, visiting, result);
    }
    return result;
  }
  private void visit(Entry entry, Map<String, Entry> names, Set<String> done, Set<String> visiting, List<Entry> out) throws IOException
  {
    if (done.contains(entry.id)) return;
    if (!visiting.add(entry.id)) throw new IOException("Dependency cycle / 词表依赖循环: " + entry.name);
    for (String reference : entry.imports)
    {
      Entry dependency = names.get(WordTable.referenceName(reference));
      if (dependency == null) throw new IOException("Missing dependency / 缺少依赖: " + reference);
      visit(dependency, names, done, visiting, out);
    }
    visiting.remove(entry.id); done.add(entry.id); out.add(entry);
  }
  private void saveCatalog(List<Entry> entries) throws Exception
  { JSONArray list = new JSONArray(); for (Entry entry : entries) list.put(entry.json()); atomic(catalog, new JSONObject().put("format", 1).put("tables", list)); }
  private void cleanup() throws Exception
  {
    JSONObject saved = state(); Set<String> keep = new HashSet<>();
    keep.add(selection(saved.getJSONObject("current")).slot); keep.add(selection(saved.getJSONObject("previous")).slot);
    File[] dirs = versions.listFiles(); if (dirs != null) for (File dir : dirs)
      if (dir.getName().matches("[a-f0-9-]{36}") && !keep.contains(dir.getName())) erase(dir);
  }
  private static String probe(File dictionary) throws IOException
  {
    try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(dictionary), StandardCharsets.UTF_8)))
    {
      String line; boolean data = false;
      while ((line = input.readLine()) != null)
      {
        if (line.equals("...")) { data = true; continue; }
        if (data && !line.isEmpty() && !line.startsWith("#")) return line.split("\t")[1].split(" ")[0];
      }
    }
    throw new IOException("No dictionary probe / 词表没有可验证的输入码");
  }
  private static void mkdir(File directory) throws IOException
  { if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create vocabulary storage / 无法创建词表目录"); }
  private static JSONObject json(File file) throws Exception
  {
    if (file.length() > 2 * 1024 * 1024) throw new IOException("Metadata too large / 元数据过大");
    ByteArrayOutputStream out = new ByteArrayOutputStream(); try (InputStream in = new FileInputStream(file)) { copy(in, out, 2 * 1024 * 1024); }
    return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
  }
  private static void atomic(File target, JSONObject value) throws IOException
  {
    File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temporary))
    { out.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    if (!temporary.renameTo(target)) throw new IOException("Cannot commit vocabulary settings / 无法保存词表设置");
  }
  private static void copy(InputStream input, OutputStream output, long maximum) throws IOException
  {
    byte[] buffer = new byte[16384]; int n; long total = 0;
    while ((n = input.read(buffer)) != -1)
    { total += n; if (total > maximum) throw new IOException("File exceeds size limit / 文件超过大小上限"); output.write(buffer, 0, n); }
  }
  private static String hash(File file) throws Exception
  {
    MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; int n;
    try (InputStream input = new FileInputStream(file)) { while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
    StringBuilder result = new StringBuilder(); String hex = "0123456789abcdef";
    for (byte b : digest.digest()) result.append(hex.charAt((b & 255) >>> 4)).append(hex.charAt(b & 15));
    return result.toString();
  }
  private void erase(File file) throws IOException
  {
    if (!file.getAbsolutePath().startsWith(root.getPath() + File.separator)) throw new IOException("Invalid managed path");
    if (file.isDirectory() && file.getAbsoluteFile().equals(file.getCanonicalFile()))
    { File[] children = file.listFiles(); if (children != null) for (File child : children) erase(child); }
    if (file.exists() && !file.delete()) throw new IOException("Cannot remove managed vocabulary file / 无法移除词表文件");
  }
}
