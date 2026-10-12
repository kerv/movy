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
    @Test public void providerPagesGrowDownwardPreservingCardsAndHaveIndependentSeriesPaging() throws Exception {
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"provider");
        NativeActivity.ProviderSection movies=a.createProviderSection("Movies","movie"),series=a.createProviderSection("Series","tv");
        List<CatalogClient.Title> firstPage=new ArrayList<>();for(int i=1;i<=movies.columns;i++)firstPage.add(new CatalogClient.Title(new JSONObject().put("id",i).put("slug","/movie/"+i).put("title","Movie "+i)));
        View original=a.appendProviderPage(movies,new CatalogClient.ProviderPage(firstPage,1,3));LinearLayout originalRow=(LinearLayout)movies.grid.getChildAt(0);
        CatalogClient.Title extra=new CatalogClient.Title(new JSONObject().put("id",100).put("slug","/movie/100").put("title","Next movie"));
        View next=a.appendProviderPage(movies,new CatalogClient.ProviderPage(java.util.Arrays.asList(firstPage.get(0),extra),2,3));
        assertEquals(2,movies.grid.getChildCount());assertSame(originalRow,movies.grid.getChildAt(0));assertSame(original,originalRow.getChildAt(0));assertSame(next,((LinearLayout)movies.grid.getChildAt(1)).getChildAt(0));assertEquals(movies.columns+1,movies.seen.size());assertEquals(2,movies.page);assertEquals(0,series.page);
        CatalogClient.Title tv=new CatalogClient.Title(new JSONObject().put("id",200).put("slug","/tv/200").put("name","A series"));
        a.appendProviderPage(series,new CatalogClient.ProviderPage(java.util.Collections.singletonList(tv),1,2));assertEquals(1,series.page);assertEquals(2,movies.page);assertTrue(series.more.isEnabled());assertEquals("Load more series",series.more.getText());
        a.appendProviderPage(movies,new CatalogClient.ProviderPage(java.util.Collections.emptyList(),3,3));assertTrue(movies.ended);assertFalse(movies.more.isEnabled());assertTrue(series.more.isEnabled());
        a.appendProviderPage(series,new CatalogClient.ProviderPage(java.util.Collections.singletonList(new CatalogClient.Title(new JSONObject().put("id",201).put("slug","/tv/201"))),2,2));assertEquals(2,series.seen.size());assertTrue(series.ended);
    }
    @Test public void providerTileHasLogoLabelAndRemoteFocus() throws Exception {
        NativeActivity a=activity();LinearLayout tile=(LinearLayout)call(a,"providerTile",new Class[]{CatalogClient.Provider.class},CatalogClient.PROVIDERS[3]);
        assertTrue(tile.getChildAt(0) instanceof android.widget.ImageView);assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER,((android.widget.ImageView)tile.getChildAt(0)).getScaleType());assertEquals("Disney+",((TextView)tile.getChildAt(1)).getText());assertTrue(tile.isFocusable());assertTrue(tile.hasOnClickListeners());assertEquals("Browse Disney+",tile.getContentDescription());
    }
    @Test public void playerHandoffKeepsLoadingOverlayUntilReturn() throws Exception {
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"detail");call(a,"busy",new Class[]{boolean.class,String.class},true,"Preparing…");
        CatalogClient.Title title=new CatalogClient.Title(new JSONObject().put("id",603).put("slug","/movie/603").put("title","The Matrix"));
        a.openPlayer(title,title.slug,"https://media.example/video.m3u8",new java.util.HashMap<>(),"{}",false);
        a.onPause();assertEquals(View.VISIBLE,((View)get(a,"loading")).getVisibility());assertEquals("Opening player…",((TextView)get(a,"loadingText")).getText());assertTrue((Boolean)get(a,"returnFromPlayer"));
        android.content.Intent intent=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(PlayerActivity.class.getName(),intent.getComponent().getClassName());assertEquals(title.slug,intent.getStringExtra("key"));
        a.onResume();assertEquals(View.GONE,((View)get(a,"loading")).getVisibility());
    }
    @Test public void providerRecentQueriesUseReleaseDatesAndExcludeFutureTitles(){
        CatalogClient.Provider disney=CatalogClient.PROVIDERS[3];String tv=CatalogClient.providerUrl(disney,"tv",2,true,"2026-10-11");
        assertTrue(tv.contains("with_networks=2739"));assertTrue(tv.contains("sort_by=first_air_date.desc"));assertTrue(tv.contains("first_air_date.lte=2026-10-11"));assertTrue(tv.contains("page=2"));
        String movie=CatalogClient.providerUrl(disney,"movie",1,true,"2026-10-11");assertTrue(movie.contains("with_watch_providers=337&watch_region=US"));assertTrue(movie.contains("primary_release_date.lte=2026-10-11"));assertTrue(movie.contains("sort_by=primary_release_date.desc"));
        assertTrue(CatalogClient.providerUrl(disney,"tv",1,false,"2026-10-11").contains("sort_by=popularity.desc"));
    }
    @Test public void captionControlRemainsClickableWithoutAdvertisedTracks() throws Exception {
        PlayerActivity a=Robolectric.buildActivity(PlayerActivity.class).get();android.widget.Button cc=new android.widget.Button(a);cc.setEnabled(false);cc.setVisibility(View.GONE);set(a,"captionButton",cc);
        call(a,"enableCaptionButton",new Class[]{});assertTrue(cc.isEnabled());assertEquals(View.VISIBLE,cc.getVisibility());assertEquals("Closed captions",cc.getContentDescription());assertTrue(cc.hasOnClickListeners());
    }
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
