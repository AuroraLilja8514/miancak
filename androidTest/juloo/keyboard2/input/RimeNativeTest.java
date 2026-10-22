package juloo.keyboard2.input;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RimeNativeTest
{
  @Test public void realOfflinePinyinSessionAndReinitialization() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RimeData.Paths paths = RimeData.deploy(context);
    for (int attempt = 0; attempt < 2; attempt++)
      try (RimeEngine engine = new RimeEngine(paths))
      {
        engine.setLearning(false);
        EngineResult result = engine.processText("nihao");
        assertTrue(result.composing);
        assertFalse(result.candidates.isEmpty());
        assertTrue("Expected a real Chinese candidate", result.candidates.get(0).matches(".*[\\u3400-\\u9fff].*"));
        String first = result.candidates.get(0);
        result = engine.confirm();
        assertEquals(first, result.committed);
        assertFalse(result.composing);
        result = engine.processText("zhongguo");
        assertTrue(result.composing);
        first = result.candidates.get(0);
        result = engine.select(0);
        if (result.composing)
          result = engine.confirm();
        assertEquals(first, result.committed);
        result = engine.processText("q");
        assertTrue(result.composing);
        assertFalse(engine.backspace().composing);
        engine.processText("ni");
        assertFalse(engine.clear().composing);
      }
  }

  @Test public void deploymentKeepsUnchangedAssetsAndUserData() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RimeData.Paths first = RimeData.deploy(context);
    File dictionary = new File(first.shared, "pinyin_simp.dict.yaml");
    long modified = dictionary.lastModified();
    File marker = new File(first.user, "miancak-test-preserve");
    try (FileOutputStream out = new FileOutputStream(marker))
    {
      out.write("preserve".getBytes(StandardCharsets.UTF_8));
    }
    RimeData.Paths second = RimeData.deploy(context);
    assertEquals(first.shared, second.shared);
    assertEquals(modified, dictionary.lastModified());
    assertTrue(marker.isFile());
    assertTrue(marker.delete());
  }
}
