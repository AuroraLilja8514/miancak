package juloo.keyboard2;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Animatable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.dict.DictionariesActivity;
import juloo.keyboard2.R;

public class LauncherActivity extends Activity implements Handler.Callback
{
  /** Periodically restart the animations. */
  List<Animatable> _animations;
  Handler _handler;

  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.launcher_activity);
    // Android 15 enforces edge-to-edge for this activity. Include the IME
    // inset so the real try-here field scrolls above the keyboard as it opens.
    if (VERSION.SDK_INT >= 35)
    {
      getWindow().setDecorFitsSystemWindows(false);
      View content = ((android.view.ViewGroup)findViewById(android.R.id.content)).getChildAt(0);
      content.setFitsSystemWindows(false);
      content.setOnApplyWindowInsetsListener((view, insets) -> {
        android.graphics.Insets bars = insets.getInsets(
            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
        android.view.ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof android.view.ViewGroup.MarginLayoutParams)
        {
          android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams)raw;
          if (margins.leftMargin != bars.left || margins.topMargin != bars.top
              || margins.rightMargin != bars.right || margins.bottomMargin != bars.bottom)
          {
            margins.setMargins(bars.left, bars.top, bars.right, bars.bottom);
            view.setLayoutParams(margins);
          }
        }
        view.post(() -> {
          View focused = view.findFocus();
          if (focused != null)
          {
            android.graphics.Rect rect = new android.graphics.Rect();
            focused.getDrawingRect(rect); focused.requestRectangleOnScreen(rect, false);
          }
        });
        return android.view.WindowInsets.CONSUMED;
      });
      content.requestApplyInsets();
    }
    _handler = new Handler(getMainLooper(), this);
  }

  @Override
  public void onStart()
  {
    super.onStart();
    _animations = new ArrayList<Animatable>();
    _animations.add(find_anim(R.id.launcher_anim_swipe));
    _animations.add(find_anim(R.id.launcher_anim_round_trip));
    _animations.add(find_anim(R.id.launcher_anim_circle));
    _handler.removeMessages(0);
    _handler.sendEmptyMessageDelayed(0, 500);
  }

  @Override
  public boolean handleMessage(Message _msg)
  {
    for (Animatable anim : _animations)
      anim.start();
    _handler.sendEmptyMessageDelayed(0, 3000);
    return true;
  }

  @Override
  public final boolean onCreateOptionsMenu(Menu menu)
  {
    getMenuInflater().inflate(R.menu.launcher_menu, menu);
    return true;
  }

  @Override
  public final boolean onOptionsItemSelected(MenuItem item)
  {
    if (item.getItemId() == R.id.btnLaunchSettingsActivity)
    {
      Intent intent = new Intent(LauncherActivity.this, SettingsActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    }
    return super.onOptionsItemSelected(item);
  }

  public void launch_imesettings(View _btn)
  {
    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
  }

  public void launch_imepicker(View v)
  {
    InputMethodManager imm =
      (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
    imm.showInputMethodPicker();
  }

  public void launch_dictionaries_activity(View v)
  {
    startActivity(new Intent(this, DictionariesActivity.class));
  }

  Animatable find_anim(int id)
  {
    ImageView img = (ImageView)findViewById(id);
    return (Animatable)img.getDrawable();
  }

}
