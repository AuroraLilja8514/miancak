package juloo.keyboard2.input;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** One immutable asset at a time. A failed/interrupted copy never replaces the
 * last complete target; a stale partial file is overwritten on the next try. */
final class RimeAssetInstaller
{
  interface Source { InputStream open() throws IOException; }

  static void install(File root, String name, String expected, Source source) throws IOException
  {
    if (!expected.matches("[a-f0-9]{64}")) throw new IOException("Invalid Rime asset digest");
    File target = RimeData.managedFile(root, name), parent = target.getParentFile();
    File temporary = new File(parent, target.getName() + ".tmp");
    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create Rime asset directory");
    regularOrAbsent(target); regularOrAbsent(temporary);
    if (target.isFile())
      try (InputStream input = new FileInputStream(target))
      { if (hash(input).equals(expected)) return; }
    boolean installed = false;
    try
    {
      MessageDigest digest = digest();
      interrupted();
      // Unlink a stale managed partial instead of truncating an inode that may
      // have other links. Original imports and learning are never copy targets.
      Files.deleteIfExists(temporary.toPath());
      try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(temporary))
      {
        byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1)
        { interrupted(); digest.update(buffer, 0, count); output.write(buffer, 0, count); }
        interrupted(); output.getFD().sync();
      }
      if (!hex(digest.digest()).equals(expected)) throw new IOException("Packaged Rime asset failed integrity check");
      interrupted();
      if (!temporary.renameTo(target)) throw new IOException("Cannot atomically deploy Rime asset");
      installed = true;
    }
    finally { if (!installed) temporary.delete(); }
  }

  private static void regularOrAbsent(File file) throws IOException
  {
    if (Files.isSymbolicLink(file.toPath()) || (file.exists() && !file.isFile()))
      throw new IOException("Rime asset staging path is not a regular file");
  }
  private static void interrupted() throws InterruptedIOException
  { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Rime asset preparation interrupted"); }
  private static String hash(InputStream input) throws IOException
  {
    MessageDigest digest = digest(); byte[] buffer = new byte[16384]; int count;
    while ((count = input.read(buffer)) != -1) { interrupted(); digest.update(buffer, 0, count); }
    return hex(digest.digest());
  }
  private static MessageDigest digest()
  { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
  private static String hex(byte[] bytes)
  { StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return result.toString(); }
  private RimeAssetInstaller() {}
}
