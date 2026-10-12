package sx.movy.tv;

import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationBehaviorTest {
    private Object get(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private Object call(Object object,String name,Class<?>[] types,Object...args)throws Exception{Method m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}
    private NativeActivity activity()throws Exception{org.robolectric.android.controller.ActivityController<NativeActivity> controller=Robolectric.buildActivity(NativeActivity.class);NativeActivity a=controller.get();FrameLayout root=new FrameLayout(a);a.setContentView(root);controller.visible();set(a,"root",root);set(a,"library",new PlaybackStore(a));return a;}
    private void layout(NativeActivity a)throws Exception{FrameLayout root=(FrameLayout)get(a,"root");root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);}
    @Test public void completedMovyAccountEpisodeUsesConfirmedNextOnHomeAndDetails()throws Exception{
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"home");call(a,"header",new Class[]{});
        JSONObject completed=new JSONObject().put("id",20).put("slug","/tv/20/1/10").put("title","A completed series");set(a,"account",new AccountClient.Snapshot(new JSONObject().put("signedIn",true).put("username","fixture").put("profile",new JSONObject().put("id","one")).put("completedSeries",new org.json.JSONArray().put(completed))));
        String key="account:fixture:one:/tv/20/1/10";CatalogClient.Title next=new CatalogClient.Title(new JSONObject().put("id",20).put("slug","/tv/20/2/1").put("title","A completed series").put("nextEpisode",true).put("availableOn","2020-01-01"));((Map<String,CatalogClient.Title>)get(a,"accountNext")).put(key,next);((Map<String,Long>)get(a,"accountNextUpdated")).put(key,System.currentTimeMillis());
        LinearLayout rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);((LinearLayout)get(a,"content")).addView(rows);set(a,"accountRows",rows);call(a,"renderAccountRows",new Class[]{});assertNotNull(rows.findViewWithTag("continue:/tv/20"));
        CatalogClient.Title root=new CatalogClient.Title(new JSONObject().put("id",20).put("slug","/tv/20").put("title","A completed series"));assertEquals("/tv/20/2/1",call(a,"resumeKey",new Class[]{CatalogClient.Title.class},root));
        ((PlaybackStore)get(a,"library")).dismiss(next,"account:fixture:one");call(a,"renderAccountRows",new Class[]{});assertNull(rows.findViewWithTag("continue:/tv/20"));
    }
    @Test public void detailsBackRestoresExpandedGridScrollAndSelectedCard()throws Exception{
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"provider");call(a,"header",new Class[]{});NativeActivity.ProviderSection movies=a.createProviderSection("Movies","movie");
        List<CatalogClient.Title> titles=new ArrayList<>();for(int i=1;i<=40;i++)titles.add(new CatalogClient.Title(new JSONObject().put("id",i).put("slug","/movie/"+i).put("title","Movie "+i)));
        a.appendProviderPage(movies,new CatalogClient.ProviderPage(titles.subList(0,20),1,5));a.appendProviderPage(movies,new CatalogClient.ProviderPage(titles.subList(20,40),2,5));layout(a);
        LinearLayout row=(LinearLayout)movies.grid.getChildAt(5);View selected=row.getChildAt(1);selected.requestFocus();ScrollView scroll=(ScrollView)get(a,"mainScroll");scroll.scrollTo(0,1000);int before=scroll.getScrollY();
        call(a,"begin",new Class[]{String.class},"detail");call(a,"header",new Class[]{});a.onBackPressed();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();layout(a);
        assertEquals("Restored source screen","provider",get(a,"screen"));assertSame(scroll,get(a,"mainScroll"));assertEquals(40,movies.seen.size());assertEquals(2,movies.page);assertSame(row,movies.grid.getChildAt(5));assertEquals("Restored scroll position",before,scroll.getScrollY());assertTrue("Original selected card has focus",selected.hasFocus());
    }
    @Test public void newLibraryStartsAtTopRatherThanLoadMoreAndSidebarCollapses()throws Exception{
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"provider");call(a,"header",new Class[]{});NativeActivity.ProviderSection movies=a.createProviderSection("Movies","movie");NativeActivity.ProviderSection series=a.createProviderSection("Series","tv");
        List<CatalogClient.Title> titles=new ArrayList<>();for(int i=1;i<=20;i++)titles.add(new CatalogClient.Title(new JSONObject().put("id",i).put("slug","/movie/"+i)));
        a.appendProviderPage(movies,new CatalogClient.ProviderPage(titles,1,2));a.appendProviderPage(series,new CatalogClient.ProviderPage(titles,1,2));layout(a);series.more.requestFocus();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("Initial scroll position",0,((ScrollView)get(a,"mainScroll")).getScrollY());assertTrue("Entry control has focus",((View)get(a,"entryControl")).hasFocus());assertFalse(movies.more.hasFocus());assertFalse(series.more.hasFocus());
        FrameLayout sidebar=(FrameLayout)get(a,"sidebar");View movieNav=sidebar.findViewWithTag("nav-movies");movieNav.requestFocus();assertTrue((Boolean)get(a,"sidebarExpanded"));assertEquals(228,sidebar.getLayoutParams().width);((View)get(a,"entryControl")).requestFocus();assertFalse((Boolean)get(a,"sidebarExpanded"));assertEquals(68,sidebar.getLayoutParams().width);
    }
    @Test public void restoringPendingPageMakesRetryUsableAndPreservesLibraryFilters()throws Exception{
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"movies");call(a,"header",new Class[]{});set(a,"librarySort",CatalogClient.Sort.TITLE_ASC);CatalogClient.Genre genre=new CatalogClient.Genre(28,"Action");set(a,"libraryGenre",genre);
        NativeActivity.ProviderSection movies=a.createProviderSection("Movies","movie");movies.page=3;movies.fetching=true;movies.more.setEnabled(false);
        call(a,"begin",new Class[]{String.class},"detail");assertTrue(a.restoreScreen());assertFalse(movies.fetching);assertTrue(movies.more.isEnabled());assertEquals(3,movies.page);assertSame(genre,get(a,"libraryGenre"));assertEquals(CatalogClient.Sort.TITLE_ASC,get(a,"librarySort"));
    }
    @Test public void continueWatchingMergesAccountAndDeviceAndCanDismissOnlyCurrentProfile()throws Exception{
        NativeActivity a=activity();call(a,"begin",new Class[]{String.class},"home");call(a,"header",new Class[]{});PlaybackStore store=(PlaybackStore)get(a,"library");store.progress("/tv/1399/2/3","A series","",120000,3600000);
        org.json.JSONArray titles=new org.json.JSONArray().put(new JSONObject().put("id",1399).put("slug","/tv/1399/2/1").put("title","A series").put("resumeMs",90000)).put(new JSONObject().put("id",603).put("slug","/movie/603").put("title","A movie").put("resumeMs",90000));
        set(a,"account",new AccountClient.Snapshot(new JSONObject().put("signedIn",true).put("profile",new JSONObject().put("id","one")).put("rows",new org.json.JSONArray().put(new JSONObject().put("name","Continue Watching · Movy account").put("titles",titles)))));
        LinearLayout accountRows=new LinearLayout(a);accountRows.setOrientation(LinearLayout.VERTICAL);((LinearLayout)get(a,"content")).addView(accountRows);set(a,"accountRows",accountRows);call(a,"renderAccountRows",new Class[]{});
        HorizontalScrollView scroll=(HorizontalScrollView)accountRows.getChildAt(1);assertEquals(2,((LinearLayout)scroll.getChildAt(0)).getChildCount());View localCard=accountRows.findViewWithTag("continue:/tv/1399");assertNotNull(localCard);
        CatalogClient.Title remote=new CatalogClient.Title(titles.getJSONObject(1));store.dismiss(remote,"account::one");call(a,"renderAccountRows",new Class[]{});assertNull(accountRows.findViewWithTag("continue:/movie/603"));assertFalse(store.dismissed(remote,"account::two"));
    }
}
