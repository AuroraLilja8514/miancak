package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

/** Optional active-phrase recovery, never a database rename or automatic migration.
 * All callers run on WordTableRuntime's serialized maintenance worker. */
public final class LearningRecovery
{
  public static final class Preview
  {
    public final String status, sourceHash, dictionary, entryId;
    public final long active, deleted, unsupported, records;
    public final boolean canApply;
    Preview(JSONObject value) throws Exception
    {
      status = value.getString("status"); sourceHash = value.getString("sourceHash");
      dictionary = value.getString("dictionary"); entryId = value.optString("entryId");
      active = value.optLong("active"); deleted = value.optLong("deleted"); unsupported = value.optLong("unsupported"); records = value.optLong("records");
      if (!sourceHash.matches("[a-f0-9]{64}") || !dictionary.equals("miancak_recovered_pinyin_" + sourceHash.substring(0, 16)) ||
          active < 0 || deleted < 0 || unsupported < 0 || records < 0 || records > 200000 || active + deleted + unsupported != records)
        throw new IOException("Invalid recovery preview; retain or explicitly discard its snapshot");
      canApply = (status.equals("ready") || status.equals("imported")) && active > 0 && unsupported == 0;
    }
  }
  private final File pending, metadata, source;
  LearningRecovery(File root, File user) throws IOException
  {
    pending = new File(root, "learning-recovery"); metadata = new File(pending, "preview.json");
    source = new File(user, "pinyin_simp.userdb");
  }
  boolean exists() { return pending.exists(); }
  Preview preview() throws Exception { return exists() ? new Preview(read()) : null; }
  private JSONObject read() throws Exception
  {
    if (!pending.getCanonicalFile().equals(pending.getAbsoluteFile()) || !metadata.getCanonicalFile().equals(metadata.getAbsoluteFile()) ||
        !metadata.isFile() || metadata.length() > 32768) throw new IOException("Recovery snapshot is retained but its preview needs repair or explicit discard");
    JSONObject value = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
    if (value.getInt("format") != 1) throw new IOException("Unknown recovery preview format"); return value;
  }
  private void save(JSONObject value) throws Exception
  {
    File temp = new File(pending, "preview.json.tmp");
    try (FileOutputStream out = new FileOutputStream(temp))
    { out.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    if (!temp.renameTo(metadata)) throw new IOException("Cannot commit learning recovery preview");
  }
  void prepare(RimeData.Paths bundled, WordTableStore.Selection selection) throws Exception
  {
    RimeEngine.requireClosedForRecovery();
    if (exists()) throw new IOException("Review or explicitly discard the retained recovery snapshot first");
    if (pending.getParentFile().getUsableSpace() < 3 * LearningSnapshot.MAX_BYTES)
      throw new IOException("Recovery needs at least 192 MiB of available private storage");
    if (!pending.mkdir()) throw new IOException("Cannot create recovery snapshot");
    long deadline = LearningSnapshot.deadline(); boolean verified = false; JSONObject value = null;
    try
    {
      File pristine = new File(pending, "snapshot/pinyin_simp.userdb");
      String hash = LearningSnapshot.copy(source, pristine, deadline); verified = true;
      String name = "miancak_recovered_pinyin_" + hash.substring(0, 16);
      value = new JSONObject().put("format", 1).put("sourceHash", hash).put("dictionary", name)
          .put("selection", selection.json()).put("status", "snapshot").put("active", 0).put("deleted", 0).put("unsupported", 0).put("records", 0);
      save(value);  // From here, a failure retains the verified pristine copy.
      File working = new File(pending, "working/pinyin_simp.userdb"); LearningSnapshot.copy(pristine, working, deadline);
      LearningSnapshot.check(deadline); RimeEngine.requireClosedForRecovery();
      long remaining = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
      long[] counts = RimeEngine.recoverLegacyCopy(bundled.shared.getPath(), working.getParent(), output().getPath(), name, remaining);
      if (counts == null || counts.length != 4) throw new IOException("No recovery accounting was returned");
      value.put("active", counts[0]).put("deleted", counts[1]).put("unsupported", counts[2]).put("records", counts[3]);
      if (!hash.equals(LearningSnapshot.digest(source, deadline)) || !hash.equals(LearningSnapshot.digest(pristine, deadline)))
        throw new IOException("Original or retained learning changed; recovery was not activated");
      new Preview(value); // Validate every returned count before presenting it.
      if (counts[2] != 0 || counts[0] == 0) { value.put("status", "blocked"); save(value); return; }
      WordTable parsed; try (InputStream in = new FileInputStream(output())) { parsed = WordTable.parse(in, Writer.nullWriter()); }
      if (!parsed.name.equals(name) || parsed.rows != counts[0] || !parsed.imports.isEmpty()) throw new IOException("Recovered rows failed strict vocabulary validation");
      LearningSnapshot.check(deadline);
      try (RandomAccessFile file = new RandomAccessFile(output(), "rw")) { file.getFD().sync(); }
      value.put("outputHash", LearningSnapshot.hash(output())).put("status", "ready"); save(value);
    }
    catch (Exception | LinkageError failure)
    {
      if (!verified) erase(pending);
      else if (value != null) { value.put("status", "blocked"); save(value); }
      throw failure;
    }
  }
  void apply(WordTableStore store, WordTableStore.Selection selection) throws Exception
  {
    RimeEngine.requireClosedForRecovery(); JSONObject value = read(); Preview preview = new Preview(value);
    if (!preview.canApply) throw new IOException("This retained recovery cannot be activated");
    long deadline = LearningSnapshot.deadline();
    if (!preview.sourceHash.equals(LearningSnapshot.digest(source, deadline)) ||
        !preview.sourceHash.equals(LearningSnapshot.digest(new File(pending, "snapshot/pinyin_simp.userdb"), deadline)))
      throw new IOException("Old learning changed after preview; discard the copies and preview again");
    JSONObject expected = value.getJSONObject("selection");
    if (!expected.getString("base").equals(selection.base) || !expected.getString("slot").equals(selection.slot) ||
        !same(expected.getJSONArray("extras"), selection.extras) || !same(expected.getJSONArray("patches"), selection.patches))
      throw new IOException("Word-table selection changed after preview; review a fresh snapshot");
    if (!value.getString("outputHash").equals(LearningSnapshot.hash(output()))) throw new IOException("Recovered dictionary failed integrity verification");
    store.importTables(Collections.singletonList(new WordTableStore.Source() {
      @Override public String filename() { return preview.dictionary + ".dict.yaml"; }
      @Override public InputStream open() throws Exception { return new FileInputStream(output()); }
    }));
    WordTableStore.Entry recovered = null;
    for (WordTableStore.Entry entry : store.catalogEntries()) if (entry.name.equals(preview.dictionary) && entry.id.equals(value.getString("outputHash"))) recovered = entry;
    if (recovered == null) throw new IOException("Recovered dictionary was not imported");
    value.put("entryId", recovered.id).put("status", "imported"); save(value);
    // If deployment fails, the old selection is atomic and the imported source,
    // pristine snapshot and preview remain available. A repeated confirmation is idempotent.
    List<String> extras = new ArrayList<>(selection.extras);
    if (!selection.base.equals(recovered.id) && !extras.contains(recovered.id)) extras.add(recovered.id);
    store.apply(selection.base, extras, selection.patches);
    value.put("status", "applied");
    try { save(value); } catch (Exception retained) { /* The working selection is already committed; do not report a rollback. Copies remain retained. */ }
  }
  private static boolean same(JSONArray expected, List<String> actual) throws Exception
  { if (expected.length() != actual.size()) return false; for (int i = 0; i < actual.size(); i++) if (!expected.getString(i).equals(actual.get(i))) return false; return true; }
  private File output() { return new File(pending, "recovered.dict.yaml"); }
  void discard() throws IOException { erase(pending); }
  private void erase(File file) throws IOException
  {
    if (!file.getAbsolutePath().equals(pending.getPath()) && !file.getAbsolutePath().startsWith(pending.getPath() + File.separator))
      throw new IOException("Invalid recovery-copy path");
    if (file.isDirectory() && file.getCanonicalFile().equals(file.getAbsoluteFile()))
    { File[] children = file.listFiles(); if (children == null) throw new IOException("Cannot enumerate retained recovery copy"); for (File child : children) erase(child); }
    if (file.exists() && !file.delete()) throw new IOException("Could not remove retained recovery copies");
  }
}
