package juloo.keyboard2.input;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Explicit setup owns networking; no engine or typing entry point calls it. */
public final class WanxiangSetup
{
  public enum Phase { CHECKING, NEEDED, COPYING, UNPACKING, STAGED, STOPPING, VALIDATING, READY, CANCELLED,
                      UNAVAILABLE, INTEGRITY, SPACE, FAILED }
  public static final class Status
  {
    public final Phase phase;
    public final String version;
    public final long bytes, transferred;
    public final boolean earlierPack;
    Status(Phase phase, String version, long bytes, long transferred, boolean earlierPack)
    { this.phase = phase; this.version = version; this.bytes = bytes; this.transferred = transferred; this.earlierPack = earlierPack; }
    public boolean busy()
    {
      return phase == Phase.CHECKING || phase == Phase.COPYING || phase == Phase.UNPACKING
          || phase == Phase.STAGED || phase == Phase.STOPPING || phase == Phase.VALIDATING;
    }
  }

  private static final Handler UI = new Handler(Looper.getMainLooper());
  private static final List<Runnable> listeners = new ArrayList<>();
  private static Status status = new Status(Phase.NEEDED, "", 0, 0, false);
  private static Thread transfer;
  private static Context foreground;
  private static WanxiangInstall.Staged awaitingActivation;

  public static Status status() { return status; }
  public static void observe(Runnable listener) { listeners.add(listener); listener.run(); }
  public static void forget(Runnable listener) { listeners.remove(listener); }
  public static void resume(Context activity)
  { foreground = activity; activateIfVisible(); }
  public static void pause(Context activity)
  { if (foreground == activity) foreground = null; }

  public static void check(Context context)
  {
    if (status.busy() || status.bytes != 0) return;
    Context app = context.getApplicationContext();
    update(Phase.CHECKING, 0);
    worker(() -> {
      try
      {
        WanxiangInstall install = WanxiangInstall.forApp(app);
        try { install.reclaimInterruptedModel(); }
        catch (IOException | RuntimeException failure) { android.util.Log.w("MIANCAK-Rime", "Resource staging cleanup deferred"); }
        boolean ready = install.installed();
        boolean earlierPack = !ready && install.hasEarlierBundle();
        UI.post(() -> publish(new Status(ready ? Phase.READY : Phase.NEEDED, install.version, install.transferBytes, 0, earlierPack)));
      }
      catch (IOException invalid) { UI.post(() -> update(Phase.FAILED, 0)); }
    });
  }

  public static void download(Context context)
  {
    if (status.busy() || status.phase == Phase.READY) return;
    android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
    if (connectivity != null && connectivity.getActiveNetwork() == null)
    { update(Phase.UNAVAILABLE, 0); return; }
    begin(context, null, null);
  }
  public static void installFiles(Context context, Uri base, Uri model) { begin(context, base, model); }

  private static void begin(Context context, Uri base, Uri model)
  {
    if (status.busy() || status.phase == Phase.READY) return;
    Context app = context.getApplicationContext();
    update(Phase.COPYING, 0);
    transfer = worker(() -> {
      try
      {
        WanxiangInstall install = WanxiangInstall.forApp(app);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        WanxiangInstall.Source source = base == null
            ? artifact -> WanxiangDownload.open(artifact, deadline)
            : artifact -> localFile(app, artifact == install.base ? base : model, deadline);
        int[] previousPercent = {-1};
        WanxiangInstall.Staged staged = install.prepare(source, name -> app.getAssets().open("rime/" + name), bytes -> {
          int percent = (int)(100 * bytes / install.transferBytes);
          if (percent != previousPercent[0])
          {
            previousPercent[0] = percent;
            UI.post(() -> { if (status.phase == Phase.COPYING)
              publish(new Status(Phase.COPYING, install.version, install.transferBytes, bytes, status.earlierPack)); });
          }
        }, () -> UI.post(() -> { if (status.phase == Phase.COPYING) update(Phase.UNPACKING, status.bytes); }));
        UI.post(() -> finishTransfer(staged));
      }
      catch (IOException invalid)
      {
        Phase phase = phaseFor(invalid, base == null);
        UI.post(() -> { transfer = null; update(status.phase == Phase.STOPPING ? Phase.CANCELLED : phase, 0); });
      }
      catch (RuntimeException invalid)
      { UI.post(() -> { transfer = null; update(Phase.FAILED, 0); }); }
    });
  }

  private static Phase phaseFor(IOException failure, boolean network)
  {
    if (failure instanceof WanxiangInstall.InsufficientSpace) return Phase.SPACE;
    if (failure instanceof WanxiangInstall.StagingBusy) return Phase.FAILED;
    if (failure instanceof WanxiangInstall.IntegrityFailure || failure instanceof java.util.zip.ZipException) return Phase.INTEGRITY;
    if (Thread.currentThread().isInterrupted()) return Phase.CANCELLED;
    return network ? Phase.UNAVAILABLE : Phase.FAILED;
  }

  public static void cancel()
  {
    if (transfer != null && (status.phase == Phase.COPYING || status.phase == Phase.UNPACKING))
    { update(Phase.STOPPING, status.transferred); transfer.interrupt(); }
    else if (status.phase == Phase.STAGED)
    {
      WanxiangInstall.Staged staged = awaitingActivation; awaitingActivation = null;
      update(Phase.STOPPING, status.bytes); discard(staged, Phase.CANCELLED);
    }
  }

  private static void finishTransfer(WanxiangInstall.Staged staged)
  {
    transfer = null;
    if (status.phase == Phase.STOPPING) { discard(staged, Phase.CANCELLED); return; }
    if (staged == null) { update(Phase.READY, status.bytes); return; }
    awaitingActivation = staged;
    update(Phase.STAGED, status.bytes);
    activateIfVisible();
  }

  /** Do not close the IME in another app just because a background transfer finished. */
  private static void activateIfVisible()
  {
    if (foreground == null || awaitingActivation == null) return;
    Context app = foreground.getApplicationContext();
    WanxiangInstall.Staged staged = awaitingActivation; awaitingActivation = null;
    update(Phase.VALIDATING, status.bytes);
    WordTableRuntime.activateResources(app, () -> staged.activate(WanxiangInstall::verifyNative),
        failure -> discard(staged, failure.isEmpty() ? Phase.READY : Phase.FAILED));
  }

  private static void discard(WanxiangInstall.Staged staged, Phase next)
  {
    if (staged == null) { update(next, status.bytes); return; }
    worker(() -> {
      try { staged.close(); }
      catch (IOException failure) { android.util.Log.w("MIANCAK-Rime", "Resource staging cleanup deferred"); }
      UI.post(() -> update(next, next == Phase.READY ? status.bytes : 0));
    });
  }

  private static Thread worker(Runnable operation)
  { Thread thread = new Thread(operation, "MIANCAK-ResourceSetup"); thread.start(); return thread; }

  private static void update(Phase phase, long transferred)
  { publish(new Status(phase, status.version, status.bytes, transferred, status.earlierPack)); }

  private static void publish(Status next)
  { status = next; for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }

  private static InputStream localFile(Context context, Uri uri, long deadline) throws IOException
  {
    WanxiangDownload.checkDeadline(deadline);
    if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("File must be selected through Android documents");
    InputStream stream = context.getContentResolver().openInputStream(uri);
    if (stream == null) throw new IOException("Cannot open selected resource");
    return WanxiangDownload.bound(stream, deadline);
  }

  private WanxiangSetup() {}
}
