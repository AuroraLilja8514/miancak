package juloo.keyboard2;
import org.junit.Test;
import static org.junit.Assert.*;
public final class EditorGestureTest
{
  private EditorGesture gesture(){return new EditorGesture(100,100,20,3);}
  @Test public void tapAndEightExactDirections()
  {
    assertEquals(0,gesture().slot());
    int[][] points={{0,0},{-40,-40},{40,-40},{-40,40},{40,40},{-40,0},{40,0},{0,-40},{0,40}};
    for(int i=1;i<9;i++)
    {EditorGesture g=gesture();g.move(100+points[i][0],100+points[i][1]);assertEquals(i,g.slot());assertEquals(Gesture.Name.Swipe,g.name());}
  }
  @Test public void circlesAndRoundtripUseExistingRecognizer()
  {
    EditorGesture g=gesture();g.move(100,60);g.move(140,100);g.move(100,140);g.move(60,100);g.move(100,100);
    assertEquals(Gesture.Name.Circle,g.name());assertEquals(0,g.slot());
    g=gesture();g.move(100,60);g.move(60,100);g.move(100,140);g.move(140,100);g.move(100,100);
    assertEquals(Gesture.Name.Anticircle,g.name());assertEquals(9,g.slot());
    g=gesture();g.move(140,60);g.move(100,100);assertEquals(Gesture.Name.Roundtrip,g.name());assertEquals(2,g.slot());
  }
  @Test public void sampledCirclesIgnoreRepeatedSectorsAndKeepTheirDirection()
  {
    for(int sign:new int[]{1,-1})
    {
      EditorGesture g=gesture();
      int[][] points={{100,100},{100,60},{100+sign*40,100},{100,140},{100-sign*40,100},{100,100}};
      for(int n=1;n<points.length;n++)for(int step=0;step<=10;step++)
      {
        float x=points[n-1][0]+(points[n][0]-points[n-1][0])*step/10f;
        float y=points[n-1][1]+(points[n][1]-points[n-1][1])*step/10f;
        g.move(x,y);g.move(x,y);
      }
      assertEquals(sign==1?Gesture.Name.Circle:Gesture.Name.Anticircle,g.name());assertEquals(sign==1?0:9,g.slot());
    }
  }

  @Test public void cancelledRotationRetainsTheEffectiveActionLikePointers()
  {
    EditorGesture g=gesture();g.move(100,60);g.move(140,100);g.move(140,80);g.move(100,100);
    assertEquals(Gesture.Name.Circle,g.name());assertEquals(0,g.slot());
    g=gesture();g.move(100,60);g.move(60,100);g.move(60,80);g.move(100,100);
    assertEquals(Gesture.Name.Anticircle,g.name());assertEquals(9,g.slot());
  }

}
