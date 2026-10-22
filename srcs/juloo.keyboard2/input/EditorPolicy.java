package juloo.keyboard2.input;

import android.os.Build;
import android.os.UserManager;
import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

public final class EditorPolicy
{
  public final boolean engineAllowed;
  public final boolean learning;
  private EditorPolicy(boolean allowed, boolean learning) { this.engineAllowed = allowed; this.learning = learning; }
  public static EditorPolicy from(EditorInfo info, Context context)
  {
    int type = info.inputType & InputType.TYPE_MASK_CLASS;
    int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
    boolean password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
      || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
      || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    boolean unlocked = Build.VERSION.SDK_INT < 24
      || ((UserManager)context.getSystemService(Context.USER_SERVICE)).isUserUnlocked();
    boolean allowed = type == InputType.TYPE_CLASS_TEXT && !password && unlocked;
    boolean learning = allowed
      && (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
      && (info.imeOptions & 0x1000000 /* IME_FLAG_NO_PERSONALIZED_LEARNING, API 26 */) == 0;
    return new EditorPolicy(allowed, learning);
  }
}
