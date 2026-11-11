package juloo.keyboard2;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class LayoutPresentationTest
{
  private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
  @Test public void symbolsDoNotInheritTextExtrasButChineseKHasPairedBrackets()
  {
    Context c=context();SharedPreferences p=c.getSharedPreferences("presentation-tests",Context.MODE_PRIVATE);p.edit().clear().commit();
    MiancakPreferences.initialize(p);
    for(String key:new String[]{"tab","esc","superscript","subscript"})p.edit().putBoolean("extra_key_"+key,true).commit();
    Config.initGlobalConfig(p,c.getResources(),false,juloo.keyboard2.dict.Dictionaries.instance(c));
    Config.globalConfig().show_numpad=true;Config.globalConfig().add_number_row=true;
    for(String category:juloo.keyboard2.input.SymbolCatalog.categories())
    {
      String scope=juloo.keyboard2.input.SymbolCatalog.scope(category,0);
      KeyboardData actual=LayoutModifier.modify_layout(PageLayouts.base(c.getResources(),scope),false,null,scope);
      assertEquals(4,actual.rows.size());
      for(String key:new String[]{"tab","esc","superscript","subscript"})assertFalse(scope+":"+key,actual.getKeys().containsKey(KeyValue.getKeyByName(key)));
      KeyboardData editor=PageLayouts.forEditor(c,scope,p);
      for(String key:new String[]{"tab","esc","superscript","subscript"})assertFalse(editor.getKeys().containsKey(KeyValue.getKeyByName(key)));
    }
    KeyboardData en=PageLayouts.forEditor(c,"en",p);
    for(String key:new String[]{"tab","esc","superscript","subscript"})assertTrue(key,en.getKeys().containsKey(KeyValue.getKeyByName(key)));
    for(String id:new String[]{"zh","zh_fn"})
    {
      KeyboardData data=PageLayouts.base(c.getResources(),id);KeyboardData.Key k=data.rows.get(1).keys.get(7);
      assertEquals("（",KeyModifier.preview(k.keys[1],false,data.modmap).getString());
      assertEquals("）",KeyModifier.preview(k.keys[2],false,data.modmap).getString());
      assertEquals("(",KeyModifier.preview(k.keys[1],true,data.modmap).getString());
      assertEquals(")",KeyModifier.preview(k.keys[2],true,data.modmap).getString());
    }
  }
  @Test public void actualViewRespectsParentWidthAndSymmetricSafeInsets()
  {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
      try
      {
        Context c=new ContextThemeWrapper(context(),R.style.Light);
        SharedPreferences p=c.getSharedPreferences("preview-measure-tests",Context.MODE_PRIVATE);p.edit().clear().commit();MiancakPreferences.initialize(p);
        Keyboard2View view=new Keyboard2View(c,null,Config.forPreview(c,p));view.setKeyboard(PageLayouts.forEditor(c,"en",p));
        int width=Math.round(240*c.getResources().getDisplayMetrics().density);
        if(android.os.Build.VERSION.SDK_INT>=35)
          view.onApplyWindowInsets(new android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),android.graphics.Insets.of(0,0,32,0)).build());
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST));
        assertEquals(width,view.getMeasuredWidth());
        java.lang.reflect.Field left=Keyboard2View.class.getDeclaredField("_marginLeft"),right=Keyboard2View.class.getDeclaredField("_marginRight");
        left.setAccessible(true);right.setAccessible(true);assertEquals(left.getFloat(view),right.getFloat(view),.01f);
        if(android.os.Build.VERSION.SDK_INT>=35)assertTrue(left.getFloat(view)>=32);
      }
      catch(ReflectiveOperationException error){throw new AssertionError(error);}
    });
  }
}
