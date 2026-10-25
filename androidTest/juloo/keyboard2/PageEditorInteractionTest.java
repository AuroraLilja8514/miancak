package juloo.keyboard2;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import juloo.keyboard2.input.SymbolState;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Invoked in the same instrumentation as Fn, symbol and lifecycle tests. */
public final class PageEditorInteractionTest
{
  private static final String[] SLOTS = {"c","nw","ne","sw","se","w","e","n","s"};
  private static final int[][] VECTORS = {{0,0},{-1,-1},{1,-1},{-1,1},{1,1},{-1,0},{1,0},{0,-1},{0,1}};
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private final String pkg = context.getPackageName();
  private final UiDevice device = UiDevice.getInstance(instrumentation);
  private final List<KeyEvent> events = new CopyOnWriteArrayList<>();
  private final JSONArray observations = new JSONArray();
  private String page = "en";

  public void editAndVerify(boolean restartOnly) throws Exception
  {
    device.wakeUp(); device.setOrientationNatural();
    device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1");
    device.executeShellCommand("ime enable " + pkg + "/juloo.keyboard2.Keyboard2");
    device.executeShellCommand("ime set " + pkg + "/juloo.keyboard2.Keyboard2");
    instrumentation.startActivitySync(new Intent(context, ImeTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    resumeReceiver(); choose("en");
    try
    {
      if (!restartOnly)
      {
        openEditor("en");
        for (int d = 0; d < 9; d++) literal("en", 1, 2, d, "C12345678".substring(d, d+1));
        require(By.desc("Shift layer preview")).click();
        literal("en", 1, 2, 0, "转"); literal("en", 1, 2, 7, "上");
        key("en", 1, 2); position(0); shot("editor-eight-directions"); closeDialog();
        key("en", 3, 2); UiObject2 shared = position(7);
        assertFalse(shared.isEnabled()); assertTrue(shared.getText().contains(context.getString(R.string.page_shared_status))); closeDialog();
        device.setOrientationLeft(); orientation(android.content.res.Configuration.ORIENTATION_LANDSCAPE);
        scrollTo("Shift layer preview");
        UiObject2 landscapeShift = device.wait(Until.findObject(By.desc("Shift layer preview")), 15000);
        assertNotNull("Shift preview must remain reachable after rotation", landscapeShift);
        assertTrue(landscapeShift.isChecked()); shot("editor-landscape-draft");
        device.setOrientationNatural(); orientation(android.content.res.Configuration.ORIENTATION_PORTRAIT);
        scrollTo("Shift layer preview");
        UiObject2 portraitShift = device.wait(Until.findObject(By.desc("Shift layer preview")), 15000);
        assertNotNull("Shift preview must remain reachable after rotation", portraitShift);
        assertTrue(portraitShift.isChecked()); save();
        resumeReceiver(); choose("en"); verifyNine();
        openEditor("en"); literal("en", 1, 2, 0, "取消"); require(By.desc("Cancel layout edit")).click();
        resumeReceiver(); choose("en"); clear(); touch(1,2,0); expect("C", "cancel");
        openEditor("en"); key("en",0,0); position(0).click(); pick(R.string.page_empty); save();
        resumeReceiver(); choose("en"); clear(); touch(0,0,0); expect("", "cleared-center");
        openEditor("en"); key("en",0,0); position(0).click(); pick(R.string.page_reset_position); save();
        resumeReceiver(); choose("en"); clear(); touch(0,0,0); expect("q", "position-reset");
        String portable = PageKeyOverrides.export(prefs());
        openEditor("en"); action("en",1,2,5, actionLabel("cursor_left")); save();
        resumeReceiver(); choose("en");
        instrumentation.runOnMainSync(() -> { current().first.setText("abcdef"); current().first.setSelection(6); });
        Point p = point(1,2); assertTrue(device.swipe(p.x,p.y,Math.round(p.x-unit()*2.5f),p.y,24));
        final int[] selection = {6}; long end=SystemClock.uptimeMillis()+10000;
        do { instrumentation.runOnMainSync(() -> selection[0]=current().first.getSelectionStart()); if(selection[0]<=4)break; SystemClock.sleep(30); } while(SystemClock.uptimeMillis()<end);
        assertTrue("Slider must move more than once", selection[0]>=0 && selection[0]<=4); expect("abcdef","slider-text");
        openEditor("en"); scrollTo("Reset this layout"); device.findObject(By.desc("Reset this layout")).click();
        require(By.res("android","button1")).click(); save();
        resumeReceiver(); choose("en"); clear(); touch(1,2,0); expect("d","layout-reset");
        instrumentation.runOnMainSync(() -> { try { PageKeyOverrides.importJson(prefs(),context,portable); } catch(Exception e){throw new IllegalStateException(e);} });
        choose("en"); verifyNine();
        openEditor("en"); require(By.desc("Edit paired layout: en_fn")).click(); action("en_fn",0,0,2,"F12");
        require(By.desc("Edit paired layout: en")).click(); save();
        openEditor("symbols"); require(By.desc("Choose symbol content")).click();
        listItem(context.getString(R.string.symbol_math)+" · "+context.getString(R.string.symbol_page,2,5)).click();
        literal("symbols/math/1",0,0,0,"符"); save(); resumeReceiver();
      }
      choose("en"); verifyNine(); clear(); touch(0,0,2); expect("1","base-digit");
      choose("en_fn"); int before=events.size(); touch(0,0,2); function(before,KeyEvent.KEYCODE_F12); expect("1","typed-f12");
      choose("symbols"); category("math"); touch(3,2,2);
      assertTrue(device.wait(Until.hasObject(By.res(pkg,"miancak_symbol_page").text(context.getString(R.string.symbol_page,2,5))),15000));
      clear();
      instrumentation.runOnMainSync(() -> ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(current().first));
      assertTrue("Connection restart changed the chosen symbol layout",device.wait(Until.hasObject(By.res(pkg,"keyboard_view").descStartsWith("Page: symbols;")),15000));
      assertTrue(device.wait(Until.hasObject(By.res(pkg,"miancak_symbol_page").text(context.getString(R.string.symbol_page,2,5))),15000));
      touch(0,0,0); expect("符","symbol-page-edit");
      choose("en"); clear(); roundtrip(1,2,7); expect("上","roundtrip-shift"); circle(); expect("上转","circle-shift");
      before=events.size(); roundtrip(0,0,2); function(before,KeyEvent.KEYCODE_F1); expect("上转","gesture-fn-fallback");
      shot(restartOnly?"editor-after-restart":"editor-input"); choose("zh");
    }
    finally
    {
      java.io.File dir=TestEvidence.directory(context);
      JSONObject report=new JSONObject().put("restartOnly",restartOnly).put("observations",observations);
      write(new java.io.File(dir,restartOnly?"editor-restart-receiver.json":"editor-receiver.json"),report.toString(2));
      StringBuilder csv=new StringBuilder("action,keyCode,metaState\n");
      for(KeyEvent e:events)csv.append(e.getAction()).append(',').append(e.getKeyCode()).append(',').append(e.getMetaState()).append('\n');
      write(new java.io.File(dir,restartOnly?"editor-restart-events.csv":"editor-events.csv"),csv.toString());
      device.unfreezeRotation(); instrumentation.runOnMainSync(() -> { ImeTestActivity a=current(); if(a!=null)a.finish(); });
    }
  }
  private void write(java.io.File file,String text)throws Exception
  { try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));} }
  private android.content.SharedPreferences prefs(){return DirectBootAwarePreferences.get_shared_preferences(context);}
  private ImeTestActivity current()
  { for(android.app.Activity a:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(a instanceof ImeTestActivity)return (ImeTestActivity)a; return null; }
  private void resumeReceiver()
  {
    context.startActivity(new Intent(context,ImeTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    assertTrue(device.wait(Until.hasObject(By.desc("first_editor")),15000));
    device.findObject(By.desc("first_editor")).click();
    final android.widget.EditText[] target={null};
    instrumentation.runOnMainSync(() -> {
      ImeTestActivity a=current();assertNotNull(a);a.first.requestFocus();target[0]=a.first;
      a.first.setOnKeyListener((v,code,event)->{if(code>=KeyEvent.KEYCODE_F1 && code<=KeyEvent.KEYCODE_F12){events.add(new KeyEvent(event));return true;}return false;});
    }); ImeTestFocus.show(instrumentation,context,target[0]); assertNotNull(keyboard());device.waitForIdle();
  }
  private void clear(){instrumentation.runOnMainSync(()->current().first.setText(""));instrumentation.waitForIdleSync();}
  private void expect(String expected,String name)throws Exception
  {
    final String[] actual={null};long end=SystemClock.uptimeMillis()+15000;
    do{instrumentation.runOnMainSync(()->{ImeTestActivity a=current();actual[0]=a==null?null:a.first.getText().toString();});if(expected.equals(actual[0]))break;SystemClock.sleep(30);}while(SystemClock.uptimeMillis()<end);
    observations.put(new JSONObject().put("name",name).put("expected",expected).put("actual",actual[0]==null?JSONObject.NULL:actual[0]));assertEquals(name,expected,actual[0]);
  }
  private void verifyNine()throws Exception
  {clear();String s="";for(int d=0;d<9;d++){touch(1,2,d);s+="C12345678".substring(d,d+1);expect(s,"direction-"+d);}touch(2,0,0);touch(1,2,7);expect(s+"上","shift-north");}
  private void function(int before,int code)
  {
    long end=SystemClock.uptimeMillis()+10000;while(events.size()<before+2 && SystemClock.uptimeMillis()<end)SystemClock.sleep(20);assertEquals(before+2,events.size());
    for(int i=0;i<2;i++){assertEquals(i,events.get(before+i).getAction());assertEquals(code,events.get(before+i).getKeyCode());assertEquals(0,events.get(before+i).getMetaState()&KeyEvent.META_FUNCTION_ON);}
  }
  private void openEditor(String id) throws Exception
  {
    device.pressBack();context.startActivity(new Intent(context,PageSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    assertTrue(device.wait(Until.hasObject(By.res(pkg,"miancak_page_settings")),15000));
    new UiScrollable(new UiSelector().resourceId(pkg+":id/miancak_page_settings")).scrollIntoView(new UiSelector().description("Edit layout: "+id));
    device.findObject(By.desc("Edit layout: "+id)).click();assertNotNull(device.wait(Until.findObject(By.desc("Save layout")),15000));
  }
  private UiObject2 require(androidx.test.uiautomator.BySelector selector)
  { UiObject2 v=device.wait(Until.findObject(selector),15000);assertNotNull("Expected UI: "+selector,v);return v; }
  private void scrollTo(String desc) throws Exception{new UiScrollable(new UiSelector().resourceId(pkg+":id/miancak_editor_vertical")).scrollIntoView(new UiSelector().description(desc));}
  private void key(String id,int r,int c) throws Exception
  {
    android.util.Log.i("MIANCAK-Acceptance", "Edit " + id + "/" + r + "/" + c);
    String desc="Edit key: "+id+":"+r+":"+c;scrollTo("Editor row: "+id+":"+r);
    new UiScrollable(new UiSelector().resourceId(pkg+":id/miancak_editor_keyboard")).setAsHorizontalList().scrollIntoView(new UiSelector().description(desc));
    UiObject2 cell=device.wait(Until.findObject(By.desc(desc)),15000);assertNotNull(cell);
    for(int attempt=0;attempt<3&&cell.getVisibleBounds().width()<Math.round(80*context.getResources().getDisplayMetrics().density);attempt++)
    {
      UiObject2 horizontal=require(By.res(pkg,"miancak_editor_keyboard"));
      horizontal.scroll(cell.getVisibleBounds().centerX()<horizontal.getVisibleBounds().centerX()?Direction.LEFT:Direction.RIGHT,.35f);
      cell=require(By.desc(desc));
    }
    assertTrue("Editor key preview collapsed: "+cell.getVisibleBounds(),cell.getVisibleBounds().width()>=Math.round(80*context.getResources().getDisplayMetrics().density));cell.click();
  }
  private UiObject2 position(int d) throws Exception
  {
    String id=pkg+":id/miancak_position_"+SLOTS[d];new UiScrollable(new UiSelector().resourceId(pkg+":id/miancak_editor_positions")).scrollIntoView(new UiSelector().resourceId(id));
    UiObject2 v=device.wait(Until.findObject(By.res(pkg,"miancak_position_"+SLOTS[d])),15000);assertNotNull(v);return v;
  }
  private void literal(String id,int r,int c,int d,String text) throws Exception
  {key(id,r,c);position(d).click();pick(R.string.page_literal);device.wait(Until.findObject(By.desc("Key output text")),15000).setText(text);device.findObject(By.res("android","button1")).click();assertNotNull(device.wait(Until.findObject(By.desc("Save layout")),15000));}
  private void action(String id,int r,int c,int d,String label) throws Exception{key(id,r,c);position(d).click();pick(R.string.page_action);listItem(label).click();}
  private String actionLabel(String id){for(PageActions.Action a:PageActions.list(context.getResources(),true))if(a.id.equals(id))return a.label;throw new AssertionError(id);}
  private void pick(int id){device.wait(Until.findObject(By.text(context.getString(id))),15000).click();}
  private UiObject2 listItem(String text) throws Exception
  {new UiScrollable(new UiSelector().className("android.widget.ListView")).scrollIntoView(new UiSelector().text(text));UiObject2 v=device.wait(Until.findObject(By.text(text)),15000);assertNotNull(v);return v;}
  private void closeDialog(){device.findObject(By.res("android","button2")).click();}
  private void save(){device.wait(Until.findObject(By.desc("Save layout")),15000).click();assertTrue(device.wait(Until.hasObject(By.res(pkg,"miancak_page_settings")),15000));}
  private UiObject2 keyboard(){return device.wait(Until.findObject(By.res(pkg,"keyboard_view")),15000);}
  private void choose(String id)
  {
    UiObject2 currentKeyboard=keyboard();
    if(currentKeyboard!=null&&currentKeyboard.getContentDescription().startsWith("Page: "+id+";")){page=id;return;}
    require(By.res(pkg,"miancak_pages")).click();require(By.res(pkg,"miancak_page_chooser"));UiObject2 v=null;
    for(int n=0;n<28;n++){v=device.findObject(By.desc("Choose page: "+id));if(v!=null&&v.getVisibleBounds().height()>16)break;UiObject2 list=device.findObject(By.res(pkg,"miancak_page_chooser"));assertNotNull(list);list.setGestureMargin(Math.round(32*context.getResources().getDisplayMetrics().density));list.scroll(n<14?Direction.DOWN:Direction.UP,.5f,Math.round(120*context.getResources().getDisplayMetrics().density));device.waitForIdle();}
    assertNotNull(v);v.click();assertTrue(device.wait(Until.hasObject(By.res(pkg,"keyboard_view").descStartsWith("Page: "+id+";")),15000));page=id;device.waitForIdle();
  }
  private void category(String id)
  {
    for(int n=0;n<12;n++){UiObject2 v=device.findObject(By.res(pkg,"symbol_category_"+id));if(v!=null&&v.getVisibleBounds().width()>20){v.click();device.waitForIdle();return;}UiObject2 bar=device.findObject(By.res(pkg,"miancak_symbol_categories"));assertNotNull(bar);bar.findObject(By.clazz("android.widget.HorizontalScrollView")).scroll(n<6?Direction.RIGHT:Direction.LEFT,.7f);device.waitForIdle();}fail("Unreachable symbol category");
  }
  private float unit(){Config c=Config.globalConfig();return(keyboard().getVisibleBounds().width()-2*c.horizontal_margin)/10f;}
  private float height(){Config c=Config.globalConfig();return Math.min(c.keyboard_rows_height_pixels,(c.screenHeightPixels-c.keyboard_rows_height_pixels)/3.95f);}
  private Point point(int r,int c)
  {
    KeyboardData data=page.equals("symbols")?PageLayouts.symbols(context.getResources(),new SymbolState("math",1)):PageLayouts.base(context.getResources(),page);
    Config cfg=Config.globalConfig();Rect b=keyboard().getVisibleBounds();float u=(b.width()-2*cfg.horizontal_margin)/data.keysWidth,h=Math.min(cfg.keyboard_rows_height_pixels,(cfg.screenHeightPixels-cfg.keyboard_rows_height_pixels)/data.keysHeight),x=b.left+cfg.horizontal_margin,y=b.top+cfg.marginTop;
    for(int i=0;i<r;i++)y+=(data.rows.get(i).height+data.rows.get(i).shift)*h;KeyboardData.Row row=data.rows.get(r);y+=(row.shift+row.height/2)*h;
    for(int i=0;i<c;i++)x+=(row.keys.get(i).width+row.keys.get(i).shift)*u;x+=(row.keys.get(c).shift+row.keys.get(c).width/2)*u;return new Point(Math.round(x),Math.round(y));
  }
  private void touch(int r,int c,int d)
  {Point p=point(r,c);int[] v=VECTORS[d];if(d==0)assertTrue(device.click(p.x,p.y));else assertTrue(device.swipe(p.x,p.y,Math.round(p.x+v[0]*unit()*.85f),Math.round(p.y+v[1]*height()*.65f),12));instrumentation.waitForIdleSync();}
  private void roundtrip(int r,int c,int d)
  {Point p=point(r,c);int[] v=VECTORS[d];assertTrue(device.swipe(new Point[]{p,new Point(Math.round(p.x+v[0]*unit()*.9f),Math.round(p.y+v[1]*height()*.7f)),p},12));}
  private void circle()
  {Point p=point(1,2);int x=Math.round(unit()*.95f),y=Math.round(height()*.7f);assertTrue(device.swipe(new Point[]{p,new Point(p.x,p.y-y),new Point(p.x+x,p.y),new Point(p.x,p.y+y),new Point(p.x-x,p.y),p},10));}
  private void orientation(int expected)
  {long end=SystemClock.uptimeMillis()+15000;while(context.getResources().getConfiguration().orientation!=expected&&SystemClock.uptimeMillis()<end)SystemClock.sleep(30);assertEquals(expected,context.getResources().getConfiguration().orientation);device.waitForIdle();}
  private void shot(String name){device.waitForIdle();device.takeScreenshot(new java.io.File(TestEvidence.directory(context),name+".png"));}
}
