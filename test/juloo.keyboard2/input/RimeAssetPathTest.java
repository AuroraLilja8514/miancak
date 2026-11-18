package juloo.keyboard2.input;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public final class RimeAssetPathTest
{
  @Test public void nestedPinnedBaseAssetsAreAllowed()
  {
    for (String name : new String[] {"dicts/jichu.dict.yaml", "lua/wanxiang/super_processor.lua", "opencc/wanxiang/STCharacters.ocd2", "wanxiang-lts-zh-hans.gram"})
      assertTrue(name, RimeData.validAssetName(name));
  }

  @Test public void traversalAndAmbiguousSeparatorsAreRefused()
  {
    for (String name : new String[] {"", "/absolute", "../user", "a/../user", "a/./b", "a//b", "a/", "a\\b", "a\0b"})
      assertFalse(name, RimeData.validAssetName(name));
  }

  @Test public void overlongAssetNamesAreRefused()
  {
    StringBuilder name = new StringBuilder();
    for (int i = 0; i < 257; i++) name.append('a');
    assertFalse(RimeData.validAssetName(name.toString()));
  }

  @Test public void manifestIsPersistedAtomicallyAndUnchangedDeploymentIsNotRewritten() throws Exception
  {
    Path root = Files.createTempDirectory("rime-manifest");
    try
    {
      byte[] original = "{\"default.yaml\": \"verified\"}\n".getBytes(StandardCharsets.UTF_8);
      RimeData.saveManifest(root.toFile(), original);
      File manifest = root.resolve("manifest.json").toFile();
      assertArrayEquals(original, Files.readAllBytes(manifest.toPath()));
      assertTrue(manifest.setLastModified(100000));
      RimeData.saveManifest(root.toFile(), original);
      assertEquals(100000, manifest.lastModified());
      byte[] updated = "{\"default.yaml\": \"updated\"}\n".getBytes(StandardCharsets.UTF_8);
      RimeData.saveManifest(root.toFile(), updated);
      assertArrayEquals(updated, Files.readAllBytes(manifest.toPath()));
      assertFalse(root.resolve("manifest.json.tmp").toFile().exists());
    }
    finally { remove(root.toFile()); }
  }

  @Test public void historicalFourAssetBundleIsVerifiedWithoutRewritingIt() throws Exception
  {
    Path root = Files.createTempDirectory("rime-earlier-bundle");
    String[] names = {"default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml", "pinyin_simp.dict.yaml"};
    StringBuilder historical = new StringBuilder("{");
    for (int i = 0; i < names.length; i++)
    {
      if (i > 0) historical.append(", ");
      historical.append('"').append(names[i]).append("\": \"").append(digest(names[i].getBytes(StandardCharsets.UTF_8))).append('"');
    }
    historical.append("}\n");
    Path shared = root.resolve("shared-" + digest(historical.toString().getBytes(StandardCharsets.UTF_8))); Files.createDirectory(shared);
    try
    {
      for (String name : names) Files.write(shared.resolve(name), name.getBytes(StandardCharsets.UTF_8));
      assertEquals(4, RimeData.legacyAssetDigests(shared.toFile()).size());
      assertFalse(shared.resolve("manifest.json").toFile().exists());
      for (String name : names) assertEquals(name, new String(Files.readAllBytes(shared.resolve(name)), StandardCharsets.UTF_8));
      Files.write(shared.resolve(names[0]), "damaged".getBytes(StandardCharsets.UTF_8));
      try { RimeData.legacyAssetDigests(shared.toFile()); fail("Changed earlier assets accepted"); }
      catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("integrity")); }
      try { RimeData.legacyAssetDigests(root.toFile()); fail("Unidentified directory accepted"); }
      catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("manifest")); }
    }
    finally { remove(root.toFile()); }
  }

  private static String digest(byte[] input) throws Exception
  {
    StringBuilder output = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(input)) output.append(String.format("%02x", b & 255));
    return output.toString();
  }
  private static void remove(File file) throws Exception
  {
    if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
    if (!file.delete()) throw new java.io.IOException("Cannot remove synthetic fixture");
  }
}
