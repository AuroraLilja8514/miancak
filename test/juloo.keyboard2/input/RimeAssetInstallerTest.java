package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RimeAssetInstallerTest
{
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final byte[] complete = "complete offline grammar fixture".getBytes(StandardCharsets.UTF_8);
  private File root() throws IOException { return temporary.newFolder(); }
  private String digest(byte[] bytes) throws Exception
  { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255)); return out.toString(); }
  private void install(File root, RimeAssetInstaller.Source source) throws Exception
  { RimeAssetInstaller.install(root, "data/model.gram", digest(complete), source); }
  private void assertNoPartial(File root)
  { assertFalse(new File(root, "data/model.gram.tmp").exists()); }

  @Test public void completeCopyIsVerifiedAndCommitted() throws Exception
  {
    File root = root(); install(root, () -> new ByteArrayInputStream(complete));
    assertArrayEquals(complete, Files.readAllBytes(new File(root, "data/model.gram").toPath())); assertNoPartial(root);
  }
  @Test public void validAssetIsNotRewrittenOrReopened() throws Exception
  {
    File root = root(); install(root, () -> new ByteArrayInputStream(complete));
    File target = new File(root, "data/model.gram"); assertTrue(target.setLastModified(100000));
    install(root, () -> { throw new AssertionError("APK asset reopened"); });
    assertEquals(100000, target.lastModified());
  }
  @Test public void readFailurePreservesCompleteTargetAndOtherUserFiles() throws Exception
  {
    File root = root(); File target = new File(root, "data/model.gram"); assertTrue(target.getParentFile().mkdirs());
    byte[] old = "earlier complete data".getBytes(StandardCharsets.UTF_8); Files.write(target.toPath(), old);
    File learning = new File(root, "learning-original"); Files.write(learning.toPath(), complete);
    try { install(root, () -> new InputStream() {
      int read;
      public int read() throws IOException { if (++read > 3) throw new IOException("synthetic full storage/read failure"); return 'x'; }
      public int read(byte[] bytes, int offset, int length) throws IOException { bytes[offset] = (byte)read(); return 1; }
    }); fail("copy failure hidden"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("synthetic")); }
    assertArrayEquals(old, Files.readAllBytes(target.toPath()));
    assertArrayEquals(complete, Files.readAllBytes(learning.toPath())); assertNoPartial(root);
  }
  @Test public void wrongDigestNeverPublishesPartialOrReplacesEarlierTarget() throws Exception
  {
    File root = root(); install(root, () -> new ByteArrayInputStream(complete));
    File target = new File(root, "data/model.gram"); byte[] old = "retained".getBytes(StandardCharsets.UTF_8); Files.write(target.toPath(), old);
    try { install(root, () -> new ByteArrayInputStream(new byte[] {1,2,3})); fail("bad digest installed"); }
    catch (IOException expected) { assertTrue(expected.getMessage().contains("integrity")); }
    assertArrayEquals(old, Files.readAllBytes(target.toPath())); assertNoPartial(root);
  }
  @Test public void interruptedCopyLeavesOriginalAndNextAttemptCanResume() throws Exception
  {
    File root = root();
    try { install(root, () -> new ByteArrayInputStream(complete) {
      @Override public synchronized int read(byte[] bytes, int offset, int length)
      { int n = super.read(bytes, offset, length); Thread.currentThread().interrupt(); return n; }
    }); fail("interruption ignored"); }
    catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
    finally { Thread.interrupted(); }
    assertFalse(new File(root, "data/model.gram").exists()); assertNoPartial(root);
    install(root, () -> new ByteArrayInputStream(complete)); assertArrayEquals(complete, Files.readAllBytes(new File(root, "data/model.gram").toPath()));
  }
  @Test public void stalePartialFromProcessInterruptionIsReplaced() throws Exception
  {
    File root = root(), partial = new File(root, "data/model.gram.tmp"); assertTrue(partial.getParentFile().mkdirs());
    Files.write(partial.toPath(), new byte[] {1,2}); install(root, () -> new ByteArrayInputStream(complete));
    assertArrayEquals(complete, Files.readAllBytes(new File(root, "data/model.gram").toPath())); assertNoPartial(root);
  }
  @Test public void stalePartialHardLinkCannotTruncateOriginal() throws Exception
  {
    File root = root(), original = temporary.newFile(); byte[] kept = "kept original".getBytes(StandardCharsets.UTF_8); Files.write(original.toPath(), kept);
    File partial = new File(root, "data/model.gram.tmp"); assertTrue(partial.getParentFile().mkdirs());
    Files.createLink(partial.toPath(), original.toPath()); install(root, () -> new ByteArrayInputStream(complete));
    assertArrayEquals(kept, Files.readAllBytes(original.toPath()));
    assertArrayEquals(complete, Files.readAllBytes(new File(root, "data/model.gram").toPath())); assertNoPartial(root);
  }
  @Test public void stagingSymlinkCannotOverwriteOriginal() throws Exception
  {
    File root = root(), original = temporary.newFile(); Files.write(original.toPath(), complete);
    File partial = new File(root, "data/model.gram.tmp"); assertTrue(partial.getParentFile().mkdirs());
    Files.createSymbolicLink(partial.toPath(), original.toPath());
    try { install(root, () -> new ByteArrayInputStream(complete)); fail("symlink followed"); } catch (IOException expected) {}
    assertArrayEquals(complete, Files.readAllBytes(original.toPath())); assertTrue(Files.isSymbolicLink(partial.toPath()));
  }
  @Test public void targetSymlinkAndDirectoryAreRefused() throws Exception
  {
    File root = root(), original = temporary.newFile(); Files.write(original.toPath(), complete);
    File target = new File(root, "data/model.gram"); assertTrue(target.getParentFile().mkdirs());
    Files.createSymbolicLink(target.toPath(), original.toPath());
    try { install(root, () -> new ByteArrayInputStream(complete)); fail("target symlink followed"); } catch (IOException expected) {}
    Files.delete(target.toPath()); assertTrue(target.mkdir());
    try { install(root, () -> new ByteArrayInputStream(complete)); fail("directory replaced"); } catch (IOException expected) {}
    assertArrayEquals(complete, Files.readAllBytes(original.toPath()));
  }
  @Test public void invalidDigestDoesNotOpenSourceOrCreateFiles() throws Exception
  {
    File root = root();
    try { RimeAssetInstaller.install(root, "model", "invalid", () -> { throw new AssertionError(); }); fail("invalid digest"); }
    catch (IOException expected) {}
    assertEquals(0, root.list().length);
  }
}
