package juloo.keyboard2;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import juloo.keyboard2.input.WanxiangSetup;
import juloo.keyboard2.input.WanxiangSetup.Phase;

public final class WanxiangSetupActivity extends Activity
{
  private static final int PICK_BASE = 1, PICK_MODEL = 2;
  private TextView message;
  private ProgressBar progress;
  private Button download, files, cancel;
  private Uri base;
  private final Runnable observer = this::render;

  @Override public void onCreate(Bundle saved)
  {
    super.onCreate(saved);
    setTitle(R.string.wanxiang_setup_title);
    setContentView(R.layout.wanxiang_setup);
    message = findViewById(R.id.wanxiang_setup_status);
    progress = findViewById(R.id.wanxiang_setup_progress);
    download = findViewById(R.id.wanxiang_setup_download);
    files = findViewById(R.id.wanxiang_setup_files);
    cancel = findViewById(R.id.wanxiang_setup_cancel);
    if (saved != null) base = saved.getParcelable("base", Uri.class);
    download.setOnClickListener(view -> WanxiangSetup.download(this));
    files.setOnClickListener(view -> pick(PICK_BASE));
    cancel.setOnClickListener(view -> WanxiangSetup.cancel());
  }

  @Override public void onStart() { super.onStart(); WanxiangSetup.check(this); WanxiangSetup.observe(observer); }
  @Override public void onStop() { WanxiangSetup.forget(observer); super.onStop(); }
  @Override public void onResume() { super.onResume(); WanxiangSetup.resume(this); }
  @Override public void onPause() { WanxiangSetup.pause(this); super.onPause(); }
  @Override public void onSaveInstanceState(Bundle out) { out.putParcelable("base", base); super.onSaveInstanceState(out); }

  private void pick(int resource)
  {
    if (resource == PICK_BASE) base = null;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
    intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
    try
    {
      startActivityForResult(Intent.createChooser(intent, getString(resource == PICK_BASE
          ? R.string.wanxiang_setup_pick_base : R.string.wanxiang_setup_pick_model)), resource);
    }
    catch (android.content.ActivityNotFoundException unavailable) { message.setText(R.string.wanxiang_setup_failed); }
  }

  @Override protected void onActivityResult(int request, int result, Intent data)
  {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) { base = null; return; }
    if (request == PICK_BASE) { base = data.getData(); pick(PICK_MODEL); }
    if (request == PICK_MODEL && base != null)
    { WanxiangSetup.installFiles(this, base, data.getData()); base = null; }
  }

  private void render()
  {
    WanxiangSetup.Status status = WanxiangSetup.status();
    Phase phase = status.phase;
    String size = Formatter.formatFileSize(this, status.bytes);
    int percent = status.bytes == 0 ? 0 : (int)(100 * status.transferred / status.bytes);
    int description;
    switch (phase)
    {
      case CHECKING: description = R.string.wanxiang_setup_checking; break;
      case NEEDED: description = R.string.wanxiang_setup_needed; break;
      case COPYING: description = R.string.wanxiang_setup_copying; break;
      case UNPACKING: description = R.string.wanxiang_setup_unpacking; break;
      case STAGED: description = R.string.wanxiang_setup_staged; break;
      case STOPPING: description = R.string.wanxiang_setup_stopping; break;
      case VALIDATING: description = R.string.wanxiang_setup_validating; break;
      case READY: description = R.string.wanxiang_setup_ready; break;
      case CANCELLED: description = R.string.wanxiang_setup_cancelled; break;
      case UNAVAILABLE: description = R.string.wanxiang_setup_unavailable; break;
      case INTEGRITY: description = R.string.wanxiang_setup_integrity; break;
      case SPACE: description = R.string.wanxiang_setup_space; break;
      default: description = R.string.wanxiang_setup_failed;
    }
    if (phase == Phase.NEEDED) message.setText(getString(description, status.version, size));
    else if (phase == Phase.COPYING) message.setText(getString(description, percent));
    else message.setText(description);
    progress.setVisibility(status.busy() ? View.VISIBLE : View.GONE);
    progress.setIndeterminate(phase != Phase.COPYING);
    if (phase == Phase.COPYING) progress.setProgress(percent);
    boolean canStart = !status.busy() && phase != Phase.READY && status.bytes > 0;
    download.setVisibility(canStart ? View.VISIBLE : View.GONE);
    download.setText(getString(phase == Phase.NEEDED ? R.string.wanxiang_setup_download : R.string.wanxiang_setup_retry, size));
    files.setVisibility(canStart ? View.VISIBLE : View.GONE);
    cancel.setVisibility(phase == Phase.COPYING || phase == Phase.UNPACKING || phase == Phase.STAGED ? View.VISIBLE : View.GONE);
  }
}
