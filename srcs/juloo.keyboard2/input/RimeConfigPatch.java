package juloo.keyboard2.input;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Bounded UTF-8 configuration data, validated by the pinned native YAML parser. */
public final class RimeConfigPatch
{
  public static final int MAX_BYTES = 256 * 1024;
  public static boolean validFilename(String name)
  { return "default.custom.yaml".equals(name) || "wanxiang.custom.yaml".equals(name) || "miancak_pinyin.custom.yaml".equals(name); }

  static String decode(byte[] bytes) throws IOException
  {
    if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Custom YAML must be nonempty and at most 256 KiB");
    try
    {
      String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      if (text.indexOf('\0') >= 0) throw new IOException("Custom YAML contains NUL");
      return text;
    }
    catch (CharacterCodingException invalid) { throw new IOException("Custom YAML must be valid UTF-8", invalid); }
  }

  static byte[] read(InputStream input) throws IOException
  {
    ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
    while ((n = input.read(buffer)) != -1)
    { if (output.size() + n > MAX_BYTES) throw new IOException("Custom YAML exceeds 256 KiB"); output.write(buffer, 0, n); }
    byte[] bytes = output.toByteArray(); decode(bytes); return bytes;
  }

  static String validate(String filename, byte[] original) throws IOException
  {
    if (!validFilename(filename)) throw new IOException("Choose default.custom.yaml, wanxiang.custom.yaml or miancak_pinyin.custom.yaml");
    decode(original);
    return RimeEngine.customConfig(filename, original, null);
  }
  static String merge(String filename, byte[] original, String schema) throws IOException
  { decode(original); return RimeEngine.customConfig(filename, original, schema.getBytes(StandardCharsets.UTF_8)); }
  private RimeConfigPatch() {}
}
