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
      view.setPadding(area.left, area.top, area.right, area.bottom);
      view.post(() -> {
        android.view.View focused = view.findFocus();
        if (focused != null) { android.graphics.Rect rect = new android.graphics.Rect(); focused.getDrawingRect(rect); focused.requestRectangleOnScreen(rect, false); }
      });
      return android.view.WindowInsets.CONSUMED;
    });
    root.requestApplyInsets();
  }
}
