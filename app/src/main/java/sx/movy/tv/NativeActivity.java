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
    private static final int BG=0xff090d16, PANEL=0xff171e2c, MUTED=0xffb0bbcb, ACCENT=0xffeb3349;
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
        cancelResolver();if(searchDebouncer!=null)searchDebouncer.cancel();if(accountClient!=null){accountClient.close();accountClient=null;}accountRows=null;screen=name;generation++;root.removeAllViews();ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setSmoothScrollingEnabled(true);
        content=column();content.setPadding(dp(32),dp(18),dp(32),dp(40));scroll.addView(content);root.addView(scroll);
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
        TextView logo=label("MOVY",25);logo.setTextColor(ACCENT);bar.addView(logo,new LinearLayout.LayoutParams(0,dp(48),1));
        addButton(bar,"Home",()->{filter="All";showHome();});addButton(bar,"Search",this::showSearch);addButton(bar,"My List",this::showSaved);
        addButton(bar,"Account",this::showAccount);content.addView(bar);
    }
    private void showHome(){
        begin("home");header();
        if(homeRows!=null){renderHome();return;}
        int token=generation;busy(true,"Discovering your next watch…");CatalogClient.home((rows,error)->{if(token!=generation)return;busy(false,"");if(error!=null)error("Couldn’t load Movy’s catalogue.",this::showHome);else{homeRows=rows;renderHome();}});
    }
    private void renderHome(){
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(0,dp(12),0,dp(14));
        for(String value:new String[]{"All","Movies","Series"})addButton(tabs,(filter.equals(value)?"● ":"")+value,()->{filter=value;showHome();});
        addButton(tabs,"Refresh",()->{homeRows=null;providersLoaded=false;showHome();});content.addView(tabs);
        LinearLayout providers=new LinearLayout(this);providers.setPadding(0,dp(4),0,dp(14));for(CatalogClient.Provider p:CatalogClient.PROVIDERS)addButton(providers,p.name,()->showProvider(p));HorizontalScrollView providerStrip=new HorizontalScrollView(this);providerStrip.setHorizontalScrollBarEnabled(false);providerStrip.setSmoothScrollingEnabled(true);providerStrip.addView(providers);content.addView(providerStrip);
        List<CatalogClient.Row> rows=new ArrayList<>();for(CatalogClient.Row row:homeRows){List<CatalogClient.Title> titles=new ArrayList<>();for(CatalogClient.Title title:row.titles)if(filter.equals("All")||(filter.equals("Series")==title.isSeries()))titles.add(title);if(!titles.isEmpty())rows.add(new CatalogClient.Row(row.name,titles));}
        if(!rows.isEmpty())hero(rows.get(0).titles.get(0),null,false);
        accountRows=column();content.addView(accountRows);renderAccountRows();readAccount(false);
        List<CatalogClient.Title> resume=library.titles("resume:");if(!resume.isEmpty())rail(new CatalogClient.Row("Continue Watching · This TV",resume));
        List<CatalogClient.Title> saved=library.titles("saved:");if(!saved.isEmpty())rail(new CatalogClient.Row("My List · This TV",saved));
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
            long pos=resumePosition(title,resumeKey);addButton(actions,pos>0?"▶ Resume":"▶ Play",()->play(title,playSeason,playEpisode,false));
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
        begin("provider");header();section((recent?"Recently released on ":"Popular on ")+provider.name);
        LinearLayout filters=new LinearLayout(this);addButton(filters,recent?"Popular":"✓ Popular",()->showProvider(provider,false));addButton(filters,recent?"✓ Recently released":"Recently released",()->showProvider(provider,true));content.addView(filters);
        TextView note=text(recent?"Newest original movie releases and series premieres, not new episodes or newly added titles · US movie availability":"Movy metadata popularity ranking, not weekly charts · US movie availability",14);note.setTextColor(MUTED);content.addView(note);
        TextView status=text("Loading titles…",14);status.setTextColor(MUTED);content.addView(status);
        Map<String,LinearLayout> rails=new LinkedHashMap<>();int token=generation;int[] page={0};Button more=button("Load more",()->{});more.setEnabled(false);
        Runnable[] fetch=new Runnable[1];fetch[0]=()->{more.setEnabled(false);more.setText("Loading…");status.setText("Loading page "+(page[0]+1)+"…");CatalogClient.providerPage(provider,page[0]+1,recent,(rows,error)->{
            if(token!=generation)return;more.setEnabled(true);more.setText("Load more");if(error!=null){more.setText("Retry loading");status.setText("Couldn’t load this page. Select Retry loading.");return;}page[0]++;boolean any=false;View firstNew=null;int added=0,total=0;
            // Keep existing rails and focused cards; append the next page.
            content.removeView(more);for(CatalogClient.Row row:rows){if(row.titles.isEmpty())continue;any=true;LinearLayout cards=rails.get(row.name);if(cards==null){cards=rail(new CatalogClient.Row(row.name,new ArrayList<>()));rails.put(row.name,cards);}int before=cards.getChildCount();View next=appendProviderTitles(cards,row.titles);added+=cards.getChildCount()-before;if(firstNew==null)firstNew=next;}
            for(LinearLayout cards:rails.values())total+=cards.getChildCount();status.setText(total+" titles loaded · Page "+page[0]+" · "+added+" new titles");
            LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(180),dp(46));mp.topMargin=dp(20);content.addView(more,mp);if(!any){more.setText("No more titles");more.setEnabled(false);}else if(page[0]>1&&firstNew!=null){View target=firstNew;target.post(()->{if(token==generation){target.requestFocus();((android.widget.HorizontalScrollView)target.getParent().getParent()).smoothScrollTo(target.getLeft(),0);}});}
        });};more.setOnClickListener(v->fetch[0].run());content.addView(more,new LinearLayout.LayoutParams(dp(180),dp(46)));fetch[0].run();
    }
    View appendProviderTitles(LinearLayout cards,List<CatalogClient.Title> titles){
        java.util.Set<String> seen=new java.util.HashSet<>();for(int i=0;i<cards.getChildCount();i++)seen.add((String)cards.getChildAt(i).getTag());View first=null;
        for(CatalogClient.Title title:titles)if(seen.add(title.slug)){View item=card(title,(TextView)cards.getTag());cards.addView(item);if(first==null)first=item;}return first;
    }
    private View card(CatalogClient.Title title,TextView preview){
        LinearLayout card=column();card.setTag(title.slug);card.setPadding(dp(8),dp(8),dp(8),dp(10));card.setBackground(rounded(PANEL,0));card.setFocusable(true);card.setClickable(true);
        ImageView poster=new ImageView(this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);poster.setBackground(rounded(0xff263146,0));card.addView(poster,new LinearLayout.LayoutParams(-1,dp(220)));image(poster,title.poster);
        TextView name=label(title.title,15);name.setMinLines(2);name.setPadding(0,dp(9),0,dp(5));card.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView meta=text(metadata(title,null),12);meta.setTextColor(0xffdec881);card.addView(meta,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(190),-2);cp.rightMargin=dp(14);card.setLayoutParams(cp);
        card.setOnFocusChangeListener((v,f)->{card.setBackground(rounded(f?0xff26334b:PANEL,f?Color.WHITE:0));poster.animate().alpha(f?1:.88f).setDuration(160).start();card.animate().scaleX(f?1.025f:1f).scaleY(f?1.025f:1f).translationZ(f?dp(4):0).setDuration(160).start();if(f)preview.setText(title.title+"    "+metadata(title,null));});
        card.setOnClickListener(v->showDetail(title));return card;
    }
    private void image(ImageView view,String url){
        if(url==null||url.isEmpty())return;
        // Decode only artwork that reaches the viewport. A TV must not hold
        // hundreds of full poster bitmaps simply because it has many rails.
        view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                if(view.getGlobalVisibleRect(new android.graphics.Rect())){
                    view.getViewTreeObserver().removeOnPreDrawListener(this);
                    CatalogClient.image(url,(bitmap,error)->{if(bitmap!=null&&!isDestroyed()&&view.isAttachedToWindow())view.setImageBitmap(bitmap);});
                }
                return true;
            }
        });
    }
    private void showDetail(CatalogClient.Title title){
        detailTitle=title;season=1;begin("detail");header();hero(title,null,true);
        TextView loadingDetail=text("Loading synopsis, cast, and episodes…",14);loadingDetail.setTextColor(MUTED);content.addView(loadingDetail);
        int token=generation;CatalogClient.details(title,(data,error)->{if(token!=generation)return;if(error!=null){loadingDetail.setText("Details are unavailable. You can still try Play.");return;}
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
        if(searchDebouncer!=null)searchDebouncer.cancel();if(value.trim().isEmpty())return;int token=++generation;results.removeAllViews();TextView status=text("Searching…",15);status.setPadding(0,dp(20),0,0);results.addView(status);CatalogClient.search(value.trim(),(rows,error)->{if(token!=generation)return;results.removeAllViews();if(error!=null){results.addView(button("Try search again",()->search(value)));return;}LinearLayout main=content;content=results;for(CatalogClient.Row row:rows)if(row.titles.isEmpty())content.addView(text("No matching titles. Try another name.",18));else rail(row);content=main;});
    }
    private void play(CatalogClient.Title title,int season,int episode,boolean restart){
        if(resolver!=null)return;final String path=title.isSeries()?"/tv/"+title.id+"/"+season+"/"+episode:title.slug;
        loadingTitle.setText(title.title);image(loadingArt,title.backdrop);busy(true,"Finding the best available source…");StreamResolver current=new StreamResolver(this,root);resolver=current;int token=generation;
        current.start(CatalogClient.SITE+path+"?play=true",new StreamResolver.Callback(){
            @Override public void ready(String url,HashMap<String,String> headers,String tracks){if(token!=generation)return;resolver=null;busy(false,"");Intent intent=new Intent(NativeActivity.this,PlayerActivity.class);intent.setData(Uri.parse(url));intent.putExtra("headers",headers);intent.putExtra("tracks",tracks);intent.putExtra("title",title.title);intent.putExtra("poster",title.poster);intent.putExtra("key",path);intent.putExtra("position",restart?0:resumePosition(title,path));returnFromPlayer=true;startActivityForResult(intent,410);}
            @Override public void progress(String message){if(token==generation)loadingText.setText(message);}
            @Override public void failed(String message){if(token!=generation)return;resolver=null;busy(false,"");new AlertDialog.Builder(NativeActivity.this).setTitle("Unable to start playback").setMessage("This source is unavailable. Please try again shortly.").setPositiveButton("Try again",(d,w)->play(title,season,episode,restart)).setNegativeButton("Cancel",null).show();}
        });
    }
    private void cancelResolver(){if(resolver!=null){resolver.close();resolver=null;}if(loading!=null)loading.setVisibility(View.GONE);}
    private String resumeKey(CatalogClient.Title title){String local=library.resumeKey(title);if(library.position(local)>0)return local;return account==null?title.slug:account.resume(title).slug;}
    private long resumePosition(CatalogClient.Title title,String path){long local=library.position(path);if(local>0)return local;CatalogClient.Title remote=account==null?title:account.resume(title);return remote.slug.equals(path)?remote.resumeMs:0;}
    private void renderAccountRows(){
        if(accountRows==null)return;accountRows.removeAllViews();LinearLayout main=content;content=accountRows;
        if(account!=null&&account.signedIn){
            if(account.profile==null){content.addView(button("Choose your Movy profile",this::showAccount));}
            else {for(CatalogClient.Row row:account.rows){if(screen.equals("saved")&&row.name.startsWith("Continue Watching"))continue;rail(row);}if(account.partial){TextView note=text("Some Movy account lists couldn’t refresh.",13);note.setTextColor(MUTED);content.addView(note);}}
        }else {TextView note=text("Connect your Movy account for your lists and Continue Watching.",14);note.setTextColor(MUTED);note.setPadding(0,dp(16),0,dp(8));content.addView(note);content.addView(button("Sign in to Movy",this::showAccount),new LinearLayout.LayoutParams(dp(180),dp(44)));}
        content=main;
    }
    private void readAccount(boolean accountScreen){
        int token=generation;if(accountClient==null)accountClient=new AccountClient(this,root);
        if(accountScreen){loadingTitle.setText("Your Movy account");busy(true,"Connecting securely…");}
        accountClient.request("read",null,(snapshot,error)->{
            if(token!=generation)return;
            if(accountScreen)busy(false,"");
            if(error==null){account=snapshot;if(accountScreen)renderAccount();else renderAccountRows();}
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
        accountClient.request(action,args,(snapshot,error)->{if(token!=generation)return;busy(false,"");if(error!=null){new AlertDialog.Builder(this).setTitle("Movy account").setMessage(error.getMessage()).setPositiveButton("OK",null).show();return;}account=snapshot;renderAccount();});
    }
    private void error(String message,Runnable retry){TextView t=text(message,17);t.setPadding(0,dp(20),0,dp(16));content.addView(t);content.addView(button("Try again",retry),new LinearLayout.LayoutParams(dp(150),dp(46)));}
    @Override public void onBackPressed(){if(resolver!=null){cancelResolver();return;}if(!screen.equals("home"))showHome();else new AlertDialog.Builder(this).setTitle("Exit Movy?").setPositiveButton("Exit",(d,w)->finish()).setNegativeButton("Keep browsing",null).show();}
    private void checkUpdate(){
        updater=new Updater();updater.checkForUpdate(this,new Updater.PromptCallback(){
            @Override public void onUpdateAvailable(String version,String notes,Runnable download){new AlertDialog.Builder(NativeActivity.this).setTitle("Movy "+version+" is ready").setMessage(notes).setPositiveButton("Update",(d,w)->download.run()).setNegativeButton("Later",null).show();}
            @Override public void onDownloadComplete(){Toast.makeText(NativeActivity.this,"Update downloaded. Follow the install prompt.",Toast.LENGTH_LONG).show();}
            @Override public void onInstallBlocked(Runnable retry){new AlertDialog.Builder(NativeActivity.this).setTitle("Update ready").setPositiveButton("Install",(d,w)->retry.run()).setNegativeButton("Later",null).show();}
            @Override public void onError(String message){}
        });
    }
    @Override protected void onPause(){super.onPause();if(updater!=null)updater.onActivityPaused();cancelResolver();}
    @Override protected void onResume(){super.onResume();if(updater!=null)updater.onActivityResumed(this);if(returnFromPlayer){returnFromPlayer=false;if(detailTitle!=null)showDetail(detailTitle);}}
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
