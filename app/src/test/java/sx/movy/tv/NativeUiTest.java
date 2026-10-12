package sx.movy.tv;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
import org.robolectric.Shadows;
import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class NativeUiTest {
    private NativeActivity activity() throws Exception {
        NativeActivity a=Robolectric.buildActivity(NativeActivity.class).get();
        set(a,"root",new FrameLayout(a));set(a,"library",new PlaybackStore(a));return a;
    }
    private Object get(Object o,String key) throws Exception {Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private void set(Object o,String key,Object value) throws Exception {Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);}
    private Object call(Object o,String name,Class<?>[] types,Object... args) throws Exception {Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    @Test public void detailAndSearchScreensDoNotHaveAnIndefiniteSpinner() throws Exception {
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"detail");
        assertEquals(View.GONE,((View)get(a,"loading")).getVisibility());
        call(a,"showSearch",new Class[]{});
        assertEquals("search",get(a,"screen"));assertEquals(View.GONE,((View)get(a,"loading")).getVisibility());
    }
    @Test public void cardWrapsTitlesAndShowsScoreAndYear() throws Exception {
        NativeActivity a=activity();JSONObject j=new JSONObject();j.put("title","A very long movie title that must remain readable from across the room");j.put("id",603);j.put("slug","/movie/603");j.put("rating",8.3);j.put("release_date","1999-03-31");
        CatalogClient.Title title=new CatalogClient.Title(j);
        LinearLayout card=(LinearLayout)call(a,"card",new Class[]{CatalogClient.Title.class,TextView.class},title,new TextView(a));
        TextView name=(TextView)card.getChildAt(1);assertTrue(name.getMaxLines()>=2);assertEquals(-2,name.getLayoutParams().height);
        TextView meta=(TextView)card.getChildAt(2);assertTrue(meta.getText().toString().contains("8.3"));assertTrue(meta.getText().toString().contains("1999"));
    }
    @Test public void completedPlaybackLeavesContinueWatchingAndResetsPosition() throws Exception {
        NativeActivity a=activity();PlaybackStore store=new PlaybackStore(a);store.progress("/movie/603","The Matrix","",60000,1200000);
        assertEquals(60000,store.position("/movie/603"));assertEquals(1,store.titles("resume:").size());
        store.progress("/movie/603","The Matrix","",1190000,1200000);assertEquals(0,store.position("/movie/603"));assertTrue(store.titles("resume:").isEmpty());
    }
    @Test public void detailHeroHasRuntimeCertificationAndRoomForPlay() throws Exception {
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"detail");
        JSONObject source=new JSONObject();source.put("id",603);source.put("title","The Matrix");source.put("slug","/movie/603");
        JSONObject detail=new JSONObject();detail.put("score",8.3);detail.put("year",1999);detail.put("duration",136);detail.put("certification","R");detail.put("desc","A computer hacker discovers that reality is a simulation.");
        call(a,"hero",new Class[]{CatalogClient.Title.class,JSONObject.class,boolean.class},new CatalogClient.Title(source),detail,true);
        FrameLayout root=(FrameLayout)get(a,"root");root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);
        LinearLayout content=(LinearLayout)get(a,"content");FrameLayout hero=(FrameLayout)content.getChildAt(0);LinearLayout info=(LinearLayout)hero.getChildAt(2);
        String metadata=((TextView)info.getChildAt(2)).getText().toString();assertTrue(metadata.contains("8.3"));assertTrue(metadata.contains("2h 16m"));assertTrue(metadata.contains("R"));
        View actions=info.getChildAt(4);assertTrue("Hero must not clip its Play row",info.getTop()+actions.getBottom()<=hero.getHeight());
    }
    @Test public void automaticSearchWaitsForPauseAndCancelsOldText() {
        List<String> queries=new ArrayList<>();SearchDebouncer debounce=new SearchDebouncer(queries::add);
        debounce.submit("mat");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));debounce.submit("matrix");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(649));assertTrue(queries.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(java.util.Collections.singletonList("matrix"),queries);
        debounce.submit("another");debounce.submit("");Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));assertEquals(1,queries.size());
    }
    @Test public void loadingStatusIsCenteredAndHasCancel() throws Exception {
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"detail");
        TextView status=(TextView)get(a,"loadingText");assertEquals(android.view.Gravity.CENTER,status.getGravity());
        FrameLayout loading=(FrameLayout)get(a,"loading");LinearLayout panel=(LinearLayout)loading.getChildAt(2);
        assertEquals("Cancel",((TextView)panel.getChildAt(panel.getChildCount()-1)).getText().toString());
    }
}
