package juloo.keyboard2;

/** Insets for the activities Android 15 now displays edge-to-edge. */
public final class ActivityInsets
{
  private ActivityInsets() {}
  public static void install(android.app.Activity activity, android.view.View root)
  {
    if (android.os.Build.VERSION.SDK_INT < 35) return;
    activity.getWindow().setDecorFitsSystemWindows(false);
    root.setFitsSystemWindows(false);
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      android.graphics.Insets area = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
      android.view.ViewGroup.LayoutParams raw = view.getLayoutParams();
      if (raw instanceof android.view.ViewGroup.MarginLayoutParams)
      {
        android.view.ViewGroup.MarginLayoutParams margins = (android.view.ViewGroup.MarginLayoutParams)raw;
        if (margins.leftMargin != area.left || margins.topMargin != area.top
            || margins.rightMargin != area.right || margins.bottomMargin != area.bottom)
        {
          margins.setMargins(area.left, area.top, area.right, area.bottom);
          view.setLayoutParams(margins);
        }
      }
      view.post(() -> {
        android.view.View focused = view.findFocus();
        if (focused != null) { android.graphics.Rect rect = new android.graphics.Rect(); focused.getDrawingRect(rect); focused.requestRectangleOnScreen(rect, false); }
      });
      return android.view.WindowInsets.CONSUMED;
    });
    root.requestApplyInsets();
  }
}
