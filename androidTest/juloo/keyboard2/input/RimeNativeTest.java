package juloo.keyboard2.input;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileOutputStream;
import android.os.SystemClock;
import java.util.Arrays;
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

  @Test public void privateSchemaDoesNotCreateALearningDatabase() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    RimeData.Paths packaged = RimeData.deploy(context);
    File privateUser = new File(context.getCacheDir(), "rime-private-test");
    assertTrue(privateUser.isDirectory() || privateUser.mkdirs());
    try (RimeEngine engine = new RimeEngine(new RimeData.Paths(packaged.shared, privateUser)))
    {
      engine.setLearning(false);
      assertTrue(engine.processText("nihao").composing);
      assertFalse(engine.confirm().committed.isEmpty());
    }
    for (File file : privateUser.listFiles())
      assertFalse("Private input must not create a user dictionary", file.getName().contains("userdb"));
  }

  @Test public void emptyTextAndSpacePreserveEngineStateAndPartialCommits() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (RimeEngine engine = new RimeEngine(RimeData.deploy(context)))
    {
      engine.setLearning(false);
      EngineResult composing = engine.processText("nihao");
      String first = composing.candidates.get(0);
      EngineResult empty = engine.processText("");
      assertTrue(empty.composing);
      assertEquals(composing.preedit, empty.preedit);
      assertTrue(empty.committed.isEmpty());
      assertEquals(first, engine.space().committed);
      EngineResult rejected = engine.processText("nihao #");
      assertFalse(rejected.handled);
      assertEquals(first, rejected.committed);
      assertTrue(engine.processText("").committed.isEmpty());
      engine.clear();
    }
  }

  @Test public void reportWarmNativeLatencyWithoutGuessingADeviceTarget() throws Exception
  {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    long deploymentStarted = SystemClock.elapsedRealtimeNanos();
    RimeData.Paths paths = RimeData.deploy(context);
    long deploymentMicros = (SystemClock.elapsedRealtimeNanos() - deploymentStarted) / 1000;
    long opened = SystemClock.elapsedRealtimeNanos();
    long openMicros;
    long[] samples = new long[50];
    try (RimeEngine engine = new RimeEngine(paths))
    {
      openMicros = (SystemClock.elapsedRealtimeNanos() - opened) / 1000;
      engine.setLearning(false);
      for (int i = 0; i < samples.length; i++)
      {
        long began = SystemClock.elapsedRealtimeNanos();
        assertTrue(engine.processText("nihao").composing);
        assertFalse(engine.confirm().committed.isEmpty());
        samples[i] = (SystemClock.elapsedRealtimeNanos() - began) / 1000;
      }
    }
    Arrays.sort(samples);
    org.json.JSONObject report = new org.json.JSONObject();
    report.put("api", android.os.Build.VERSION.SDK_INT);
    report.put("abi", android.os.Build.SUPPORTED_ABIS[0]);
    report.put("deploymentVerificationMicros", deploymentMicros);
    report.put("runtimeOpenMicros", openMicros);
    report.put("warmComposeAndConfirmP50Micros", samples[25]);
    report.put("warmComposeAndConfirmP95Micros", samples[47]);
    report.put("testProcessPssKiB", android.os.Debug.getPss());
    report.put("scope", "Debug APK on emulator; JNI operation latency, not physical touch-to-editor latency");
    File directory = context.getExternalFilesDir("test-results");
    assertTrue(directory.isDirectory() || directory.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(directory, "native-metrics.json")))
    {
      out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
  }
}
