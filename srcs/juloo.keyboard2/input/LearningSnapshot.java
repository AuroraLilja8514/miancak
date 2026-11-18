package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Closed, independent copies only. No hard links, symlinks or original DB open. */
final class LearningSnapshot
{
  static final long MAX_BYTES = 64L * 1024 * 1024;
  static final int MAX_FILES = 512;
  static long deadline() { return System.nanoTime() + 60_000_000_000L; }
  static void check(long deadline) throws IOException
  { if (System.nanoTime() >= deadline) throw new IOException("Learning snapshot exceeded its 60-second budget"); }
  private static List<Path> files(File directory, long deadline) throws IOException
  {
    Path root = directory.toPath();
    if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile()) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Expected a managed legacy database directory");
    List<Path> files = new ArrayList<>(); final long[] size = {0}; final int[] dirs = {0};
    Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException
      { check(deadline); if (++dirs[0] > 64) throw new IOException("Too many snapshot directories"); return FileVisitResult.CONTINUE; }
      @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException
      {
        check(deadline);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("Snapshot links and special files are unsupported");
        size[0] += attrs.size(); files.add(path);
        if (files.size() > MAX_FILES || size[0] > MAX_BYTES) throw new IOException("Legacy snapshot exceeds 512 files / 64 MiB");
        return FileVisitResult.CONTINUE;
      }
    });
    if (files.isEmpty()) throw new IOException("No old Pinyin learning database was found");
    files.sort(Comparator.comparing(p -> root.relativize(p).toString())); return files;
  }
  static String digest(File directory, long deadline) throws Exception
  {
    MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; long bytes = 0;
    for (Path path : files(directory, deadline))
    {
      digest.update(directory.toPath().relativize(path).toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
      long size = Files.size(path); for (int i = 7; i >= 0; i--) digest.update((byte)(size >>> (8 * i)));
      try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS))
      { int n; while ((n = in.read(buffer)) != -1) { check(deadline); bytes += n;
        if (bytes > MAX_BYTES) throw new IOException("Snapshot grew beyond its size bound"); digest.update(buffer, 0, n); } }
    }
    return hex(digest.digest());
  }
  static String copy(File source, File target, long deadline) throws Exception
  {
    String before = digest(source, deadline);
    if (target.exists() || !target.mkdirs()) throw new IOException("Snapshot target must be fresh");
    long written = 0;
    for (Path path : files(source, deadline))
    {
      check(deadline); File to = new File(target, source.toPath().relativize(path).toString());
      if (!to.getParentFile().isDirectory() && !to.getParentFile().mkdirs()) throw new IOException("Cannot stage snapshot directory");
      try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS); FileOutputStream out = new FileOutputStream(to))
      { byte[] buffer = new byte[16384]; int n;
        while ((n = in.read(buffer)) != -1) { check(deadline); if ((written += n) > MAX_BYTES) throw new IOException("Snapshot grew beyond its bound"); out.write(buffer, 0, n); }
        out.getFD().sync(); }
    }
    if (!before.equals(digest(source, deadline)) || !before.equals(digest(target, deadline)))
      throw new IOException("Old learning changed during snapshot; no recovery was activated");
    return before;
  }
  static String hash(File file) throws Exception
  {
    if (!file.getCanonicalFile().equals(file.getAbsoluteFile()) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() > MAX_BYTES)
      throw new IOException("Invalid recovered dictionary");
    MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[16384]; int n; long size = 0;
    try (InputStream in = new FileInputStream(file))
    { while ((n = in.read(buffer)) != -1) { if ((size += n) > MAX_BYTES) throw new IOException("Dictionary exceeds size bound"); digest.update(buffer, 0, n); } }
    return hex(digest.digest());
  }
  private static String hex(byte[] bytes)
  { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
  private LearningSnapshot() {}
}
