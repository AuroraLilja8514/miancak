package juloo.keyboard2;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LayoutImportEncodingTest
{
  private void rejects(int... values) throws Exception
  {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) bytes[i] = (byte)values[i];
    rejects(bytes);
  }
  private void rejects(byte[] bytes) throws Exception
  {
    try { PageKeyOverrides.decodeImportFile(bytes); fail("Accepted malformed UTF-8 layout bytes"); }
    catch (CharacterCodingException expected) {}
  }
  @Test public void wellFormedUnicodeAndEscapesRemainExact() throws Exception
  {
    String text = "{\"pages\":{\"zh\":{\"0:0:0\":\":literal:'你好🙂é\\\\路径'\"}}}\r\n";
    assertEquals(text, PageKeyOverrides.decodeImportFile(text.getBytes(StandardCharsets.UTF_8)));
  }
  @Test public void anIntentionalReplacementCharacterIsValidContent() throws Exception
  {
    String text = ":literal:'�'";
    assertEquals(text, PageKeyOverrides.decodeImportFile(text.getBytes(StandardCharsets.UTF_8)));
  }
  @Test public void aValidBomIsRetainedForTheExistingJsonParser() throws Exception
  {
    String text = "\ufeff{\"version\":4}";
    assertEquals(text, PageKeyOverrides.decodeImportFile(text.getBytes(StandardCharsets.UTF_8)));
  }
  @Test public void invalidContinuationOverlongSurrogateAndOutOfRangeSequencesAreRejected() throws Exception
  {
    rejects(0x80); rejects(0xff); rejects(0xc0, 0xaf); rejects(0xe0, 0x80, 0xaf);
    rejects(0xed, 0xa0, 0x80); rejects(0xf4, 0x90, 0x80, 0x80);
    rejects(0xe4, 0x41, 0xad); rejects(0xf8, 0x88, 0x80, 0x80, 0x80);
  }
  @Test public void truncatedMultibyteEndingsAreRejected() throws Exception
  {
    for (String text : new String[] {"é", "中", "🙂"})
    {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      for (int length = 1; length < bytes.length; length++) rejects(Arrays.copyOf(bytes, length));
    }
  }
  @Test public void malformedBytesInsideOtherwiseWellFormedJsonAreRejected() throws Exception
  {
    byte[] bytes = "{\"pages\":{\"zh\":{\"0:0:0\":\":literal:'?'\"}}}".getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < bytes.length; i++) if (bytes[i] == '?') bytes[i] = (byte)0x80;
    rejects(bytes);
  }
  @Test public void validTextAcrossReadBufferBoundariesAndAtTheByteLimitIsExact() throws Exception
  {
    for (int length : new int[] {4094, 4095, 4096, 4097, PageKeyOverrides.MAX_BYTES - 4})
    {
      char[] padding = new char[length]; Arrays.fill(padding, ' ');
      String text = new String(padding) + "🙂";
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      assertTrue(bytes.length <= PageKeyOverrides.MAX_BYTES);
      assertEquals(text, PageKeyOverrides.decodeImportFile(bytes));
    }
  }
}
