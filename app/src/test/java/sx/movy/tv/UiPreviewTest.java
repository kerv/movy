package sx.movy.tv;

import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;

/** Layout-only previews with fixture data and placeholder art, never live media. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class UiPreviewTest {
    private Object get(Object o,String key)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private void set(Object o,String key,Object value)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);}
    private Object call(Object o,String key,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(key,types);m.setAccessible(true);return m.invoke(o,args);}
    @Test public void continueWatchingLandscapeCardsRenderWithResumeNextAndOptions()throws Exception{
        org.robolectric.android.controller.ActivityController<NativeActivity> controller=Robolectric.buildActivity(NativeActivity.class);NativeActivity a=controller.get();FrameLayout root=new FrameLayout(a);a.setContentView(root);controller.visible();set(a,"root",root);PlaybackStore store=new PlaybackStore(a);set(a,"library",store);call(a,"begin",new Class[]{String.class},"home");call(a,"header",new Class[]{});
        store.progress("/movie/1","Across the Ocean","",1200000,7200000);store.progress("/tv/2/2/3","The Long Way Home","",1000000,3600000);store.progress("/tv/3/1/10","Northern Lights","",3599000,3600000);store.queueNext(new CatalogClient.Title(new JSONObject().put("id",3).put("slug","/tv/3/1/10").put("title","Northern Lights")),new NextEpisodeFinder.Next("/tv/3/2/1","2020-01-01"));
        LinearLayout content=(LinearLayout)get(a,"content");LinearLayout activity=new LinearLayout(a);activity.setOrientation(LinearLayout.VERTICAL);content.addView(activity);set(a,"accountRows",activity);call(a,"renderAccountRows",new Class[]{});HorizontalScrollView rail=(HorizontalScrollView)activity.getChildAt(1);LinearLayout cards=(LinearLayout)rail.getChildAt(0);assertEquals(3,cards.getChildCount());
        for(int i=0;i<cards.getChildCount();i++){FrameLayout card=(FrameLayout)cards.getChildAt(i);View image=((LinearLayout)card.getChildAt(0)).getChildAt(0);image.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.HSVToColor(new float[]{200+i*35,.45f,.45f}),0xff14181c}));}
        NativeActivity.ProviderSection grid=a.createProviderSection("Recently released","movie");List<CatalogClient.Title> fixtures=new ArrayList<>();for(int i=0;i<5;i++)fixtures.add(new CatalogClient.Title(new JSONObject().put("id",i+10).put("slug","/movie/"+(i+10)).put("title","New release "+(i+1)).put("rating",7.5).put("release_date","2026-10-12")));a.appendProviderPage(grid,new CatalogClient.ProviderPage(fixtures,1,5));
        root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400));File folder=new File("build/reports/native-preview");assertTrue(folder.exists()||folder.mkdirs());save(root,new File(folder,"home-fixture.png"));
    }
    @Test public void libraryAndSidebarRenderWithoutClippingAtTvResolution()throws Exception{
        org.robolectric.android.controller.ActivityController<NativeActivity> controller=Robolectric.buildActivity(NativeActivity.class);NativeActivity a=controller.get();FrameLayout root=new FrameLayout(a);a.setContentView(root);controller.visible();set(a,"root",root);set(a,"library",new PlaybackStore(a));call(a,"begin",new Class[]{String.class},"movies");call(a,"header",new Class[]{});
        LinearLayout content=(LinearLayout)get(a,"content");LinearLayout filters=new LinearLayout(a);for(String label:new String[]{"Recommended","Library","Categories","Release date ↓","All categories"}){Button button=(Button)call(a,"button",new Class[]{String.class,Runnable.class},label,(Runnable)()->{});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,44);p.rightMargin=12;filters.addView(button,p);}content.addView(filters);
        NativeActivity.ProviderSection section=a.createProviderSection("New releases","movie");List<CatalogClient.Title> titles=new ArrayList<>();String[] names={"A New Beginning","The Last Journey","Beyond the Horizon","A Quiet Afternoon","Northern Lights","Tomorrow's Promise","Across the Ocean","The Long Way Home"};for(int i=0;i<names.length;i++)titles.add(new CatalogClient.Title(new JSONObject().put("id",i+1).put("slug","/movie/"+(i+1)).put("title",names[i]).put("rating",7.4+i*.1).put("release_date","2026-10-10")));a.appendProviderPage(section,new CatalogClient.ProviderPage(titles,1,5));
        int i=0;for(int row=0;row<section.grid.getChildCount();row++){LinearLayout cards=(LinearLayout)section.grid.getChildAt(row);for(int col=0;col<cards.getChildCount();col++){LinearLayout card=(LinearLayout)cards.getChildAt(col);int color=Color.HSVToColor(new float[]{(i++*43)%360,.4f,.45f});card.getChildAt(0).setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{color,0xff151a20}));}}
        root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        View first=((LinearLayout)section.grid.getChildAt(0)).getChildAt(0);assertTrue(first.getWidth()>0);assertTrue(first.getGlobalVisibleRect(new Rect()));assertEquals(5,section.columns);
        File folder=new File("build/reports/native-preview");assertTrue(folder.exists()||folder.mkdirs());save(root,new File(folder,"library-fixture.png"));
        View selected=((FrameLayout)get(a,"sidebar")).findViewWithTag("nav-movies");selected.getOnFocusChangeListener().onFocusChange(selected,true);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300));root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);assertEquals(228,((View)get(a,"sidebar")).getLayoutParams().width);save(root,new File(folder,"sidebar-fixture.png"));
    }
    @Test public void compactPlayerControlsRenderAtTvResolution()throws Exception{
        android.app.Activity a=Robolectric.buildActivity(android.app.Activity.class).setup().visible().get();FrameLayout root=new FrameLayout(a);root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff36515f,0xff17252b,0xff06090c}));a.setContentView(root);
        androidx.media3.ui.PlayerView player=(androidx.media3.ui.PlayerView)a.getLayoutInflater().inflate(R.layout.native_player,root,false);root.addView(player);((TextView)player.findViewById(R.id.player_title)).setText("The Long Way Home · S2 E3");player.showController();
        root.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY));root.layout(0,0,1280,720);Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        View settings=player.findViewById(R.id.player_options);assertTrue(settings.isFocusable());assertTrue(settings.getGlobalVisibleRect(new Rect()));assertNotNull(player.findViewById(androidx.media3.ui.R.id.exo_progress));assertNotNull(player.findViewById(androidx.media3.ui.R.id.exo_subtitle));
        File folder=new File("build/reports/native-preview");assertTrue(folder.exists()||folder.mkdirs());save(root,new File(folder,"player-fixture.png"));
    }
    private void save(View root,File file)throws Exception{Bitmap bitmap=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();}
}
