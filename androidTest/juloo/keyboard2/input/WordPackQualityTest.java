package juloo.keyboard2.input;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Same frontend schema, separate empty users, real JNI; data adoption is gated. */
@RunWith(AndroidJUnit4.class)
public final class WordPackQualityTest
{
  private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final Context tests = InstrumentationRegistry.getInstrumentation().getContext();

  @Test public void comparePinnedPureWordDataWithoutChangingTheSelectedDictionary() throws Exception
  {
    RimeData.Paths bundled = RimeData.bundled(app);
    JSONObject report = new JSONObject().put("api", android.os.Build.VERSION.SDK_INT)
      .put("abi", android.os.Build.SUPPORTED_ABIS[0])
      .put("scope", "Debug APK / emulator / fresh user / learning disabled; same MIANCAK schema; JNI latency excludes touch; no physical-device guarantee")
      .put("criteria", "70 core: top1 >=80%, top5 >=95%; candidate must not regress vs baseline. Six long sentences diagnostic; <=512MiB process PSS, <=180s cold deploy, <=200ms core JNI P95.")
      .put("provenance", new JSONObject(read(tests.getAssets().open("quality/provenance.json"))));
    File root = new File(app.getCacheDir(), "word-pack-quality");
    remove(root); assertTrue(root.mkdirs());
    try
    {
      JSONObject baseline = measure("baseline", root, bundled);
      report.put("baseline", baseline);
      JSONObject candidate = measure("candidate", root, bundled);
      report.put("candidate", candidate);
      JSONObject core = measure("core", root, bundled);
      report.put("core", core);
      save(report);
      assertTrue("Core top1 below full pack", core.getInt("coreTop1") >= candidate.getInt("coreTop1"));
      assertTrue("Core top5 below full pack", core.getInt("coreTop5") >= candidate.getInt("coreTop5"));
      assertTrue("Core long-sentence result below full pack", core.getInt("sentenceTop1") >= candidate.getInt("sentenceTop1"));
      assertTrue("Core must reduce deployed bytes", core.getLong("deployedBytes") < candidate.getLong("deployedBytes"));
      assertTrue("Core resource budget", core.getLong("coldDeployMicros") <= 180000000 && core.getLong("processPssKiB") <= 512 * 1024 && core.getLong("coreInputP95Micros") <= 200000);
      int n = candidate.getInt("coreCount"); assertEquals(70, n);
      assertTrue("Core top1: " + candidate, candidate.getInt("coreTop1") * 100 >= n * 80);
      assertTrue("Core top5: " + candidate, candidate.getInt("coreTop5") * 100 >= n * 95);
      assertTrue("Top1 regressed", candidate.getInt("coreTop1") >= baseline.getInt("coreTop1"));
      assertTrue("Top5 regressed", candidate.getInt("coreTop5") >= baseline.getInt("coreTop5"));
      assertTrue("PSS exceeds budget", candidate.getLong("processPssKiB") <= 512 * 1024);
      assertTrue("Cold deployment exceeds budget", candidate.getLong("coldDeployMicros") <= 180000000);
      assertTrue("Core JNI P95 exceeds budget", candidate.getLong("coreInputP95Micros") <= 200000);
    }
    finally { save(report); remove(root); }
  }

  private JSONObject measure(String name, File root, RimeData.Paths bundled) throws Exception
  {
    File shared = new File(root, name), user = new File(root, name + "-user");
    assertTrue(shared.mkdir()); assertTrue(user.mkdir());
    for (String schema : Arrays.asList("default.yaml", "miancak_pinyin.schema.yaml", "miancak_pinyin_private.schema.yaml"))
      try (InputStream in = new FileInputStream(new File(bundled.shared, schema))) { copy(in, new File(shared, schema)); }
    try (InputStream in = tests.getAssets().open("quality/" + name + ".dict.yaml"))
    { copy(in, new File(shared, "pinyin_simp.dict.yaml")); }
    JSONArray samples = new JSONArray(); ArrayList<Long> latencies = new ArrayList<>();
    int core = 0, top1 = 0, top5 = 0, sentences = 0, sentenceTop1 = 0;
    long started = SystemClock.elapsedRealtimeNanos(), deploy, observedPss = 0;
    try (RimeEngine engine = new RimeEngine(new RimeData.Paths(shared, user), true))
    {
      deploy = (SystemClock.elapsedRealtimeNanos() - started) / 1000;
      engine.setLearning(false); observedPss = android.os.Debug.getPss();
      try (BufferedReader corpus = new BufferedReader(new InputStreamReader(tests.getAssets().open("quality/corpus.tsv"), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = corpus.readLine()) != null)
        {
          if (line.isEmpty() || line.startsWith("#")) continue;
          String[] fields = line.split("\t", -1); assertEquals(line, 3, fields.length);
          Set<String> expected = new HashSet<>(Arrays.asList(fields[2].split("\\|")));
          engine.clear(); long began = SystemClock.elapsedRealtimeNanos();
          EngineResult result = engine.processText(fields[1]);
          long micros = (SystemClock.elapsedRealtimeNanos() - began) / 1000;
          observedPss = Math.max(observedPss, android.os.Debug.getPss());
          List<String> actual = new ArrayList<>();
          for (String candidate : result.candidates) actual.add(result.committed + candidate);
          int rank = -1;
          for (int i = 0; i < actual.size(); i++) if (expected.contains(actual.get(i))) { rank = i + 1; break; }
          String confirmed = result.committed + engine.confirm().committed;
          if (!actual.isEmpty()) assertEquals("Confirm must agree with actual first candidate", actual.get(0), confirmed);
          samples.put(new JSONObject().put("group", fields[0]).put("code", fields[1]).put("expected", new JSONArray(expected))
              .put("actual", new JSONArray(actual)).put("preedit", result.preedit).put("firstConfirmed", confirmed)
              .put("rank", rank).put("inputMicros", micros));
          if (fields[0].equals("sentence")) { sentences++; if (rank == 1) sentenceTop1++; }
          else { core++; if (rank == 1) top1++; if (rank > 0 && rank <= 5) top5++; latencies.add(micros); }
        }
      }
    }
    Collections.sort(latencies);
    return new JSONObject().put("name", name).put("coreCount", core).put("coreTop1", top1).put("coreTop5", top5)
      .put("sentenceCount", sentences).put("sentenceTop1", sentenceTop1).put("coldDeployMicros", deploy)
      .put("coreInputP50Micros", latencies.get(latencies.size() / 2)).put("coreInputP95Micros", latencies.get((latencies.size() * 95 - 1) / 100))
      .put("processPssKiB", observedPss).put("deployedBytes", bytes(shared) + bytes(user)).put("samples", samples);
  }
  private void save(JSONObject report) throws Exception
  {
    File dir = new File(app.getFilesDir(), "test-results"); assertTrue(dir.isDirectory() || dir.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(dir, "word-pack-quality.json")))
    { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
  }
  private static void copy(InputStream in, File target) throws Exception
  { try (FileOutputStream out = new FileOutputStream(target)) { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) >= 0) out.write(b, 0, n); } }
  private static String read(InputStream in) throws Exception
  { try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int n; while ((n = input.read(b)) >= 0) out.write(b, 0, n); return out.toString("UTF-8"); } }
  private static long bytes(File f)
  { if (!f.isDirectory()) return f.length(); long size = 0; for (File c : f.listFiles()) size += bytes(c); return size; }
  private static void remove(File f) throws Exception
  { if (!f.exists()) return; if (f.isDirectory()) for (File c : f.listFiles()) remove(c); if (!f.delete()) throw new IOException("Cannot remove quality fixture: " + f); }
}
