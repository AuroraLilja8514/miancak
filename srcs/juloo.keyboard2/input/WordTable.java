package juloo.keyboard2.input;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The documented, data-only Rime profile. No external YAML reaches librime. */
public final class WordTable
{
  public static final long MAX_BYTES = 64L * 1024 * 1024;
  private static final Pattern CODE = Pattern.compile("[a-z]{1,8}( [a-z]{1,8})*");
  private static final Pattern WEIGHT = Pattern.compile("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
  private static final Pattern SPACES = Pattern.compile(" +");
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}");
  public final String name, version;
  public final List<String> imports;
  public final long rows;
  private WordTable(String name, String version, List<String> imports, long rows)
  {
    this.name = name; this.version = version;
    this.imports = Collections.unmodifiableList(new ArrayList<>(imports)); this.rows = rows;
  }

  public static WordTable parse(InputStream source, Writer normalized) throws IOException
  {
    InputStream bounded = new FilterInputStream(source) {
      long count;
      private void add(int n) throws IOException
      { if (n > 0 && (count += n) > MAX_BYTES) throw new IOException("File exceeds 64 MiB / 文件超过 64 MiB"); }
      @Override public int read() throws IOException { int n = super.read(); if (n >= 0) add(1); return n; }
      @Override public int read(byte[] b, int off, int len) throws IOException
      { int n = in.read(b, off, len); add(n); return n; }
    };
    BufferedReader input = new BufferedReader(new InputStreamReader(bounded,
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)));
    Map<String, String> fields = new HashMap<>();
    // Older Android regex objects allocate native ICU state. Reuse matchers for
    // every row rather than relying on Java GC to release thousands of them.
    Matcher codeMatcher = CODE.matcher(""), weightMatcher = WEIGHT.matcher(""), spaceMatcher = SPACES.matcher("");
    Map<String, List<String>> lists = new HashMap<>();
    String list = null, line;
    boolean started = false, ended = false;
    int number = 0, headerBytes = 0;
    List<String> columns = Arrays.asList("text", "code", "weight");
    long rows = 0;
    while ((line = line(input)) != null)
    {
      number++;
      if (number == 1 && line.startsWith("\ufeff")) line = line.substring(1);
      if (!ended)
      {
        headerBytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
        if (headerBytes > 65536) throw error(number, "Header exceeds 64 KiB / 表头过大");
        String value = comment(line).trim();
        if (value.isEmpty()) continue;
        if (!started)
        {
          if (!value.equals("---")) throw error(number, "Expected --- / 缺少词表表头");
          started = true; continue;
        }
        if (value.equals("..."))
        {
          ended = true;
          if (!fields.containsKey("name") || !validName(fields.get("name")))
            throw error(number, "Invalid dictionary name / 词表名称无效");
          if (value(fields, "version", "").length() > 256) throw error(number, "Version too long / 版本说明过长");
          String sort = value(fields, "sort", "by_weight");
          if (!sort.equals("by_weight") && !sort.equals("by_original")) throw error(number, "Unsupported sort / 不支持的排序");
          if (fields.containsKey("use_preset_vocabulary") && !fields.get("use_preset_vocabulary").equals("false"))
            throw error(number, "Preset vocabulary needs conversion / 请先将预设词频转换为明确权重");
          if (lists.containsKey("columns")) columns = lists.get("columns");
          if (new HashSet<>(columns).size() != columns.size() || !columns.contains("text") || !columns.contains("code")
              || !Arrays.asList("text", "code", "weight", "stem").containsAll(columns))
            throw error(number, "Unsupported columns / 不支持的数据列");
          continue;
        }
        if (line.indexOf('\t') >= 0) throw error(number, "Tabs in header / 表头请使用空格缩进");
        if (Character.isWhitespace(line.charAt(0)))
        {
          if (list == null || !value.startsWith("- ")) throw error(number, "Unsupported nested YAML / 不支持嵌套配置");
          lists.get(list).add(scalar(value.substring(2).trim(), number)); continue;
        }
        int colon = value.indexOf(':');
        if (colon <= 0) throw error(number, "Invalid header / 表头无效");
        String key = value.substring(0, colon).trim(), raw = value.substring(colon + 1).trim();
        if (fields.containsKey(key) || lists.containsKey(key)) throw error(number, "Duplicate header key / 表头字段重复: " + key);
        list = null;
        if (key.equals("columns") || key.equals("import_tables"))
        {
          List<String> items = new ArrayList<>(); lists.put(key, items);
          if (raw.isEmpty()) list = key;
          else
          {
            if (!raw.startsWith("[") || !raw.endsWith("]")) throw error(number, "Expected a list / 需要列表");
            String body = raw.substring(1, raw.length() - 1).trim();
            if (!body.isEmpty()) for (String item : split(body, ',')) items.add(scalar(item.trim(), number));
          }
        }
        else if (Arrays.asList("name", "version", "sort", "use_preset_vocabulary").contains(key))
          fields.put(key, scalar(raw, number));
        else throw error(number, "Unsupported header key / 不支持的配置字段: " + key);
        continue;
      }
      if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
      String[] values = line.split("\t", -1);
      if (values.length > columns.size()) throw error(number, "Too many columns / 数据列过多");
      String text = at(values, columns.indexOf("text")), code = spaceMatcher.reset(at(values, columns.indexOf("code")).trim()).replaceAll(" ");
      if (text.isEmpty() || text.codePointCount(0, text.length()) > 256) throw error(number, "Empty or long text / 词条为空或过长");
      for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) throw error(number, "Control character / 词条包含控制字符");
      if (!codeMatcher.reset(code).matches()) throw error(number, "Expected untoned Pinyin / 需要小写无声调拼音: " + code);
      if (!at(values, columns.indexOf("stem")).trim().isEmpty()) throw error(number, "Non-empty stem is unsupported / 不支持非空造词码");
      String weight = at(values, columns.indexOf("weight")).trim();
      if (weight.isEmpty()) weight = "1";
      if (!weightMatcher.reset(weight).matches()) throw error(number, "Invalid weight / 需要明确的非负权重");
      double frequency;
      try { frequency = Double.parseDouble(weight); } catch (NumberFormatException invalid) { throw error(number, "Invalid weight / 权重无效"); }
      if (Double.isInfinite(frequency) || frequency > 1e12) throw error(number, "Weight exceeds limit / 权重超过上限");
      rows++;
      if (value(fields, "sort", "by_weight").equals("by_original")) weight = Double.toString(1e9 / rows);
      normalized.write(text); normalized.write('\t'); normalized.write(code); normalized.write('\t'); normalized.write(weight); normalized.write('\n');
    }
    if (!ended) throw error(number, "Missing ... / 缺少表头结束标记");
    List<String> imports = lists.containsKey("import_tables") ? lists.get("import_tables") : Collections.emptyList();
    if (imports.size() > 64) throw error(number, "Too many dependencies / 依赖过多");
    for (String dependency : imports) if (!validReference(dependency)) throw error(number, "Invalid dependency / 依赖名称无效: " + dependency);
    if (rows == 0 && imports.isEmpty()) throw error(number, "Empty dictionary / 空词表");
    return new WordTable(fields.get("name"), value(fields, "version", ""), imports, rows);
  }

  private static String value(Map<String, String> values, String key, String fallback)
  { String found = values.get(key); return found == null ? fallback : found; }

  public static boolean validName(String name) { return NAME.matcher(name).matches(); }
  public static boolean validReference(String value)
  {
    if (value.length() > 256 || value.startsWith("/") || value.endsWith("/")) return false;
    for (String part : value.split("/", -1)) if (!validName(part) || part.equals(".") || part.equals("..")) return false;
    return true;
  }
  public static String referenceName(String reference) { return reference.substring(reference.lastIndexOf('/') + 1); }
  private static String at(String[] values, int index) { return index < 0 || index >= values.length ? "" : values[index]; }
  private static IOException error(int line, String detail) { return new IOException("Line / 行 " + line + ": " + detail); }
  private static String line(BufferedReader reader) throws IOException
  {
    StringBuilder result = new StringBuilder(); int c;
    while ((c = reader.read()) != -1 && c != '\n')
    {
      if (result.length() >= 16384) throw new IOException("Line exceeds 16 KiB / 单行过长");
      result.append((char)c);
    }
    if (c == -1 && result.length() == 0) return null;
    if (result.length() > 0 && result.charAt(result.length() - 1) == '\r') result.setLength(result.length() - 1);
    if (result.toString().getBytes(StandardCharsets.UTF_8).length > 16384) throw new IOException("Line exceeds 16 KiB / 单行过长");
    return result.toString();
  }
  private static String comment(String value)
  {
    boolean single = false, doubled = false, escape = false;
    for (int i = 0; i < value.length(); i++)
    {
      char c = value.charAt(i);
      if (escape) { escape = false; continue; }
      if (c == '\\' && doubled) { escape = true; continue; }
      if (c == '\'' && !doubled) single = !single;
      if (c == '"' && !single) doubled = !doubled;
      if (c == '#' && !single && !doubled) return value.substring(0, i);
    }
    return value;
  }
  private static List<String> split(String value, char separator) throws IOException
  {
    List<String> result = new ArrayList<>(); boolean single = false, doubled = false, escape = false; int begin = 0;
    for (int i = 0; i < value.length(); i++)
    {
      char c = value.charAt(i);
      if (escape) { escape = false; continue; }
      if (c == '\\' && doubled) { escape = true; continue; }
      if (c == '\'' && !doubled) single = !single;
      if (c == '"' && !single) doubled = !doubled;
      if (c == separator && !single && !doubled) { result.add(value.substring(begin, i)); begin = i + 1; }
    }
    if (single || doubled) throw new IOException("Unclosed quote / 引号未闭合");
    result.add(value.substring(begin)); return result;
  }
  private static String scalar(String value, int line) throws IOException
  {
    if (value.isEmpty()) throw error(line, "Empty header value / 表头值为空");
    if (value.startsWith("'"))
    {
      if (value.length() < 2 || !value.endsWith("'")) throw error(line, "Unclosed quote / 引号未闭合");
      return value.substring(1, value.length() - 1).replace("''", "'");
    }
    if (value.startsWith("\""))
    {
      if (value.length() < 2 || !value.endsWith("\"")) throw error(line, "Unclosed quote / 引号未闭合");
      StringBuilder out = new StringBuilder();
      for (int i = 1; i < value.length() - 1; i++)
      {
        char c = value.charAt(i);
        if (c == '\\')
        {
          if (++i >= value.length() - 1) throw error(line, "Invalid escape / 转义无效");
          c = value.charAt(i);
          switch (c)
          {
            case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
            case '\\': case '"': break;
            default: throw error(line, "Unsupported escape / 不支持的转义");
          }
        }
        else if (c == '"') throw error(line, "Unexpected quote / 多余引号");
        out.append(c);
      }
      return out.toString();
    }
    if ("&*!|>{[".indexOf(value.charAt(0)) >= 0 || value.contains(": ")) throw error(line, "Unsupported YAML value / 不支持的 YAML 值");
    return value;
  }
}
