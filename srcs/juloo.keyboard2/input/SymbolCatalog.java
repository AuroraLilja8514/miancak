package juloo.keyboard2.input;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content coordinates, separate from the symbols layout's input policy. */
public final class SymbolCatalog
{
  public static final int PAGE_SIZE = 28;
  private static final Map<String, List<String>> CONTENT = new LinkedHashMap<>();
  static
  {
    // The original page is kept at its exact coordinates for old user edits.
    add("common", "， 。 、 ； ： ？ ！ … — · （ ） 【 】 《 》 〈 〉 「 」 ‘ ’ “ ” 〔 〕 『 』");
    add("punctuation", "， 。 、 ； ： ？ ！ … …… — —— · ‧ ・ ﹏ ～ ＿ ＼ ／ ｜ ｀ ＇ ＂ ＃ ＠ ＆ ＊ ％ ‰ ‱ ． ﹐ ﹑ ﹒ ﹔ ﹕ ﹖ ﹗ ﹘ ﹣ ﹨ ﹡ ¡ ¿ § ¶ † ‡ • ‣ ⁂ ※ ⁕ ‼ ⁇ ⁈ ⁉ ‽ ⁃ ‐ ‑ ‒ – ― ﹉ ﹊ ﹋ ﹌ 〃 々 〆 〇 〝 〞 〟");
    add("brackets", "（ ） ［ ］ ｛ ｝ 【 】 〖 〗 〔 〕 《 》 〈 〉 「 」 『 』 ‘ ’ “ ” ‹ › « » ⟨ ⟩ ⟪ ⟫ ⟦ ⟧ ⦅ ⦆ ⦃ ⦄ ⌈ ⌉ ⌊ ⌋ ⟮ ⟯ ⦇ ⦈ ⦉ ⦊ ⦋ ⦌ ⦍ ⦎ ⦏ ⦐ ⦑ ⦒ ⦓ ⦔ ⦕ ⦖ ⦗ ⦘");
    add("math", "＋ − ± ∓ × ÷ ＝ ≠ ≈ ≃ ≅ ≡ ≤ ≥ ＜ ＞ ∝ ∞ √ ∛ ∜ ∑ ∏ ∐ ∫ ∬ ∭ ∮ ∯ ∰ ∂ ∇ Δ ∆ ∈ ∉ ∋ ∌ ⊂ ⊃ ⊆ ⊇ ⊊ ⊋ ∪ ∩ ∅ ∖ ∁ ∀ ∃ ∄ ¬ ∧ ∨ ⊻ ⊕ ⊖ ⊗ ⊘ ⊙ ⊚ ⊞ ⊟ ⊠ ⊡ ∴ ∵ ∶ ∷ ∣ ∤ ∥ ∦ ⊥ ⊢ ⊣ ⊤ ⊨ ⊩ ⊬ ⋀ ⋁ ⋂ ⋃ ⋄ ⋅ ⋆ ⋈ ≪ ≫ ≮ ≯ ≰ ≱ ≲ ≳ ⪅ ⪆ ⊲ ⊳ ⊴ ⊵ ∠ ∡ ∢ ∘ ∟ π ℯ ℏ ℓ ℕ ℤ ℚ ℝ ℂ");
    add("arrows", "← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇑ ⇒ ⇓ ⇔ ⇕ ⇖ ⇗ ⇘ ⇙ ↚ ↛ ↮ ↞ ↟ ↠ ↡ ↢ ↣ ↤ ↥ ↦ ↧ ↩ ↪ ↫ ↬ ↭ ↯ ↰ ↱ ↲ ↳ ↴ ↵ ↶ ↷ ↺ ↻ ⇀ ⇁ ⇂ ⇃ ⇄ ⇅ ⇆ ⇇ ⇈ ⇉ ⇊ ⇋ ⇌ ⇍ ⇎ ⇏ ⇠ ⇡ ⇢ ⇣ ⇤ ⇥ ⇧ ⇩ ⇦ ⇨ ⟵ ⟶ ⟷ ⟸ ⟹ ⟺ ⤴ ⤵ ⤶ ⤷ ⤺ ⤻ ⤿ ⥀");
    add("currency", "￥ ＄ € £ ₩ ¢ ¤ ¥ ₠ ₡ ₢ ₣ ₤ ₥ ₦ ₧ ₨ ₪ ₫ ₭ ₮ ₯ ₱ ₲ ₳ ₴ ₵ ₸ ₹ ₺ ₽ ₼ ₾ ₿ ° ℃ ℉ ′ ″ ‴ ％ ‰ ‱ ㎜ ㎝ ㎞ ㎎ ㎏ ㎡ ㎥ ㏄ ㏎ ㏑ ㏒ ㏕ ㎐ ㎑ ㎒ ㎓ ㎧ ㎨ ㎩ ㎪ ㎫ ㎬ ㎭ ㎮ ㎯");
    add("shapes", "○ ● ◯ ◌ ◐ ◑ ◒ ◓ ◔ ◕ ◖ ◗ ◎ ◉ ◦ ◇ ◆ ◈ ◊ □ ■ ▣ ▤ ▥ ▦ ▧ ▨ ▩ ▪ ▫ ▬ ▭ ▮ ▯ △ ▲ ▽ ▼ ◁ ◀ ▷ ▶ ☆ ★ ✦ ✧ ✩ ✪ ✫ ✬ ✭ ✮ ✯ ✰ ✓ ✔ ✕ ✖ ✗ ✘ ☑ ☒ ♠ ♡ ♢ ♣ ♤ ♥ ♦ ♧ ♩ ♪ ♫ ♬ ♭ ♮ ♯ ⌘ ⌥ ⌫ ⌦ ⏎ ⌨");
  }
  private static void add(String id, String symbols)
  { CONTENT.put(id, Collections.unmodifiableList(Arrays.asList(symbols.split(" +")))); }
  public static List<String> categories() { return Collections.unmodifiableList(new java.util.ArrayList<>(CONTENT.keySet())); }
  public static boolean contains(String category) { return CONTENT.containsKey(category); }
  public static int pageCount(String category)
  { return category.equals("common") ? 1 : (CONTENT.get(category).size() + PAGE_SIZE - 1) / PAGE_SIZE; }
  public static List<String> items(String category, int page)
  {
    List<String> values = CONTENT.get(category);
    return values.subList(page * PAGE_SIZE, Math.min(values.size(), (page + 1) * PAGE_SIZE));
  }
  public static String scope(String category, int page)
  { return category.equals("common") ? "symbols" : "symbols/" + category + "/" + page; }
  public static boolean validScope(String scope)
  {
    if (scope == null) return false;
    if (scope.equals("symbols")) return true;
    String[] parts = scope.split("/", -1);
    if (parts.length != 3 || !parts[0].equals("symbols") || !contains(parts[1]) || parts[1].equals("common")) return false;
    try
    {
      int page = Integer.parseInt(parts[2]);
      return page >= 0 && page < pageCount(parts[1]) && Integer.toString(page).equals(parts[2]);
    }
    catch (NumberFormatException invalid) { return false; }
  }
  private SymbolCatalog() {}
}
