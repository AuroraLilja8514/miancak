package juloo.keyboard2.input;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageConfigTest
{
  @Test public void defaultsHaveOneSharedTextFamilyAndAllFunctionalPages()
  {
    PageConfig config = new PageConfig(null, null, null, null);
    assertEquals(PageConfig.IDS, config.visible());
    assertEquals("symbols", config.fn(InputRouter.Mode.CHINESE));
    assertEquals("numeric", config.fn(InputRouter.Mode.ENGLISH));
  }
  @Test public void invalidAndDuplicateIdsCannotStrandTheTextProfiles()
  {
    PageConfig config = new PageConfig("greek,greek,missing,en", "greek,missing", "missing", "zh");
    assertEquals(Arrays.asList("greek", "en", "zh"), config.visible());
    assertEquals("", config.chineseFn);
    assertEquals("", config.englishFn);
    assertTrue(config.enable("zh", false).enabled.contains("zh"));
  }
  @Test public void disablingAFnTargetClearsTheTargetAndReorderingPersists()
  {
    PageConfig config = new PageConfig(null, null, null, null).enable("symbols", false).move("numeric", -1);
    assertEquals("", config.chineseFn);
    PageConfig restored = new PageConfig(PageConfig.csv(config.order), PageConfig.csv(config.enabled), config.chineseFn, config.englishFn);
    assertEquals(config.visible(), restored.visible());
    assertEquals("numeric", restored.englishFn);
  }
  @Test public void enteringFnTargetFromAnyRouteHasTheSameIndicator()
  {
    PageConfig config = new PageConfig(null, null, "greek", "numeric");
    PageState state = new PageState();
    state.select("greek");
    assertTrue(state.fnActive(config, InputRouter.Mode.CHINESE));
    assertEquals("zh", state.toggle("greek", InputRouter.Mode.CHINESE));
    state.select("numeric");
    assertFalse(state.fnActive(config, InputRouter.Mode.CHINESE));
    assertTrue(state.fnActive(config, InputRouter.Mode.ENGLISH));
    state.followMode(InputRouter.Mode.ENGLISH);
    assertEquals("numeric", state.active());
    state.reset(InputRouter.Mode.ENGLISH);
    assertEquals("en", state.active());
  }
}
