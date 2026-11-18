package juloo.keyboard2.input;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LearningSnapshotTest
{
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private File source() throws Exception
  { File root = temporary.newFolder(); Files.write(new File(root, "CURRENT").toPath(), new byte[] {1, 2, 3}); return root; }
  @Test public void independentCopyAndPristineSnapshotRemainUnchanged() throws Exception
  {
    File original = source(), pristine = new File(temporary.getRoot(), "pristine"), working = new File(temporary.getRoot(), "working");
    long limit = LearningSnapshot.deadline(); String hash = LearningSnapshot.copy(original, pristine, limit);
    assertEquals(hash, LearningSnapshot.copy(pristine, working, limit));
    assertFalse(Files.isSameFile(new File(original, "CURRENT").toPath(), new File(pristine, "CURRENT").toPath()));
    assertFalse(Files.isSameFile(new File(pristine, "CURRENT").toPath(), new File(working, "CURRENT").toPath()));
    Files.write(new File(working, "CURRENT").toPath(), new byte[] {4});
    assertEquals(hash, LearningSnapshot.digest(original, limit)); assertEquals(hash, LearningSnapshot.digest(pristine, limit));
    Files.write(new File(original, "new.log").toPath(), new byte[] {5});
    assertNotEquals(hash, LearningSnapshot.digest(original, limit)); assertEquals(hash, LearningSnapshot.digest(pristine, limit));
  }
  @Test public void namesAndLengthsContributeToSnapshotIdentity() throws Exception
  {
    File root = source(); long limit = LearningSnapshot.deadline(); String hash = LearningSnapshot.digest(root, limit);
    assertTrue(new File(root, "CURRENT").renameTo(new File(root, "renamed"))); assertNotEquals(hash, LearningSnapshot.digest(root, limit));
  }
  @Test public void linksAndLinkedParentsAreRefused() throws Exception
  {
    File root = source(); Path link = temporary.getRoot().toPath().resolve("linked"); Files.createSymbolicLink(link, root.toPath());
    try { LearningSnapshot.digest(link.toFile(), LearningSnapshot.deadline()); fail("Linked root accepted"); } catch (IOException expected) {}
    Files.createSymbolicLink(root.toPath().resolve("other"), new File(root, "CURRENT").toPath());
    try { LearningSnapshot.digest(root, LearningSnapshot.deadline()); fail("Linked file accepted"); } catch (IOException expected) {}
  }
  @Test public void fileAndByteBoundsRejectBeforeCopying() throws Exception
  {
    File root = source();
    for (int i = 0; i < 512; i++) Files.write(new File(root, "entry" + i).toPath(), new byte[0]);
    try { LearningSnapshot.digest(root, LearningSnapshot.deadline()); fail("513 files accepted"); } catch (IOException expected) {}
    File large = temporary.newFolder(); try (RandomAccessFile file = new RandomAccessFile(new File(large, "oversize"), "rw")) { file.setLength(LearningSnapshot.MAX_BYTES + 1); }
    try { LearningSnapshot.digest(large, LearningSnapshot.deadline()); fail("Oversize accepted"); } catch (IOException expected) {}
  }
  @Test public void expiredBudgetMissingDatabaseAndOccupiedTargetFail() throws Exception
  {
    File root = source();
    try { LearningSnapshot.digest(root, System.nanoTime() - 1); fail("Expired deadline accepted"); } catch (IOException expected) {}
    try { LearningSnapshot.digest(temporary.newFolder(), LearningSnapshot.deadline()); fail("Empty database accepted"); } catch (IOException expected) {}
    try { LearningSnapshot.copy(root, temporary.newFolder(), LearningSnapshot.deadline()); fail("Existing target overwritten"); } catch (IOException expected) {}
  }
  @Test public void recoveredOutputUsesTheExistingStrictDictionaryParser() throws Exception
  {
    String source = "---\nname: miancak_recovered_pinyin_0123456789abcdef\nversion: '1'\nsort: by_weight\nuse_preset_vocabulary: false\n...\n旧短语\tjiu duan yu\t7\n";
    StringWriter normalized = new StringWriter();
    WordTable parsed = WordTable.parse(new ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)), normalized);
    assertEquals(1, parsed.rows); assertEquals("旧短语\tjiu duan yu\t7\n", normalized.toString());
    assertTrue(parsed.imports.isEmpty());
  }
}
