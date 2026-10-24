package juloo.keyboard2;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Overlays may scroll, but cannot change the base keyboard's allocated height. */
public final class KeyboardSurface extends FrameLayout
{
  public KeyboardSurface(Context context, AttributeSet attrs) { super(context, attrs); }

  @Override protected void onMeasure(int widthSpec, int heightSpec)
  {
    View base = getChildAt(0);
    measureChild(base, widthSpec, heightSpec);
    int width = resolveSize(base.getMeasuredWidth(), widthSpec);
    int height = resolveSize(base.getMeasuredHeight(), heightSpec);
    int contentHeight = Math.max(0, height - (base instanceof Keyboard2View ? ((Keyboard2View)base).systemBottomInset() : 0));
    for (int i = 1; i < getChildCount(); i++)
    {
      View overlay = getChildAt(i);
      if (overlay.getVisibility() != GONE)
        overlay.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY));
    }
    setMeasuredDimension(width, height);
  }
}
