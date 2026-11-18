package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class RimeConfigPatchTest
{
  @Test public void onlyNamedNonPrivatePatchTargetsAreAccepted()
  {
    for (String name : new String[] {"default.custom.yaml", "wanxiang.custom.yaml", "miancak_pinyin.custom.yaml"}) assertTrue(RimeConfigPatch.validFilename(name));
    for (String name : new String[] {null, "", "../wanxiang.custom.yaml", "miancak_pinyin_private.custom.yaml", "wanxiang.schema.yaml", "other.custom.yaml", "WANXIANG.custom.yaml"}) assertFalse(RimeConfigPatch.validFilename(name));
  }
  @Test public void strictUtf8PreservesBomCommentsAndOriginalBytes() throws Exception
  {
    byte[] bytes = "\ufeff# 我的设置\npatch: {menu/page_size: 5}\n".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(bytes, RimeConfigPatch.read(new ByteArrayInputStream(bytes)));
    assertEquals("\ufeff# 我的设置\npatch: {menu/page_size: 5}\n", RimeConfigPatch.decode(bytes));
  }
  @Test public void malformedUtf8NulAndSizeLimitsFailBeforeNativeParsing() throws Exception
  {
    for (byte[] bytes : new byte[][] {new byte[0], {(byte)0xc3, (byte)0x28}, {0}, new byte[RimeConfigPatch.MAX_BYTES + 1]})
    {
      try { RimeConfigPatch.read(new ByteArrayInputStream(bytes)); fail("Invalid custom file accepted"); }
      catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
  }
}
