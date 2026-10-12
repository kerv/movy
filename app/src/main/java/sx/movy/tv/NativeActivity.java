package sx.movy.tv;

import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Native browsing and episode selection. Browser resolution runs only on Play. */
public class NativeActivity extends android.app.Activity {
    private static final int BG=0xff111416, PANEL=0xff20252a, MUTED=0xffaab0b6, ACCENT=0xffe9ae36;
    private FrameLayout root, loading;
    private LinearLayout content, results, episodes;
    private TextView loadingText;
    private StreamResolver resolver;
    private PlaybackStore library;
    private String screen="home", filter="All";
    private int generation, season=1;
    private List<CatalogClient.Row> homeRows;
    private boolean providersLoading;
    private boolean providersLoaded;
    private Updater updater;
    private CatalogClient.Title detailTitle;
    private boolean returnFromPlayer;
    private SearchDebouncer searchDebouncer;
    private AccountClient accountClient;
    private AccountClient.Snapshot account;
    private LinearLayout accountRows;
    private ImageView loadingArt;
    private TextView loadingTitle;
    private ScrollView mainScroll;
    private FrameLayout sidebar;
    private final List<TextView> navigationLabels=new ArrayList<>();
    private boolean sidebarExpanded;
    private Button entryControl;
    private android.view.ViewTreeObserver.OnGlobalFocusChangeListener navigationFocus;
    private CatalogClient.Provider activeProvider;
    private String libraryType="movie",pendingSearch="";
    private CatalogClient.Sort librarySort=CatalogClient.Sort.RELEASE_DESC;
    private CatalogClient.Genre libraryGenre;
    private final Map<String,List<CatalogClient.Genre>> genreCache=new HashMap<>();
    private List<ProviderSection> pageSections=new ArrayList<>();
    private final Deque<ScreenState> history=new ArrayDeque<>();
    private boolean detailLoading;
    private int screenColor=BG;
    private final Set<String> nextLookups=new HashSet<>();
    private final Map<String,CatalogClient.Title> accountNext=new HashMap<>();
    private final Map<String,Long> accountNextUpdated=new HashMap<>();
    private long homeLoadedAt;

    /** Keep the actual card trees and scroll offsets, not just page numbers. */
    private final class ScreenState {
        final List<View> children=new ArrayList<>();final View focus=root.findFocus();final Object focusTag=focusTagOf(focus);final String name=screen,homeFilter=filter,type=libraryType,search=pendingSearch;
        final LinearLayout body=content,searchResults=results,episodeRows=episodes,accountContent=accountRows;
        final FrameLayout overlay=loading,nav=sidebar;final ScrollView scroll=mainScroll;final int scrollY=mainScroll==null?0:mainScroll.getScrollY(),selectedSeason=season,color=screenColor;
        final TextView status=loadingText,title=loadingTitle;final ImageView art=loadingArt;final Button top=entryControl;
        final List<TextView> labels=new ArrayList<>(navigationLabels);final List<ProviderSection> sections=pageSections;
        final CatalogClient.Title detail=detailTitle;final boolean pendingDetails=detailLoading;final CatalogClient.Provider provider=activeProvider;final CatalogClient.Sort sort=librarySort;final CatalogClient.Genre genre=libraryGenre;
        ScreenState(){for(int i=0;i<root.getChildCount();i++)children.add(root.getChildAt(i));}
    }
    private void rememberScreen(){if(content==null)return;history.addLast(new ScreenState());while(history.size()>8)history.removeFirst();releaseArtwork(root);}
    private void releaseArtwork(View view){if(view instanceof ImageView)((ImageView)view).setImageDrawable(null);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)releaseArtwork(group.getChildAt(i));}}
    boolean restoreScreen(){
        if(history.isEmpty())return false;cancelResolver();if(searchDebouncer!=null)searchDebouncer.cancel();if(accountClient!=null){accountClient.close();accountClient=null;}generation++;
        ScreenState state=history.removeLast();root.removeAllViews();screen=state.name;filter=state.homeFilter;libraryType=state.type;pendingSearch=state.search;activeProvider=state.provider;librarySort=state.sort;libraryGenre=state.genre;
        content=state.body;results=state.searchResults;episodes=state.episodeRows;accountRows=state.accountContent;loading=state.overlay;loadingText=state.status;loadingTitle=state.title;loadingArt=state.art;mainScroll=state.scroll;sidebar=state.nav;entryControl=state.top;detailTitle=state.detail;detailLoading=state.pendingDetails;season=state.selectedSeason;screenColor=state.color;pageSections=state.sections;
        navigationLabels.clear();navigationLabels.addAll(state.labels);sidebarExpanded=false;root.setBackgroundColor(screenColor);for(View child:state.children)root.addView(child);loading.setVisibility(View.GONE);setSidebarExpanded(false);
        for(ProviderSection section:pageSections)if(section.fetching){section.fetching=false;section.more.setEnabled(true);section.more.setText(section.type.equals("movie")?"Retry movies":"Retry series");section.status.setText("Current titles kept. Retry to load the next page.");}
        if(detailLoading&&detailTitle!=null)showDetail(detailTitle);else if(!pendingSearch.isEmpty()&&screen.equals("search"))search(pendingSearch);
        int token=generation;root.post(()->{if(token!=generation)return;View target=state.focus!=null&&state.focus.isShown()?state.focus:state.focusTag==null?null:content.findViewWithTag(state.focusTag);if(target!=null)target.requestFocus();else if(entryControl!=null)entryControl.requestFocus();mainScroll.scrollTo(0,state.scrollY);});
        if(screen.equals("account"))showAccount();else if(accountRows!=null){renderAccountRows();readAccount(false);}return true;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        UiModeManager ui=(UiModeManager)getSystemService(UI_MODE_SERVICE);
        if(ui!=null&&ui.getCurrentModeType()==Configuration.UI_MODE_TYPE_TELEVISION)setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().setNavigationBarColor(BG);root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        library=new PlaybackStore(this);showHome();checkUpdate();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    private TextView label(String s,int size){TextView t=text(s,size);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable rounded(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(10));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    private Button button(String title,Runnable click){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setPadding(dp(16),0,dp(16),0);b.setBackground(rounded(PANEL,0));b.setFocusable(true);b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setOnClickListener(v->click.run());b.setOnFocusChangeListener((v,f)->{b.setBackground(rounded(f?Color.WHITE:PANEL,0));b.setTextColor(f?BG:Color.WHITE);b.animate().scaleX(f?1.035f:1f).scaleY(f?1.035f:1f).setDuration(160).start();});return b;
    }
    private void addButton(LinearLayout row,String title,Runnable click){Button b=button(title,click);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.rightMargin=dp(10);row.addView(b,p);}
    private void begin(String name){
        cancelResolver();if(searchDebouncer!=null)searchDebouncer.cancel();if(accountClient!=null){accountClient.close();accountClient=null;}
        if(name.equals("home"))history.clear();else if(!name.equals(screen))rememberScreen();
        accountRows=null;pageSections=new ArrayList<>();activeProvider=null;detailLoading=false;pendingSearch="";screen=name;generation++;root.removeAllViews();screenColor=BG;root.setBackgroundColor(BG);
        mainScroll=new ScrollView(this);mainScroll.setFillViewport(true);mainScroll.setSmoothScrollingEnabled(true);mainScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        content=column();content.setPadding(dp(24),dp(18),dp(24),dp(40));mainScroll.addView(content);FrameLayout.LayoutParams mainParams=new FrameLayout.LayoutParams(-1,-1);mainParams.leftMargin=dp(84);root.addView(mainScroll,mainParams);
        content.setAlpha(0);content.animate().alpha(1).setDuration(180).start();
        loading=new FrameLayout(this);loading.setBackgroundColor(BG);
        loadingArt=new ImageView(this);loadingArt.setScaleType(ImageView.ScaleType.CENTER_CROP);loading.addView(loadingArt,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xd9090d16,0xf0090d16}));loading.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout box=column();box.setGravity(Gravity.CENTER);box.setPadding(dp(32),dp(24),dp(32),dp(24));
        TextView brand=label("MOVY",18);brand.setTextColor(ACCENT);brand.setGravity(Gravity.CENTER);box.addView(brand);
        loadingTitle=label("Getting ready",28);loadingTitle.setGravity(Gravity.CENTER);loadingTitle.setPadding(0,dp(18),0,dp(26));box.addView(loadingTitle,new LinearLayout.LayoutParams(-1,-2));
        ProgressBar spin=new ProgressBar(this);spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));box.addView(spin,new LinearLayout.LayoutParams(dp(40),dp(40)));
        loadingText=text("Loading…",16);loadingText.setGravity(Gravity.CENTER);loadingText.setTextColor(MUTED);loadingText.setPadding(0,dp(22),0,dp(22));box.addView(loadingText,new LinearLayout.LayoutParams(-1,-2));
        Button cancel=button("Cancel",this::cancelResolver);box.addView(cancel,new LinearLayout.LayoutParams(dp(140),dp(44)));
        loading.addView(box,new FrameLayout.LayoutParams(-1,-1));loading.setVisibility(View.GONE);root.addView(loading);
    }
    private void busy(boolean yes,String message){loadingText.setText(message);loading.setVisibility(yes?View.VISIBLE:View.GONE);if(yes){loading.setAlpha(0);loading.animate().alpha(1).setDuration(180).start();}}
    private void header(){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo=label(screen.equals("home")?"Home":screen.equals("movies")?"Movies":screen.equals("series")?"Series":screen.equals("provider")&&activeProvider!=null?activeProvider.name:screen.equals("genres")?"Categories":screen.equals("providers")?"Providers":screen.equals("detail")?"Title details":screen.equals("saved")?"My List":screen.equals("account")?"Account":"Search",24);bar.addView(logo,new LinearLayout.LayoutParams(0,dp(48),1));
        entryControl=button("Libraries",()->{setSidebarExpanded(true);sidebar.findViewWithTag("nav-home").requestFocus();});bar.addView(entryControl,new LinearLayout.LayoutParams(dp(116),dp(40)));content.addView(bar);buildSidebar();focusEntry();
    }
    private void focusEntry(){int token=generation;entryControl.requestFocus();mainScroll.scrollTo(0,0);root.post(()->{if(token==generation){if(!screen.equals("detail")||root.findFocus()==null)entryControl.requestFocus();mainScroll.scrollTo(0,0);}});}
    private void buildSidebar(){
        navigationLabels.clear();sidebarExpanded=false;sidebar=new FrameLayout(this);sidebar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff101315,0xfa101315}));sidebar.setElevation(dp(12));
        ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);LinearLayout items=column();items.setPadding(dp(8),dp(18),dp(8),dp(22));scroll.addView(items);sidebar.addView(scroll);
        TextView brand=label("M",23);brand.setGravity(Gravity.CENTER);brand.setTextColor(ACCENT);items.addView(brand,new LinearLayout.LayoutParams(-1,dp(40)));
        navItem(items,"Account","account",screen.equals("account"),this::showAccount);navItem(items,"My List","saved",screen.equals("saved"),this::showSaved);navItem(items,"Search","search",screen.equals("search"),this::showSearch);navItem(items,"Home","home",screen.equals("home"),()->{filter="All";showHome();});
        TextView libraries=text("LIBRARIES",11);libraries.setTextColor(MUTED);libraries.setPadding(dp(12),dp(20),0,dp(10));navigationLabels.add(libraries);libraries.setVisibility(View.GONE);items.addView(libraries);
        navItem(items,"Movies","movies",screen.equals("movies")&&activeProvider==null,()->showLibrary("movie",null,null,CatalogClient.Sort.RELEASE_DESC));navItem(items,"Series","series",screen.equals("series")&&activeProvider==null,()->showLibrary("tv",null,null,CatalogClient.Sort.RELEASE_DESC));navItem(items,"Categories","genres",screen.equals("genres"),()->showGenres("movie"));navItem(items,"Providers","providers",screen.equals("providers"),this::showProviders);
        for(CatalogClient.Provider provider:CatalogClient.PROVIDERS)navItem(items,provider.name,"brand:"+provider.name.charAt(0),activeProvider==provider,()->showProvider(provider));
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(68),-1,Gravity.LEFT);root.addView(sidebar,Math.max(0,root.getChildCount()-1),params);
        if(navigationFocus==null){navigationFocus=(oldFocus,newFocus)->setSidebarExpanded(isInside(newFocus,sidebar));root.getViewTreeObserver().addOnGlobalFocusChangeListener(navigationFocus);}
    }
    private boolean isInside(View child,View ancestor){while(child!=null){if(child==ancestor)return true;child=child.getParent() instanceof View?(View)child.getParent():null;}return false;}
    private Object focusTagOf(View view){while(view!=null&&view!=root){if(view.getTag()!=null)return view.getTag();view=view.getParent() instanceof View?(View)view.getParent():null;}return null;}
    private void setSidebarExpanded(boolean expanded){if(sidebar==null)return;sidebarExpanded=expanded;android.view.ViewGroup.LayoutParams params=sidebar.getLayoutParams();if(params!=null&&params.width!=dp(expanded?228:68)){params.width=dp(expanded?228:68);sidebar.setLayoutParams(params);mainScroll.animate().alpha(expanded?.5f:1f).setDuration(140).start();}for(TextView label:navigationLabels)label.setVisibility(expanded?View.VISIBLE:View.GONE);}
    private void navItem(LinearLayout parent,String name,String icon,boolean selected,Runnable action){
        LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(12),0,dp(10),0);item.setFocusable(true);item.setClickable(true);item.setTag("nav-"+icon);item.setContentDescription(name);item.setBackground(rounded(selected?0xff30363a:Color.TRANSPARENT,0));
        NavigationIcon glyph=new NavigationIcon(this,icon);item.addView(glyph,new LinearLayout.LayoutParams(dp(24),dp(24)));TextView caption=label(name,14);caption.setPadding(dp(14),0,0,0);caption.setVisibility(View.GONE);navigationLabels.add(caption);item.addView(caption,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(44));params.bottomMargin=dp(3);parent.addView(item,params);
        item.setOnClickListener(v->action.run());item.setOnFocusChangeListener((v,focused)->{if(focused)setSidebarExpanded(true);item.setBackground(rounded(focused?Color.WHITE:selected?0xff30363a:Color.TRANSPARENT,0));caption.setTextColor(focused?BG:Color.WHITE);glyph.setColor(focused?BG:0xffccd1d7);});
    }
    private void showHome(){
        begin("home");header();
        if(homeRows!=null&&System.currentTimeMillis()-homeLoadedAt>15*60*1000){homeRows=null;providersLoaded=false;}
        if(homeRows!=null){renderHome();return;}
        int token=generation;busy(true,"Discovering your next watch…");CatalogClient.home((rows,error)->{if(token!=generation)return;busy(false,"");if(error!=null)error("Couldn’t load Movy’s catalogue.",this::showHome);else{homeRows=rows;homeLoadedAt=System.currentTimeMillis();renderHome();}});
    }
    private void renderHome(){
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(0,dp(12),0,dp(14));
        addButton(tabs,"Movie library",()->showLibrary("movie",null,null,CatalogClient.Sort.RELEASE_DESC));addButton(tabs,"Series library",()->showLibrary("tv",null,null,CatalogClient.Sort.RELEASE_DESC));addButton(tabs,"Categories",()->showGenres("movie"));
        addButton(tabs,"Refresh",()->{homeRows=null;providersLoaded=false;showHome();});content.addView(tabs);
        LinearLayout providers=new LinearLayout(this);providers.setPadding(dp(6),dp(8),dp(6),dp(20));providers.setClipChildren(false);for(CatalogClient.Provider p:CatalogClient.PROVIDERS)providers.addView(providerTile(p));HorizontalScrollView providerStrip=new HorizontalScrollView(this);providerStrip.setHorizontalScrollBarEnabled(false);providerStrip.setSmoothScrollingEnabled(true);providerStrip.setClipChildren(false);providerStrip.addView(providers);
        List<CatalogClient.Row> rows=new ArrayList<>();for(CatalogClient.Row row:homeRows){List<CatalogClient.Title> titles=new ArrayList<>();for(CatalogClient.Title title:row.titles)if(filter.equals("All")||(filter.equals("Series")==title.isSeries()))titles.add(title);if(!titles.isEmpty())rows.add(new CatalogClient.Row(row.name,titles));}
        accountRows=column();content.addView(accountRows);renderAccountRows();readAccount(false);
        if(!rows.isEmpty())hero(rows.get(0).titles.get(0),null,false);
        section("Streaming libraries");content.addView(providerStrip);
        List<CatalogClient.Title> saved=library.titles("saved:");if(!saved.isEmpty())rail(new CatalogClient.Row("My List · This TV",saved));
        List<CatalogClient.Title> recent=library.titles("history:");if(!recent.isEmpty())rail(new CatalogClient.Row("Recently Watched · This TV",recent));
        for(CatalogClient.Row row:rows)rail(row);
        if(!providersLoaded&&!providersLoading){
            providersLoading=true;int token=generation;
            CatalogClient.providers((extra,error)->{
                providersLoading=false;providersLoaded=true;
                if(homeRows==null)return;
                Set<String> names=new HashSet<>();for(CatalogClient.Row r:homeRows)names.add(r.name);
                List<CatalogClient.Row> fresh=new ArrayList<>();for(CatalogClient.Row r:extra)if(names.add(r.name)){fresh.add(r);homeRows.add(r);}
                if(token==generation&&!filter.equals("Movies"))for(CatalogClient.Row r:fresh)rail(r);
            });
        }
    }
    private void showSaved(){begin("saved");header();content.addView(label("My List",28));accountRows=column();content.addView(accountRows);renderAccountRows();readAccount(false);TextView note=text("Also saved on this TV",14);note.setTextColor(MUTED);note.setPadding(0,dp(20),0,dp(12));content.addView(note);List<CatalogClient.Title> saved=library.titles("saved:");if(saved.isEmpty())content.addView(text("Choose a title and select + My List to save it here.",17));else rail(new CatalogClient.Row("Saved for later · This TV",saved));}
    private void hero(CatalogClient.Title title,JSONObject detail,boolean full){
        FrameLayout hero=new FrameLayout(this);hero.setBackground(rounded(PANEL,0));hero.setClipToOutline(true);
        ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);hero.addView(art,new FrameLayout.LayoutParams(-1,-1));image(art,detail!=null?detail.optString("background",title.backdrop):title.backdrop);
        View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff090d16,0xec090d16,0x40090d16}));hero.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout info=column();info.setPadding(dp(28),dp(22),dp(24),dp(22));
        TextView eyebrow=label(full?(title.isSeries()?"SERIES":"MOVIE"):"FEATURED ON MOVY",12);eyebrow.setTextColor(0xffff6676);info.addView(eyebrow);
        TextView name=label(title.title,full?32:28);name.setPadding(0,dp(12),0,dp(12));info.addView(name);
        TextView metadata=text(metadata(title,detail),15);metadata.setTextColor(0xffe4d199);info.addView(metadata);
        String description=detail!=null?detail.optString("desc"):title.description;
        TextView synopsis=text(description.isEmpty()?"Explore this title, meet the cast, and find your next favorite.":description,15);synopsis.setTextColor(0xffd1d8e2);synopsis.setMaxLines(full?5:3);synopsis.setEllipsize(android.text.TextUtils.TruncateAt.END);synopsis.setLineSpacing(dp(3),1);synopsis.setPadding(0,dp(14),0,dp(18));info.addView(synopsis);
        LinearLayout actions=new LinearLayout(this);
        if(full){
            String resumeKey=resumeKey(title);
            int s=1,e=1;String[] parts=resumeKey.split("/");if(title.isSeries()&&parts.length>=5){try{s=Integer.parseInt(parts[3]);e=Integer.parseInt(parts[4]);}catch(NumberFormatException ignored){}}
            final int playSeason=s,playEpisode=e;
            CatalogClient.Title queued=nextFor(title);long pos=resumePosition(title,resumeKey);addButton(actions,title.nextEpisode||(queued!=null&&queued.slug.equals(resumeKey))?"▶ Next episode":pos>0?"▶ Resume":"▶ Play",()->play(title,playSeason,playEpisode,false));
            if(pos>0)addButton(actions,"Start over",()->play(title,playSeason,playEpisode,true));
            Button save=button(library.saved(title.slug)?"✓ In My List":"+ My List",()->{});save.setOnClickListener(v->{library.toggle(title);save.setText(library.saved(title.slug)?"✓ In My List":"+ My List");});LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,dp(44));sp.rightMargin=dp(10);actions.addView(save,sp);
        }else addButton(actions,"Explore title",()->showDetail(title));
        info.addView(actions);int available=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-64;
        FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(Math.min(available,full?610:540)),-2);hero.addView(info,ip);
        // Content determines height, avoiding title/button clipping on small TV
        // viewports and when accessibility font scaling is enabled.
        hero.setMinimumHeight(dp(full?350:260));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(14);hp.bottomMargin=dp(16);content.addView(hero,hp);
        if(full&&actions.getChildCount()>0)actions.getChildAt(0).requestFocus();
        if(!full){int token=generation;CatalogClient.details(title,(data,error)->{if(token!=generation||data==null)return;metadata.setText(metadata(title,data));String overview=data.optString("desc");if(!overview.isEmpty())synopsis.setText(overview);});}
    }
    private String metadata(CatalogClient.Title title,JSONObject d){
        List<String> fields=new ArrayList<>();String rating=d!=null?String.format(Locale.US,"%.1f",d.optDouble("score",0)):title.rating;
        if(!rating.isEmpty()&&!rating.equals("0.0"))fields.add("★ "+rating+" / 10");String year=d!=null?d.optString("year"):title.year;if(!year.isEmpty())fields.add(year);
        if(d!=null){String cert=d.optString("certification");if(!cert.isEmpty())fields.add(cert);int mins=d.optInt("duration");if(mins>0)fields.add((mins/60>0?mins/60+"h ":"")+mins%60+"m");JSONArray seasons=d.optJSONArray("seasons");if(seasons!=null&&seasons.length()>0)fields.add(seasons.length()+" seasons");}
        else fields.add(title.isSeries()?"Series":"Movie");return android.text.TextUtils.join("   •   ",fields);
    }
    private LinearLayout rail(CatalogClient.Row row){
        TextView heading=label(row.name,20);heading.setPadding(0,dp(18),0,dp(12));content.addView(heading);
        TextView preview=text("",14);preview.setTextColor(MUTED);preview.setMinHeight(dp(24));content.addView(preview);
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setSmoothScrollingEnabled(true);scroll.setClipChildren(false);LinearLayout cards=new LinearLayout(this);cards.setPadding(dp(8),dp(10),dp(8),dp(14));cards.setClipChildren(false);cards.setTag(preview);
        for(CatalogClient.Title item:row.titles)cards.addView(card(item,preview));scroll.addView(cards);content.addView(scroll,new LinearLayout.LayoutParams(-1,-2));return cards;
    }
    private void showProvider(CatalogClient.Provider provider){
        showProvider(provider,false);
    }
    private void showProvider(CatalogClient.Provider provider,boolean recent){
        begin("provider");activeProvider=provider;screenColor=androidx.core.graphics.ColorUtils.blendARGB(BG,provider.color,.13f);root.setBackgroundColor(screenColor);header();providerBanner(provider);
        LinearLayout filters=new LinearLayout(this);addButton(filters,recent?"Popular":"✓ Popular",()->showProvider(provider,false));addButton(filters,recent?"✓ Recently released":"Recently released",()->showProvider(provider,true));content.addView(filters);
        LinearLayout browse=new LinearLayout(this);addButton(browse,"Movie library",()->showLibrary("movie",provider,null,CatalogClient.Sort.RELEASE_DESC));addButton(browse,"Series library",()->showLibrary("tv",provider,null,CatalogClient.Sort.RELEASE_DESC));content.addView(browse);
        TextView note=text(recent?"Newest original movie releases and series premieres, not new episodes or newly added titles · US movie availability":"Movy metadata popularity ranking, not weekly charts · US movie availability",14);note.setTextColor(MUTED);content.addView(note);
        LinearLayout jump=new LinearLayout(this);content.addView(jump);
        ProviderSection movies=createProviderSection(recent?"Recently Released Movies":"Popular Movies","movie");
        ProviderSection series=createProviderSection(recent?"Recently Premiered Series":"Popular Series","tv");
        addButton(jump,"Browse movies ↓",()->focusProviderSection(movies));addButton(jump,"Browse series ↓",()->focusProviderSection(series));
        movies.more.setOnClickListener(v->loadProviderSection(provider,recent,movies));series.more.setOnClickListener(v->loadProviderSection(provider,recent,series));
        loadProviderSection(provider,recent,movies);loadProviderSection(provider,recent,series);
    }
    private void providerBanner(CatalogClient.Provider provider){
        LinearLayout banner=new LinearLayout(this);banner.setGravity(Gravity.CENTER_VERTICAL);banner.setPadding(dp(24),dp(22),dp(24),dp(22));banner.setBackground(providerBackground(provider,false));
        ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);banner.addView(logo,new LinearLayout.LayoutParams(dp(126),dp(62)));image(logo,provider.logo);LinearLayout copy=column();copy.setPadding(dp(28),0,0,0);copy.addView(label(provider.name+" library",28));TextView note=text("Recommended  /  Movies  /  Series",14);note.setTextColor(MUTED);note.setPadding(0,dp(10),0,0);copy.addView(note);banner.addView(copy,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);p.bottomMargin=dp(18);content.addView(banner,p);
    }
    private void showProviders(){begin("providers");header();section("Streaming libraries");LinearLayout row=null;int columns=Math.max(1,(Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-132)/164);int i=0;for(CatalogClient.Provider provider:CatalogClient.PROVIDERS){if(i++%columns==0){row=new LinearLayout(this);row.setPadding(0,dp(10),0,dp(14));content.addView(row);}row.addView(providerTile(provider));}}
    private void showLibrary(String type,CatalogClient.Provider provider,CatalogClient.Genre genre,CatalogClient.Sort sort){
        begin(type.equals("movie")?"movies":"series");libraryType=type;activeProvider=provider;libraryGenre=genre;librarySort=sort;header();if(provider!=null)providerBanner(provider);
        TextView hint=text("Library · "+(provider==null?"Movy catalogue":provider.name)+(type.equals("tv")?" · Series dates are first premieres":""),14);hint.setTextColor(MUTED);content.addView(hint);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(0,dp(16),0,dp(12));addButton(tabs,"Recommended",()->{if(provider==null)showRecommended(type);else showProvider(provider);});addButton(tabs,"✓ Library",()->{});addButton(tabs,"Categories",()->showGenres(type,provider));content.addView(tabs);
        LinearLayout controls=new LinearLayout(this);addButton(controls,sort.label,()->chooseSort(type,provider,genre,sort));addButton(controls,genre==null?"All categories":genre.name,()->chooseGenre(type,provider,sort));addButton(controls,"Refresh",()->showLibrary(type,provider,genre,sort));content.addView(controls);
        ProviderSection section=createProviderSection(genre==null?(type.equals("movie")?"All movies":"All series"):genre.name,type);
        section.more.setOnClickListener(v->loadLibrarySection(type,provider,genre,sort,section));loadLibrarySection(type,provider,genre,sort,section);
    }
    private void chooseSort(String type,CatalogClient.Provider provider,CatalogClient.Genre genre,CatalogClient.Sort current){
        CatalogClient.Sort[] options=CatalogClient.Sort.values();String[] names=new String[options.length];for(int i=0;i<names.length;i++)names[i]=(options[i]==current?"✓ ":"")+options[i].label;
        new AlertDialog.Builder(this).setTitle("Sort library").setItems(names,(d,index)->showLibrary(type,provider,genre,options[index])).setNegativeButton("Cancel",null).show();
    }
    private void withGenres(String type,CatalogClient.Result<List<CatalogClient.Genre>> callback){List<CatalogClient.Genre> cached=genreCache.get(type);if(cached!=null){callback.accept(cached,null);return;}CatalogClient.genres(type,(genres,error)->{if(error==null)genreCache.put(type,genres);callback.accept(genres,error);});}
    private void chooseGenre(String type,CatalogClient.Provider provider,CatalogClient.Sort sort){int token=generation;withGenres(type,(genres,error)->{if(token!=generation)return;if(error!=null){Toast.makeText(this,"Categories could not load. Please try again.",Toast.LENGTH_LONG).show();return;}String[] names=new String[genres.size()+1];names[0]="All categories";for(int i=0;i<genres.size();i++)names[i+1]=genres.get(i).name;new AlertDialog.Builder(this).setTitle("Filter by category").setItems(names,(d,index)->showLibrary(type,provider,index==0?null:genres.get(index-1),sort)).setNegativeButton("Cancel",null).show();});}
    private void showRecommended(String type){
        begin(type.equals("movie")?"movies":"series");libraryType=type;header();LinearLayout tabs=new LinearLayout(this);addButton(tabs,"✓ Recommended",()->{});addButton(tabs,"Library",()->showLibrary(type,null,null,CatalogClient.Sort.RELEASE_DESC));addButton(tabs,"Categories",()->showGenres(type));content.addView(tabs);
        LinearLayout rows=column();content.addView(rows);int token=generation;CatalogClient.Result<List<CatalogClient.Row>> render=(all,error)->{if(token!=generation)return;if(error!=null){rows.addView(button("Retry recommendations",()->showRecommended(type)));return;}LinearLayout main=content;content=rows;boolean featured=false;for(CatalogClient.Row row:all){List<CatalogClient.Title> titles=new ArrayList<>();for(CatalogClient.Title title:row.titles)if(title.isSeries()==type.equals("tv"))titles.add(title);if(!titles.isEmpty()){if(!featured){hero(titles.get(0),null,false);featured=true;}rail(new CatalogClient.Row(row.name,titles));}}content=main;};if(homeRows!=null)render.accept(homeRows,null);else CatalogClient.home(render);
    }
    private void showGenres(String type){showGenres(type,null);}
    private void showGenres(String type,CatalogClient.Provider provider){
        begin("genres");libraryType=type;activeProvider=provider;header();section("Browse by category"+(provider==null?"":" · "+provider.name));LinearLayout tabs=new LinearLayout(this);addButton(tabs,type.equals("movie")?"✓ Movies":"Movies",()->showGenres("movie",provider));addButton(tabs,type.equals("tv")?"✓ Series":"Series",()->showGenres("tv",provider));content.addView(tabs);
        LinearLayout grid=column();content.addView(grid);int token=generation;withGenres(type,(genres,error)->{if(token!=generation)return;if(error!=null){grid.addView(button("Retry categories",()->showGenres(type,provider)));return;}int columns=Math.max(1,(Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-132)/204);LinearLayout row=null;int i=0;for(CatalogClient.Genre genre:genres){if(i++%columns==0){row=new LinearLayout(this);row.setPadding(0,dp(12),0,dp(12));grid.addView(row);}Button category=button(genre.name,()->showLibrary(type,provider,genre,CatalogClient.Sort.RELEASE_DESC));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(190),dp(90));p.rightMargin=dp(14);row.addView(category,p);}});
    }
    private void loadLibrarySection(String type,CatalogClient.Provider provider,CatalogClient.Genre genre,CatalogClient.Sort sort,ProviderSection section){
        if(section.fetching||section.ended)return;int token=generation;section.fetching=true;section.more.setEnabled(false);section.more.setText("Loading…");section.status.setText("Loading page "+(section.page+1)+"…");
        CatalogClient.libraryPage(type,provider,genre==null?0:genre.id,sort,section.page+1,(page,error)->finishCatalogPage(section,token,page,error));
    }
    private View providerTile(CatalogClient.Provider provider){
        LinearLayout tile=column();tile.setGravity(Gravity.CENTER);tile.setPadding(dp(12),dp(12),dp(12),dp(10));tile.setFocusable(true);tile.setClickable(true);tile.setContentDescription("Browse "+provider.name);tile.setBackground(providerBackground(provider,false));
        ImageView logo=new ImageView(this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);tile.addView(logo,new LinearLayout.LayoutParams(dp(104),dp(44)));image(logo,provider.logo);
        TextView name=label(provider.name,13);name.setGravity(Gravity.CENTER);name.setPadding(0,dp(8),0,0);tile.addView(name);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(150),dp(102));params.rightMargin=dp(14);tile.setLayoutParams(params);
        tile.setOnClickListener(v->showProvider(provider));tile.setOnFocusChangeListener((v,focused)->{tile.setBackground(providerBackground(provider,focused));tile.animate().scaleX(focused?1.06f:1f).scaleY(focused?1.06f:1f).translationZ(focused?dp(6):0).setDuration(180).start();});return tile;
    }
    private GradientDrawable providerBackground(CatalogClient.Provider provider,boolean focused){
        int tint=(provider.color&0x00ffffff)|0xff000000;GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{androidx.core.graphics.ColorUtils.blendARGB(PANEL,tint,focused?.48f:.22f),PANEL});background.setCornerRadius(dp(14));background.setStroke(dp(focused?2:1),focused?Color.WHITE:androidx.core.graphics.ColorUtils.blendARGB(PANEL,tint,.5f));return background;
    }
    static final class ProviderSection {
        final String type;final LinearLayout block,grid;final TextView preview,status;final Button more;final int columns;
        final Set<String> seen=new HashSet<>();int page;boolean fetching,ended;
        ProviderSection(String type,LinearLayout block,LinearLayout grid,TextView preview,TextView status,Button more,int columns){this.type=type;this.block=block;this.grid=grid;this.preview=preview;this.status=status;this.more=more;this.columns=columns;}
    }
    ProviderSection createProviderSection(String heading,String type){
        LinearLayout block=column();content.addView(block);TextView title=label(heading,22);title.setPadding(0,dp(24),0,dp(12));block.addView(title);
        TextView preview=text("",14);preview.setTextColor(MUTED);preview.setMinHeight(dp(24));block.addView(preview);
        LinearLayout grid=column();grid.setClipChildren(false);block.addView(grid);
        TextView status=text("Loading…",14);status.setTextColor(MUTED);status.setPadding(0,dp(12),0,dp(12));block.addView(status);
        Button more=button(type.equals("movie")?"Load more movies":"Load more series",()->{});block.addView(more,new LinearLayout.LayoutParams(dp(220),dp(46)));
        int width=Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density)-138;
        ProviderSection section=new ProviderSection(type,block,grid,preview,status,more,Math.max(1,width/204));pageSections.add(section);return section;
    }
    private void focusProviderSection(ProviderSection section){if(section.grid.getChildCount()>0)((LinearLayout)section.grid.getChildAt(0)).getChildAt(0).requestFocus();else section.more.requestFocus();}
    View appendProviderPage(ProviderSection section,CatalogClient.ProviderPage page){
        View first=null;int added=0;
        for(CatalogClient.Title title:page.titles)if(section.seen.add(title.slug)){
            LinearLayout row=section.grid.getChildCount()==0?null:(LinearLayout)section.grid.getChildAt(section.grid.getChildCount()-1);
            if(row==null||row.getChildCount()>=section.columns){row=new LinearLayout(this);row.setGravity(Gravity.TOP);row.setClipChildren(false);row.setPadding(dp(6),dp(8),0,dp(12));section.grid.addView(row);}
            View item=card(title,section.preview);row.addView(item);if(first==null)first=item;added++;
        }
        section.page=page.page;section.ended=page.titles.isEmpty()||page.page>=Math.min(500,page.totalPages);section.status.setText(section.seen.size()+" "+(section.type.equals("movie")?"movies":"series")+" loaded · "+added+" new · Page "+section.page);
        section.more.setEnabled(!section.ended);section.more.setText(section.ended?(page.page>=500&&page.totalPages>500?"Catalogue page limit reached":"No more results"):section.type.equals("movie")?"Load more movies":"Load more series");return first;
    }
    private void loadProviderSection(CatalogClient.Provider provider,boolean recent,ProviderSection section){
        if(section.fetching||section.ended)return;int token=generation;section.fetching=true;section.more.setEnabled(false);section.more.setText("Loading…");section.status.setText("Loading page "+(section.page+1)+"…");
        CatalogClient.providerPage(provider,section.type,section.page+1,recent,(page,error)->{
            finishCatalogPage(section,token,page,error);
        });
    }
    private void finishCatalogPage(ProviderSection section,int token,CatalogClient.ProviderPage page,Exception error){
        if(token!=generation)return;section.fetching=false;if(error!=null){section.more.setEnabled(true);section.more.setText(section.type.equals("movie")?"Retry movies":"Retry series");section.status.setText("Couldn’t load this page. Your current titles are still here.");return;}boolean paging=section.page>0;View first=appendProviderPage(section,page);if(paging&&first!=null)first.post(()->{if(token==generation)first.requestFocus();});
    }
    private View card(CatalogClient.Title title,TextView preview){
        LinearLayout card=column();card.setTag(title.slug);card.setPadding(dp(8),dp(8),dp(8),dp(10));card.setBackground(rounded(PANEL,0));card.setFocusable(true);card.setClickable(true);
        ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackground(rounded(0xff263146,0));card.addView(poster,new LinearLayout.LayoutParams(-1,dp(260)));image(poster,title.poster);
        TextView name=label(title.title,15);name.setMinLines(2);name.setPadding(0,dp(9),0,dp(5));card.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView meta=text(metadata(title,null),12);meta.setTextColor(0xffdec881);card.addView(meta,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(190),-2);cp.rightMargin=dp(14);card.setLayoutParams(cp);
        card.setOnFocusChangeListener((v,f)->{card.setBackground(rounded(f?0xff26334b:PANEL,f?Color.WHITE:0));poster.animate().alpha(f?1:.88f).setDuration(160).start();card.animate().scaleX(f?1.025f:1f).scaleY(f?1.025f:1f).translationZ(f?dp(4):0).setDuration(160).start();if(f)preview.setText(title.title+"    "+metadata(title,null));});
        card.setOnClickListener(v->showDetail(title));return card;
    }
    private void image(ImageView view,String url){
        if(url==null||url.isEmpty())return;
        // Long provider grids retain their cards, but not offscreen bitmaps.
        // The shared bounded cache makes scrolling back cheap without keeping
        // every poster visited alive in its ImageView for the whole session.
        view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            boolean fetching,displayed,failed;
            @Override public boolean onPreDraw(){
                boolean visible=view.getGlobalVisibleRect(new android.graphics.Rect());if(displayed&&view.getDrawable()==null)displayed=false;
                if(!visible){if(displayed){view.setImageDrawable(null);displayed=false;}failed=false;}
                else if(!fetching&&!displayed&&!failed){
                    fetching=true;CatalogClient.image(url,(bitmap,error)->{fetching=false;if(isDestroyed()||!view.isAttachedToWindow())return;failed=bitmap==null;
                        if(bitmap!=null&&view.getGlobalVisibleRect(new android.graphics.Rect())){view.setImageBitmap(bitmap);displayed=true;}
                    });
                }
                return true;
            }
        });
    }
    private void showDetail(CatalogClient.Title title){
        if(screen.equals("detail")&&detailTitle!=null&&!detailTitle.slug.equals(title.slug))rememberScreen();begin("detail");detailTitle=title;season=1;detailLoading=true;header();hero(title,null,true);
        TextView loadingDetail=text("Loading synopsis, cast, and episodes…",14);loadingDetail.setTextColor(MUTED);content.addView(loadingDetail);
        int token=generation;CatalogClient.details(title,(data,error)->{if(token!=generation)return;detailLoading=false;if(error!=null){loadingDetail.setText("Details are unavailable. You can still try Play.");return;}
            content.removeViews(1,content.getChildCount()-1);hero(title,data,true);detailExtras(title,data);
        });
    }
    private void detailExtras(CatalogClient.Title title,JSONObject detail){
        JSONArray genres=detail.optJSONArray("categories");if(genres!=null){TextView g=text(join(genres),15);g.setTextColor(0xffc1b7fa);g.setPadding(0,dp(8),0,dp(15));content.addView(g);}
        String desc=detail.optString("desc");if(!desc.isEmpty()){section("About "+title.title);TextView about=text(desc,16);about.setTextColor(MUTED);about.setLineSpacing(dp(5),1);content.addView(about);}
        if(title.isSeries())seasonPicker(title,detail.optJSONArray("seasons"));
        JSONArray cast=detail.optJSONArray("actors");if(cast!=null&&cast.length()>0){section("Cast & Characters");HorizontalScrollView rail=new HorizontalScrollView(this);LinearLayout people=new LinearLayout(this);for(int i=0;i<Math.min(cast.length(),12);i++){JSONObject actor=cast.optJSONObject(i);if(actor==null)continue;LinearLayout person=column();person.setPadding(0,0,dp(16),0);ImageView avatar=new ImageView(this);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);person.addView(avatar,new LinearLayout.LayoutParams(dp(105),dp(130)));image(avatar,actor.optString("avatar"));TextView name=label(actor.optString("name"),13);person.addView(name,new LinearLayout.LayoutParams(dp(105),-2));TextView role=text(actor.optString("label"),12);role.setTextColor(MUTED);person.addView(role,new LinearLayout.LayoutParams(dp(105),-2));people.addView(person);}rail.addView(people);content.addView(rail);}
        for(String key:new String[]{"directors","writers","creators"}){JSONArray people=detail.optJSONArray(key);if(people!=null&&people.length()>0){StringBuilder names=new StringBuilder();for(int i=0;i<Math.min(people.length(),6);i++){JSONObject p=people.optJSONObject(i);if(i>0)names.append(", ");names.append(p==null?people.optString(i):p.optString("name"));}TextView credit=text(Character.toUpperCase(key.charAt(0))+key.substring(1)+": "+names,14);credit.setTextColor(MUTED);credit.setPadding(0,dp(14),0,0);content.addView(credit);}}
        recommendations(detail.opt("recommendations"),title.isSeries()?"tv":"movie","You May Also Like");recommendations(detail.opt("similar"),title.isSeries()?"tv":"movie","More Like This");
        LinearLayout options=new LinearLayout(this);options.setPadding(0,dp(22),0,0);addButton(options,"Refresh details",()->showDetail(title));content.addView(options);
    }
    private void section(String title){TextView h=label(title,21);h.setPadding(0,dp(24),0,dp(14));content.addView(h);}
    private String join(JSONArray a){List<String>s=new ArrayList<>();for(int i=0;i<a.length();i++)s.add(a.optString(i));return android.text.TextUtils.join("   •   ",s);}
    private void recommendations(Object raw,String type,String heading){
        JSONArray a=raw instanceof JSONArray?(JSONArray)raw:raw instanceof JSONObject?((JSONObject)raw).optJSONArray("results"):null;if(a==null)return;
        List<CatalogClient.Title> titles=new ArrayList<>();for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j==null)continue;try{titles.add(j.has("slug")?new CatalogClient.Title(j):CatalogClient.fromMetadata(j,type));}catch(Exception ignored){}}
        if(!titles.isEmpty())rail(new CatalogClient.Row(heading,titles));
    }
    private void seasonPicker(CatalogClient.Title title,JSONArray seasons){
        if(seasons==null||seasons.length()==0)return;section("Episodes");LinearLayout choices=new LinearLayout(this);
        int first=1;boolean found=false;for(int i=0;i<seasons.length();i++){JSONObject s=seasons.optJSONObject(i);if(s==null)continue;int n=s.optInt("season_number",i+1);if(n==0)continue;if(!found){first=n;found=true;}addButton(choices,"Season "+n,()->loadEpisodes(title,n));}
        HorizontalScrollView sc=new HorizontalScrollView(this);sc.addView(choices);content.addView(sc);episodes=column();content.addView(episodes);loadEpisodes(title,first);
    }
    private void loadEpisodes(CatalogClient.Title title,int number){
        season=number;LinearLayout target=episodes;target.removeAllViews();target.addView(text("Loading season "+number+"…",14));int token=generation;
        CatalogClient.episodes(title.id,number,(items,error)->{if(token!=generation||season!=number)return;target.removeAllViews();if(error!=null){target.addView(button("Retry season "+number,()->loadEpisodes(title,number)));return;}
            for(int i=0;i<items.length();i++){JSONObject e=items.optJSONObject(i);if(e==null)continue;int n=e.optInt("episode_number",i+1);LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(10),0,dp(10));row.setGravity(Gravity.CENTER_VERTICAL);
                ImageView still=new ImageView(this);still.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(still,new LinearLayout.LayoutParams(dp(150),dp(85)));String path=e.optString("still_path");if(!path.isEmpty())image(still,"https://image.tmdb.org/t/p/w300"+path);
                LinearLayout info=column();info.setPadding(dp(16),0,dp(16),0);info.addView(label(n+". "+e.optString("name","Episode "+n),16));TextView desc=text(e.optString("overview"),13);desc.setMaxLines(3);desc.setEllipsize(android.text.TextUtils.TruncateAt.END);desc.setTextColor(MUTED);info.addView(desc);row.addView(info,new LinearLayout.LayoutParams(0,-2,1));Button play=button("▶ Play",()->play(title,number,n,false));row.addView(play,new LinearLayout.LayoutParams(dp(100),dp(44)));target.addView(row);
            }
        });
    }
    private void showSearch(){
        begin("search");header();section("Find your next favorite");LinearLayout bar=new LinearLayout(this);EditText query=new EditText(this);query.setTextColor(Color.WHITE);query.setHintTextColor(MUTED);query.setHint("Movies, series, anime…");query.setSingleLine(true);query.setPadding(dp(18),0,dp(18),0);query.setBackground(rounded(PANEL,0));query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);bar.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));addButton(bar,"Search",()->search(query.getText().toString()));query.setOnEditorActionListener((v,id,e)->{if(id==EditorInfo.IME_ACTION_SEARCH){search(query.getText().toString());return true;}return false;});content.addView(bar);results=column();content.addView(results);
        searchDebouncer=new SearchDebouncer(this::search);
        query.addTextChangedListener(new android.text.TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}@Override public void onTextChanged(CharSequence s,int start,int before,int count){generation++;searchDebouncer.submit(s.toString());if(s.toString().trim().isEmpty())results.removeAllViews();}@Override public void afterTextChanged(android.text.Editable s){}});
        query.requestFocus();
    }
    private void search(String value){
        if(searchDebouncer!=null)searchDebouncer.cancel();if(value.trim().isEmpty())return;pendingSearch=value;int token=++generation;results.removeAllViews();TextView status=text("Searching…",15);status.setPadding(0,dp(20),0,0);results.addView(status);CatalogClient.search(value.trim(),(rows,error)->{if(token!=generation)return;pendingSearch="";results.removeAllViews();if(error!=null){results.addView(button("Try search again",()->search(value)));return;}LinearLayout main=content;content=results;for(CatalogClient.Row row:rows)if(row.titles.isEmpty())content.addView(text("No matching titles. Try another name.",18));else rail(row);content=main;});
    }
    private void play(CatalogClient.Title title,int season,int episode,boolean restart){
        if(resolver!=null)return;final String path=title.isSeries()?"/tv/"+title.id+"/"+season+"/"+episode:title.slug;
        loadingTitle.setText(title.title);image(loadingArt,title.backdrop);busy(true,"Finding the best available source…");StreamResolver current=new StreamResolver(this,root);resolver=current;int token=generation;
        current.start(CatalogClient.SITE+path+"?play=true",new StreamResolver.Callback(){
            @Override public void ready(String url,HashMap<String,String> headers,String tracks){if(token==generation)openPlayer(title,path,url,headers,tracks,restart);}
            @Override public void progress(String message){if(token==generation)loadingText.setText(message);}
            @Override public void failed(String message){if(token!=generation)return;resolver=null;busy(false,"");new AlertDialog.Builder(NativeActivity.this).setTitle("Unable to start playback").setMessage("This source is unavailable. Please try again shortly.").setPositiveButton("Try again",(d,w)->play(title,season,episode,restart)).setNegativeButton("Cancel",null).show();}
        });
    }
    void openPlayer(CatalogClient.Title title,String path,String url,HashMap<String,String> headers,String tracks,boolean restart){
        resolver=null;loadingText.setText("Opening player…");Intent intent=new Intent(this,PlayerActivity.class);intent.setData(Uri.parse(url));intent.putExtra("headers",headers);intent.putExtra("tracks",tracks);intent.putExtra("title",title.title);intent.putExtra("poster",title.poster);intent.putExtra("key",path);intent.putExtra("position",restart?0:resumePosition(title,path));returnFromPlayer=true;startActivityForResult(intent,410);overridePendingTransition(0,0);
    }
    private void cancelResolver(){if(resolver!=null){resolver.close();resolver=null;}if(loading!=null)loading.setVisibility(View.GONE);}
    private CatalogClient.Title nextFor(CatalogClient.Title title){for(CatalogClient.Title next:library.titles("next:"))if(next.id==title.id&&next.isSeries()==title.isSeries())return next;if(account!=null&&account.profile!=null){String today=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());for(CatalogClient.Title completed:account.completedSeries)if(completed.id==title.id&&completed.isSeries()==title.isSeries()){CatalogClient.Title next=accountNext.get(accountScope()+":"+completed.slug);if(next!=null&&next.availableOn.compareTo(today)<=0)return next;}}return null;}
    private String resumeKey(CatalogClient.Title title){if(title.nextEpisode)return title.slug;String local=library.resumeKey(title);if(library.position(local)>0)return local;CatalogClient.Title remote=account==null?title:account.resume(title);if(remote.resumeMs>0&&!library.completedTitle(title))return remote.slug;CatalogClient.Title next=nextFor(title);return next==null?title.slug:next.slug;}
    private long resumePosition(CatalogClient.Title title,String path){long local=library.position(path);if(local>0)return local;if(library.completedTitle(title)||title.nextEpisode)return 0;CatalogClient.Title remote=account==null?title:account.resume(title);return remote.slug.equals(path)?remote.resumeMs:0;}
    private void renderAccountRows(){
        if(accountRows==null)return;View oldFocus=accountRows.findFocus();Object focusedTag=focusTagOf(oldFocus);accountRows.removeAllViews();LinearLayout main=content;content=accountRows;
        if(screen.equals("home")){continueWatching();if(account!=null&&account.profile!=null&&!account.recentHistory.isEmpty())rail(new CatalogClient.Row("Recently Watched · "+account.profile.optString("name","Movy"),account.recentHistory));}
        if(account!=null&&account.signedIn){
            if(account.profile==null){content.addView(button("Choose your Movy profile",this::showAccount));}
            else {for(CatalogClient.Row row:account.rows){if((screen.equals("saved")||screen.equals("home"))&&row.name.startsWith("Continue Watching"))continue;rail(row);}if(account.partial){TextView note=text("Some Movy account lists couldn’t refresh.",13);note.setTextColor(MUTED);content.addView(note);}}
        }else {TextView note=text("Connect your Movy account for your lists and Continue Watching.",14);note.setTextColor(MUTED);note.setPadding(0,dp(16),0,dp(8));content.addView(note);content.addView(button("Sign in to Movy",this::showAccount),new LinearLayout.LayoutParams(dp(180),dp(44)));}
        content=main;if(focusedTag!=null){View replacement=accountRows.findViewWithTag(focusedTag);if(replacement!=null)replacement.requestFocus();else if(entryControl!=null)entryControl.requestFocus();}
    }
    private String accountScope(){return "account:"+(account==null?"":account.username)+":"+(account!=null&&account.profile!=null?account.profile.optString("id"):"none");}
    private void adoptAccount(AccountClient.Snapshot snapshot){String before=accountScope();account=snapshot;if(!before.equals(accountScope()))history.clear();}
    private void continueWatching(){
        LinkedHashMap<String,CatalogClient.Title> titles=new LinkedHashMap<>();List<CatalogClient.Title> local=new ArrayList<>(library.titles("resume:"));local.addAll(library.titles("next:"));Collections.sort(local,(a,b)->Long.compare(b.updatedMs,a.updatedMs));for(CatalogClient.Title title:local)if(!titles.containsKey(PlaybackStore.titleKey(title.slug)))titles.put(PlaybackStore.titleKey(title.slug),title);
        if(account!=null&&account.profile!=null){String today=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());int token=generation;for(CatalogClient.Title completed:account.completedSeries){String lookup=accountScope()+":"+completed.slug;
            if(accountNext.containsKey(lookup)&&System.currentTimeMillis()-(accountNextUpdated.containsKey(lookup)?accountNextUpdated.get(lookup):0L)>15*60*1000){accountNext.remove(lookup);accountNextUpdated.remove(lookup);}
            if(accountNext.containsKey(lookup)){CatalogClient.Title next=accountNext.get(lookup);if(next!=null&&next.availableOn.compareTo(today)<=0&&!library.dismissed(next,accountScope())&&!titles.containsKey(PlaybackStore.titleKey(next.slug)))titles.put(PlaybackStore.titleKey(next.slug),next);}
            else if(nextLookups.add(lookup))NextEpisodeFinder.find(completed,(next,error)->{nextLookups.remove(lookup);if(error==null){CatalogClient.Title title=null;if(next!=null)try{title=new CatalogClient.Title(new JSONObject().put("id",completed.id).put("title",completed.title).put("poster",completed.poster).put("image",completed.backdrop).put("slug",next.key).put("nextEpisode",true).put("availableOn",next.date));}catch(Exception ignored){}accountNext.put(lookup,title);accountNextUpdated.put(lookup,System.currentTimeMillis());if(token==generation&&screen.equals("home"))renderAccountRows();}});
        }}
        if(account!=null&&account.profile!=null)for(CatalogClient.Row row:account.rows)if(row.name.startsWith("Continue Watching"))for(CatalogClient.Title title:row.titles){String key=PlaybackStore.titleKey(title.slug);if(!titles.containsKey(key)&&!library.dismissed(title,accountScope())&&!library.completedTitle(title))titles.put(key,title);}
        section("Continue Watching");if(titles.isEmpty()){TextView empty=text("Resume your movies and series here. Your Movy account and this TV’s activity appear together.",14);empty.setTextColor(MUTED);content.addView(empty);}else{HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setSmoothScrollingEnabled(true);LinearLayout cards=new LinearLayout(this);cards.setPadding(dp(6),dp(10),dp(6),dp(14));cards.setClipChildren(false);int count=0;for(CatalogClient.Title title:titles.values()){if(count++>=PlaybackStore.RECENT_LIMIT)break;cards.addView(continueCard(title));}scroll.addView(cards);content.addView(scroll);}
        int token=generation;for(CatalogClient.Title completed:library.titles("pending_next:"))if(nextLookups.add(completed.slug))NextEpisodeFinder.find(completed,(next,error)->{nextLookups.remove(completed.slug);if(error==null){library.queueNext(completed,next);if(token==generation&&screen.equals("home"))renderAccountRows();}});
    }
    private View continueCard(CatalogClient.Title title){
        FrameLayout card=new FrameLayout(this);card.setId(View.generateViewId());card.setTag("continue:"+PlaybackStore.titleKey(title.slug));card.setFocusable(true);card.setClickable(true);card.setBackground(rounded(PANEL,0));LinearLayout info=column();
        ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);info.addView(art,new LinearLayout.LayoutParams(-1,dp(144)));image(art,title.backdrop.isEmpty()?title.poster:title.backdrop);
        TextView name=label(title.title,16);name.setMinLines(2);name.setPadding(dp(12),dp(10),dp(12),dp(6));info.addView(name);String[] parts=title.slug.split("/");String episode=title.isSeries()&&parts.length>=5?" · S"+parts[3]+" E"+parts[4]:"";TextView state=text((title.nextEpisode?"Up next":"Resume")+episode,13);state.setPadding(dp(12),0,dp(12),dp(12));state.setTextColor(ACCENT);info.addView(state);
        if(title.durationMs>0&&title.resumeMs>0){ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgress((int)Math.min(1000,title.resumeMs*1000/title.durationMs));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));info.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));}
        card.addView(info,new FrameLayout.LayoutParams(-1,-2));Button more=button("⋯",()->continueOptions(title));more.setId(View.generateViewId());more.setContentDescription("More options for "+title.title);more.setPadding(0,0,0,0);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(40),dp(36),Gravity.TOP|Gravity.RIGHT);mp.topMargin=dp(8);mp.rightMargin=dp(8);card.addView(more,mp);card.setNextFocusUpId(more.getId());more.setNextFocusDownId(card.getId());more.setNextFocusLeftId(card.getId());
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(264),-2);cp.rightMargin=dp(16);card.setLayoutParams(cp);card.setOnClickListener(v->resumeContinue(title));card.setOnLongClickListener(v->{continueOptions(title);return true;});card.setOnFocusChangeListener((v,focused)->{card.setBackground(rounded(PANEL,focused?Color.WHITE:0));card.animate().scaleX(focused?1.025f:1).scaleY(focused?1.025f:1).setDuration(150).start();});return card;
    }
    private void resumeContinue(CatalogClient.Title title){String[] parts=resumeKey(title).split("/");int s=1,e=1;try{if(parts.length>=5){s=Integer.parseInt(parts[3]);e=Integer.parseInt(parts[4]);}}catch(Exception ignored){}showDetail(title);play(title,s,e,false);}
    private void continueOptions(CatalogClient.Title title){new AlertDialog.Builder(this).setTitle(title.title).setItems(new String[]{title.nextEpisode?"Play next episode":"Resume","View details","Remove from Continue Watching"},(dialog,index)->{if(index==0)resumeContinue(title);else if(index==1)showDetail(title);else{library.dismiss(title,accountScope());renderAccountRows();Toast.makeText(this,"Removed from Continue Watching on this TV",Toast.LENGTH_SHORT).show();}}).setNegativeButton("Cancel",null).show();}
    private void readAccount(boolean accountScreen){
        int token=generation;if(accountClient==null)accountClient=new AccountClient(this,root);
        if(accountScreen){loadingTitle.setText("Your Movy account");busy(true,"Connecting securely…");}
        accountClient.request("read",null,(snapshot,error)->{
            if(token!=generation)return;
            if(accountScreen)busy(false,"");
            if(error==null){adoptAccount(snapshot);if(accountScreen)renderAccount();else renderAccountRows();}
            else if(accountScreen){error("Couldn’t connect to Movy. "+error.getMessage(),this::showAccount);content.addView(button("Sign in again",this::loginForm),new LinearLayout.LayoutParams(dp(180),dp(44)));}
        });
    }
    private void showAccount(){begin("account");header();section("Your Movy account");readAccount(true);}
    private void renderAccount(){
        if(content.getChildCount()>2)content.removeViews(2,content.getChildCount()-2);
        if(account==null||!account.signedIn){loginForm();return;}
        content.addView(label(account.username,26));if(!account.email.isEmpty()){TextView email=text(account.email,15);email.setTextColor(MUTED);content.addView(email);}
        section("Who’s watching?");
        if(account.profiles.length()==0)content.addView(text("No profiles were returned for this account.",16));
        LinearLayout people=new LinearLayout(this);
        for(int i=0;i<account.profiles.length();i++){JSONObject profile=account.profiles.optJSONObject(i);if(profile==null)continue;String name=profile.optString("name","Profile");boolean active=account.profile!=null&&account.profile.optString("id").equals(profile.optString("id"));addButton(people,(active?"● ":"")+name,()->chooseProfile(profile));}
        HorizontalScrollView sc=new HorizontalScrollView(this);sc.setSmoothScrollingEnabled(true);sc.addView(people);content.addView(sc);
        if(account.profile!=null){section("Watching as "+account.profile.optString("name"));for(CatalogClient.Row row:account.rows)rail(row);if(account.rows.isEmpty())content.addView(text("Your lists and watch history are currently empty.",16));}
        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(24),0,0);addButton(actions,"Refresh account",this::showAccount);addButton(actions,"Sign out",()->new AlertDialog.Builder(this).setTitle("Sign out of Movy?").setPositiveButton("Sign out",(d,w)->accountAction("logout",null)).setNegativeButton("Cancel",null).show());content.addView(actions);
    }
    private EditText accountInput(String hint,boolean password){EditText input=new EditText(this);input.setHint(hint);input.setTextColor(Color.WHITE);input.setHintTextColor(MUTED);input.setSingleLine(true);input.setPadding(dp(16),0,dp(16),0);input.setBackground(rounded(PANEL,0));input.setInputType(password?android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_CLASS_TEXT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(380),dp(50));p.topMargin=dp(14);content.addView(input,p);return input;}
    private void loginForm(){
        if(content.getChildCount()>2)content.removeViews(2,content.getChildCount()-2);
        TextView note=text("Sign in to access your Movy profiles, watchlists, and Continue Watching.",16);note.setTextColor(MUTED);content.addView(note);
        EditText username=accountInput("Email or username",false),password=accountInput("Password",true);
        Button signIn=button("Sign in",()->{String user=username.getText().toString().trim(),pass=password.getText().toString();if(user.isEmpty()||pass.isEmpty())return;JSONObject args=new JSONObject();try{args.put("username",user);args.put("password",pass);}catch(Exception ignored){}password.setText("");accountAction("login",args);});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(160),dp(46));p.topMargin=dp(18);content.addView(signIn,p);username.requestFocus();
    }
    private void chooseProfile(JSONObject profile){
        if((!profile.has("hasPin")&&!profile.has("pinEnabled")&&!profile.has("pinRequired"))||profile.optBoolean("hasPin")||profile.optBoolean("pinEnabled")||profile.optBoolean("pinRequired")){
            EditText pin=new EditText(this);pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);pin.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
            new AlertDialog.Builder(this).setTitle("PIN for "+profile.optString("name")).setView(pin).setPositiveButton("Continue",(d,w)->selectProfile(profile,pin.getText().toString())).setNegativeButton("Cancel",null).show();
        }else selectProfile(profile,"");
    }
    private void selectProfile(JSONObject profile,String pin){JSONObject args=new JSONObject();try{args.put("id",profile.optString("id"));args.put("pin",pin);}catch(Exception ignored){}accountAction("profile",args);}
    private void accountAction(String action,JSONObject args){
        int token=generation;if(accountClient==null)accountClient=new AccountClient(this,root);loadingTitle.setText("Your Movy account");busy(true,"Updating your account…");
        accountClient.request(action,args,(snapshot,error)->{if(token!=generation)return;busy(false,"");if(error!=null){new AlertDialog.Builder(this).setTitle("Movy account").setMessage(error.getMessage()).setPositiveButton("OK",null).show();return;}adoptAccount(snapshot);renderAccount();});
    }
    private void error(String message,Runnable retry){TextView t=text(message,17);t.setPadding(0,dp(20),0,dp(16));content.addView(t);content.addView(button("Try again",retry),new LinearLayout.LayoutParams(dp(150),dp(46)));}
    @Override public void onBackPressed(){if(resolver!=null){cancelResolver();return;}if(sidebarExpanded){setSidebarExpanded(false);if(entryControl!=null)entryControl.requestFocus();return;}if(restoreScreen())return;if(!screen.equals("home"))showHome();else new AlertDialog.Builder(this).setTitle("Exit Movy?").setPositiveButton("Exit",(d,w)->finish()).setNegativeButton("Keep browsing",null).show();}
    private void checkUpdate(){
        updater=new Updater();updater.checkForUpdate(this,new Updater.PromptCallback(){
            @Override public void onUpdateAvailable(String version,String notes,Runnable download){new AlertDialog.Builder(NativeActivity.this).setTitle("Movy "+version+" is ready").setMessage(notes).setPositiveButton("Update",(d,w)->download.run()).setNegativeButton("Later",null).show();}
            @Override public void onDownloadComplete(){Toast.makeText(NativeActivity.this,"Update downloaded. Follow the install prompt.",Toast.LENGTH_LONG).show();}
            @Override public void onInstallBlocked(Runnable retry){new AlertDialog.Builder(NativeActivity.this).setTitle("Update ready").setPositiveButton("Install",(d,w)->retry.run()).setNegativeButton("Later",null).show();}
            @Override public void onError(String message){}
        });
    }
    @Override protected void onPause(){super.onPause();if(updater!=null)updater.onActivityPaused();if(!returnFromPlayer)cancelResolver();}
    @Override protected void onResume(){super.onResume();if(updater!=null)updater.onActivityResumed(this);if(returnFromPlayer){returnFromPlayer=false;busy(false,"");if(detailTitle!=null)showDetail(detailTitle);}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==410&&result==RESULT_OK&&data!=null&&detailTitle!=null){
            CatalogClient.Title title=detailTitle;int s=data.getIntExtra("season",1),e=data.getIntExtra("episode",1);
            returnFromPlayer=false;showDetail(title);int token=generation;
            CatalogClient.episodes(title.id,s,(items,error)->{
                if(token!=generation)return;
                if(error!=null){error("Couldn’t load the next episode.",()->showDetail(title));return;}
                if(e<=items.length())play(title,s,e,false);
                else CatalogClient.episodes(title.id,s+1,(next,nextError)->{if(token!=generation)return;if(nextError==null&&next.length()>0)play(title,s+1,1,false);else Toast.makeText(this,"You’ve reached the end of the available episodes.",Toast.LENGTH_LONG).show();});
            });
        }
    }
    @Override protected void onDestroy(){cancelResolver();if(searchDebouncer!=null)searchDebouncer.cancel();if(accountClient!=null)accountClient.close();super.onDestroy();}
}
