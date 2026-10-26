package juloo.keyboard2;

/** Selects logical positions, including empty ones, using the real keyboard's
 * compass/distance/circle recognizer without executing actions or repeating. */
public final class EditorGesture
{
  private final float x,y,distance; private final int sensitivity;
  private Gesture gesture;
  public EditorGesture(float x,float y,float distance,int sensitivity)
  { this.x=x;this.y=y;this.distance=distance;this.sensitivity=sensitivity; }
  public void move(float tx,float ty)
  {
    float dx=tx-x,dy=ty-y;
    if(Math.abs(dx)+Math.abs(dy)<distance)
    { if(gesture!=null && gesture.is_in_progress())gesture.moved_to_center(); return; }
    int direction=((int)((Math.atan2(dy,dx)+Math.PI)*8/Math.PI)+12)%16;
    if(gesture==null)gesture=new Gesture(direction,sensitivity); else gesture.changed_direction(direction);
  }
  public Gesture.Name name(){return gesture==null?Gesture.Name.None:gesture.get_gesture();}
  public int slot()
  {
    switch(name())
    {
      case Circle:return 0;case Anticircle:return 9;
      case Swipe:case Roundtrip:return Pointers.DIRECTION_TO_INDEX[gesture.current_direction()];
      default:return 0;
    }
  }
}
