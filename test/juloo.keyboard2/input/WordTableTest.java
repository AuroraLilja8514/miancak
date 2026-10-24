package juloo.keyboard2.input;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class WordTableTest
{
  private WordTable parse(String source, Writer out) throws Exception
  { return WordTable.parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), out); }
  private void rejects(String source) throws Exception
  { try { parse(source, new StringWriter()); fail("Accepted invalid input: " + source.substring(0, Math.min(source.length(), 80))); } catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); } }
  @Test public void streamsUtf8AndDefaultColumnsWithoutRewritingExactText() throws Exception
  {
    StringWriter out = new StringWriter();
    WordTable info = parse("\ufeff# license\r\n---\r\nname: test\r\nversion: '1.0'\r\n...\r\n𠮷祥\tji xiang\t12\r\n你好\tni hao\r\n", out);
    assertEquals("test", info.name); assertEquals("1.0", info.version); assertEquals(2, info.rows);
    assertEquals("𠮷祥\tji xiang\t12\n你好\tni hao\t1\n", out.toString());
  }
  @Test public void supportsColumnOrderAndBothListStylesWithDependencies() throws Exception
  {
    StringWriter out = new StringWriter();
    WordTable info = parse("---\nname: root\nversion: \"v#1\" # comment\ncolumns: [code, text, weight, stem]\nimport_tables:\n  - 'cn_dicts/base'\n  - \"extra\"\n...\nni hao\t你好\t1e2\t\n", out);
    assertEquals(Arrays.asList("cn_dicts/base", "extra"), info.imports); assertEquals("v#1", info.version);
    assertEquals("你好\tni hao\t1e2\n", out.toString());
    assertEquals(0, parse("---\nname: root\nimport_tables: [base, 'extra']\n...\n", new StringWriter()).rows);
  }
  @Test public void originalOrderProducesExplicitDecreasingWeights() throws Exception
  {
    StringWriter out = new StringWriter(); parse("---\nname: ordered\nsort: original\ncolumns:\n  - text\n  - code\n...\n甲\tjia\n乙\tyi\n", out);
    String[] rows = out.toString().split("\n");
    assertTrue(Double.parseDouble(rows[0].split("\t")[2]) > Double.parseDouble(rows[1].split("\t")[2]));
  }
  @Test public void rejectsExecutableOrUnsupportedYamlAndAmbiguousFields() throws Exception
  {
    for (String header : Arrays.asList("name: test\nname: again", "name: !!java/object bad", "name: &anchor test", "name: test\nencoder: {}", "name: test\nuse_preset_vocabulary: true", "name: ../escape", "name: test\ncolumns: [text, weight]", "name: test\nsort: by_original"))
      rejects("---\n" + header + "\n...\n你好\tni hao\t1\n");
    rejects("---\nname: root\nimport_tables: [../bad]\n...\n");
    rejects("---\nname: root\nimport_tables: [/absolute]\n...\n");
    rejects("---\nname: root\nimport_tables: [bad//path]\n...\n");
  }
  @Test public void rejectsNonPinyinCodesAndUnsupportedDataFields() throws Exception
  {
    for (String row : Arrays.asList("你好\tni3 hao3\t1", "你好\tnǐ hǎo\t1", "你好\tNi Hao\t1", "你好\tni;xx hao;yy\t1", "你好\t\t1", "你好\tni hao\t100%", "你好\tni hao\t-1", "你好\tni hao\t1e309", "你好\tni hao\t1\textra"))
      rejects("---\nname: test\n...\n" + row + "\n");
    rejects("---\nname: test\ncolumns: [text, code, stem]\n...\n你好\tni hao\tnh\n");
    rejects("---\nname: test\n...\n"); rejects("name: test\n...\n你好\tni hao\n");
  }
  @Test public void rejectsInvalidUtf8AndOversizedLinesBeforeUnboundedAllocation() throws Exception
  {
    byte[] invalid = { (byte)0xff, (byte)0xfe };
    try { WordTable.parse(new ByteArrayInputStream(invalid), new StringWriter()); fail(); } catch (IOException expected) {}
    char[] giant = new char[16385]; Arrays.fill(giant, 'a'); rejects(new String(giant));
    char[] unicode = new char[6000]; Arrays.fill(unicode, '中'); rejects("#" + new String(unicode));
  }
  @Test public void parsesPinnedUnmodifiedExistingRimeIceTable() throws Exception
  {
    try (InputStream input = new FileInputStream("androidTest/assets/word-tables/8105.dict.yaml"))
    {
      WordTable table = WordTable.parse(input, new StringWriter()); assertEquals("8105", table.name); assertTrue(table.rows > 8000);
    }
  }
  @Test public void refusesToSilentlyDropUncodedRowsFromAnExistingMixedTable() throws Exception
  {
    try (InputStream input = new FileInputStream("androidTest/assets/word-tables/others.dict.yaml"))
    { try { WordTable.parse(input, new StringWriter()); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("Pinyin")); } }
  }
}
