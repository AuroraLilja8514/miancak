package juloo.keyboard2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;

/** Test-only adapter. File and Handler construction are deferred to the daemon. */
final class AndroidProgressEvidence
{
  private static final ProgressEvidence.Guard GUARD = new ProgressEvidence.Guard();

  static ProgressEvidence start(Context context, ProgressEvidence.Run run)
  {
    try { return startWorker(context, run); }
    catch (Throwable ignored) { return ProgressEvidence.NONE; }
  }

  private static ProgressEvidence startWorker(Context context, ProgressEvidence.Run run)
  {
    ProgressEvidence evidence = new ProgressEvidence(run, new ProgressEvidence.Clock() {
      public long now() { return SystemClock.elapsedRealtime(); }
      public void sleep(long ms) { SystemClock.sleep(ms); }
    }, new ProgressEvidence.Poster() {
      private Handler handler;
      public boolean post(Runnable callback)
      {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler.post(callback); // Ordinary queue: no async/front-of-queue bypass.
      }
    }, new ProgressEvidence.Sink() {
      private FileOutputStream out;
      public void open(ProgressEvidence.Run selected) throws Exception
      {
        File dir = context.getExternalFilesDir("test-results");
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs()))
          throw new java.io.IOException("External test-results unavailable");
        File file = new File(dir, "progress-" + selected.scenario + "-" + selected.phase + ".jsonl");
        // One artifact per phase; never truncate earlier evidence on a repeat.
        if (!file.createNewFile()) throw new java.io.IOException("Progress evidence already exists");
        out = new FileOutputStream(file);
      }
      public void write(byte[] record) throws Exception { out.write(record); out.flush(); }
      public void close() throws Exception { if (out != null) out.close(); }
    });
    try
    {
      if (GUARD.claim(evidence))
      {
        Thread worker = new Thread(evidence::work, "MIANCAK-test-progress");
        worker.setDaemon(true);
        worker.start();
      }
    }
    catch (Throwable ignored) { /* Startup failure is not an acceptance failure. */ }
    return evidence;
  }

  private AndroidProgressEvidence() {}
}
