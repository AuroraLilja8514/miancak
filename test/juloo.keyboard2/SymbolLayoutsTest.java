package juloo.keyboard2;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SymbolLayoutsTest
{
  @Test public void onlyExplicitUnicodeWidthPairsChangeWithShift()
  {
    for (String[] pair : new String[][] {{"，", ","}, {"（", "("}, {"［", "["}, {"＋", "+"}, {"％", "%"}})
    {
      assertEquals(pair[1], SymbolLayouts.widthPair(pair[0]));
      assertEquals(pair[0], SymbolLayouts.widthPair(pair[1]));
      KeyValue value = SymbolLayouts.symbol(pair[0]);
      assertEquals(pair[0], value.layer(false).getSemanticText());
      assertEquals(pair[1], KeyModifier.modify(value, KeyValue.Modifier.SHIFT).getSemanticText());
    }
    for (String unchanged : new String[] {"【", "∫", "ℕ", "←", "−", "……"})
    {
      assertEquals(unchanged, SymbolLayouts.widthPair(unchanged));
      assertEquals(unchanged, SymbolLayouts.symbol(unchanged).layer(true).getSemanticText());
    }
  }
}
