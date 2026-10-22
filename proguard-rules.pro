-keep public class juloo.cdict.* {
  public protected private *;
}

# JNI resolves these names directly, including the EngineResult constructor.
-keep class juloo.keyboard2.input.RimeEngine { *; }
-keep class juloo.keyboard2.input.EngineResult { *; }
