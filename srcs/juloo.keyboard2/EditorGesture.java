package juloo.keyboard2;

/** Selects logical positions, including empty ones, using the real keyboard's
 * compass/distance/circle recognizer without executing actions or repeating. */
public final class EditorGesture
{
  private final float x, y, distance;
  private final int sensitivity;
  private Gesture gesture;
  private Gesture.Name effective = Gesture.Name.None;

  public EditorGesture(float x, float y, float distance, int sensitivity)
  {
    this.x = x; this.y = y;
    this.distance = distance; this.sensitivity = sensitivity;
  }

  public void move(float tx, float ty)
  {
    float dx = tx - x, dy = ty - y;
    if (Math.abs(dx) + Math.abs(dy) < distance)
    {
      if (gesture != null && gesture.is_in_progress())
      {
        gesture.moved_to_center();
        rememberSelection();
      }
      return;
    }
    int direction = ((int)((Math.atan2(dy, dx) + Math.PI) * 8 / Math.PI) + 12) % 16;
    if (gesture == null)
    { gesture = new Gesture(direction, sensitivity); effective = Gesture.Name.Swipe; }
    // Android sends many samples in the same angular sector. The recognizer
    // accepts direction changes, not zero-angle updates (which otherwise
    // cancel a clockwise rotation). Empty slots still remain selectable.
    else if (direction != gesture.current_direction())
    { gesture.changed_direction(direction); rememberSelection(); }
  }

  private void rememberSelection()
  {
    Gesture.Name current = gesture.get_gesture();
    // Pointers retains ptr.value after a recognizer reversal/cancellation.
    // Match that effective action instead of silently selecting the center.
    if (current != Gesture.Name.None) effective = current;
  }

  public Gesture.Name name() { return effective; }

  public int slot()
  {
    switch (name())
    {
      case Circle: return 0;
      case Anticircle: return 9;
      case Swipe: case Roundtrip: return Pointers.DIRECTION_TO_INDEX[gesture.current_direction()];
      default: return 0;
    }
  }
}
