package juloo.keyboard2.input;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Isolated catalog and real native engine, complementary to ordinary fresh
 * install/upgrade tests. The injected failure affects only APK preparation. */
@RunWith(AndroidJUnit4.class)
public class RimeDeploymentRecoveryTest
{
  @Test public void failedPreparationKeepsPatchedInputAndExplicitRetryPublishesOnlyAfterProbe() throws Exception
  {
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File root = new File(app.getCacheDir(), "rime-deployment-" + UUID.randomUUID()); assertTrue(root.mkdir());
    Context isolated = new ContextWrapper(app) {
      @Override public Context getApplicationContext() { return this; }
      @Override public File getFilesDir() { return root; }
    };
    try
    {
      WordTableStore first = new WordTableStore(isolated);
      byte[] yaml = "# synthetic retained original\npatch: {menu/page_size: 3}\n".getBytes(StandardCharsets.UTF_8);
      first.importConfigs(Collections.singletonList(new WordTableStore.Source() {
        public String filename() { return "default.custom.yaml"; }
        public InputStream open() { return new ByteArrayInputStream(yaml); }
      }));
      String patch = first.view().patches.get(0).id;
      first.apply(WordTableStore.BUNDLED, Collections.emptyList(), Collections.singletonList(patch));
      String before = first.view().selection.slot;
      File original = new File(root, "rime/word-tables/config-patches/" + patch + "/source.custom.yaml");
      File learningMarker = new File(root, "rime/user/retained-synthetic-original.txt");
      byte[] kept = "retained synthetic user bytes".getBytes(StandardCharsets.UTF_8); Files.write(learningMarker.toPath(), kept);
      boolean[] fail = {true}; int[] preparations = {0};
      WordTableStore broken = new WordTableStore(root, new WordTableStore.Packaged() {
        public RimeData.Paths identify() throws IOException { return RimeData.identity(isolated); }
        public RimeData.Paths prepare() throws IOException {
          preparations[0]++; if (fail[0]) throw new IOException("synthetic storage interruption"); return RimeData.bundled(isolated);
        }
        public InputStream open(String name) throws IOException { return isolated.getAssets().open(name); }
      });
      RimeData.Paths retained = broken.currentPaths(); assertTrue(retained.retained);
      try (RimeEngine engine = new RimeEngine(retained)) {
        engine.setLearning(false); assertTrue(engine.usesRetainedData());
        assertEquals(3, engine.processText("shi").candidates.size()); assertFalse(engine.confirm().committed.isEmpty());
      }
      File state = new File(root, "rime/word-tables/selection.json"); byte[] deferred = Files.readAllBytes(state.toPath());
      try { broken.retryDeployment(); fail("failed retry accepted"); } catch (IOException expected) { assertTrue(expected.getMessage().contains("synthetic")); }
      assertArrayEquals(deferred, Files.readAllBytes(state.toPath())); assertEquals(before, broken.view().selection.slot);
      assertTrue(broken.currentPaths().retained); assertEquals(2, preparations[0]);
      fail[0] = false; broken.retryDeployment();
      assertNotEquals(before, broken.view().selection.slot); assertEquals(Collections.singletonList(patch), broken.view().selection.patches);
      assertEquals("", broken.view().recovery); assertFalse(broken.currentPaths().retained);
      try (RimeEngine engine = new RimeEngine(broken.currentPaths())) {
        engine.setLearning(false); assertFalse(engine.usesRetainedData());
        assertEquals(3, engine.processText("shi").candidates.size()); assertFalse(engine.confirm().committed.isEmpty());
      }
      assertArrayEquals(yaml, Files.readAllBytes(original.toPath())); assertArrayEquals(kept, Files.readAllBytes(learningMarker.toPath()));
    }
    finally { remove(root); }
  }
  private static void remove(File file) throws IOException
  {
    if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); }
    if (!file.delete()) throw new IOException("Cannot remove isolated deployment fixture");
  }
}
