package juloo.keyboard2.input;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageConfigTest
{
  @Test public void everyFnEdgeIsSymmetricAndOwnsOneBase()
  {
    PageConfig config = new PageConfig(null, null);
    for (String id : PageConfig.IDS)
    {
      String target = config.fn(id);
      if (!target.isEmpty())
      {
        assertEquals(id, config.fn(target));
        assertEquals(PageConfig.base(id), PageConfig.base(target));
        assertEquals(PageConfig.mode(id), PageConfig.mode(target));
      }
    }
    assertEquals("zh_fn", config.fn("zh"));
    assertEquals("en_fn", config.fn("en"));
    for (String id : Arrays.asList("symbols", "emoji", "clipboard", "pinyin", "zhuyin"))
      assertEquals("", config.fn(id));
  }
  @Test public void invalidAndDuplicateIdsCannotStrandRequiredPairs()
  {
    PageConfig config = new PageConfig("greek,greek,missing,en", "greek,missing");
    assertEquals(Arrays.asList("greek", "en", "zh", "zh_fn", "en_fn", "greek_fn"), config.visible());
    for (String id : Arrays.asList("zh", "zh_fn", "en", "en_fn"))
      assertTrue(config.enable(id, false).enabled.contains(id));
  }
  @Test public void optionalPairsAreEnabledAndDisabledTogether()
  {
    PageConfig config = new PageConfig(null, null).enable("greek_fn", false);
    assertFalse(config.enabled.contains("greek"));
    assertFalse(config.enabled.contains("greek_fn"));
    assertEquals("", config.fn("greek"));
    config = config.enable("greek", true).move("numeric", -1);
    assertTrue(config.enabled.contains("greek_fn"));
    assertEquals(config.visible(), new PageConfig(PageConfig.csv(config.order), PageConfig.csv(config.enabled)).visible());
  }
  @Test public void chooserAndDirectFnHaveIdenticalStateAndPolicy()
  {
    PageState state = new PageState();
    state.select("zh_fn"); assertTrue(state.fnActive()); assertEquals(InputRouter.Mode.CHINESE, state.mode());
    state.select("en_fn"); assertTrue(state.fnActive()); assertEquals(InputRouter.Mode.ENGLISH, state.mode());
    state.select("symbols"); assertFalse(state.fnActive()); assertEquals(InputRouter.Mode.ENGLISH, state.mode());
    state.select("missing"); assertEquals("symbols", state.active());
  }
  @Test public void languageAndReturnLinksDoNotDependOnNavigationHistory()
  {
    assertEquals("en_fn", PageConfig.languageTarget("zh_fn"));
    assertEquals("zh_fn", PageConfig.languageTarget("en_fn"));
    assertEquals("zh", PageConfig.languageTarget("pinyin"));
    assertEquals("greek", PageConfig.returnTarget("greek_fn"));
    assertEquals("zh", PageConfig.returnTarget("clipboard"));
  }
  @Test public void phoneticShortcutIsConfiguredIndependentlyOfFnAndCycling()
  {
    PageConfig config = new PageConfig(null, "zh,en");
    assertEquals("pinyin", config.phoneticEntry);
    config = config.withPhoneticEntry("zhuyin");
    assertTrue(config.enabled.contains("zhuyin"));
    assertEquals("zhuyin", config.move("zhuyin", -1).enable("numeric", true).phoneticEntry);
    assertEquals("zh_fn", config.fn("zh"));
    assertEquals("zhuyin", config.enable("zhuyin", false).phoneticEntry);
    assertEquals("pinyin", new PageConfig(null, null, "broken").phoneticEntry);
  }
}
