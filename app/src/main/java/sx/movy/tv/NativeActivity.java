package sx.movy.tv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native remote-first shell. Movy is used as a metadata service while browse
 * views are shown by Android widgets rather than a full-page WebView. */
public class NativeActivity extends android.app.Activity {
    private static final int BG = Color.rgb(8, 10, 15), RED = Color.rgb(229, 9, 20);
    private FrameLayout root;
    private LinearLayout content;
    private ProgressBar spinner;
    private WebView resolver;
    private CatalogClient.Title selected;
    private boolean showingDetail;
    private boolean refreshAfterWebsite;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        root = new FrameLayout(this); root.setBackgroundColor(BG); setContentView(root); showHome();
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextColor(Color.WHITE); t.setTextSize(size); t.setGravity(Gravity.CENTER_VERTICAL); return t;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private void beginScreen() {
        root.removeAllViews(); ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(40), dp(22), dp(40), dp(36)); scroll.addView(content);
        root.addView(scroll); spinner = new ProgressBar(this); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER); root.addView(spinner, p);
    }
    private Button action(String label) {
        Button b = new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); b.setBackgroundColor(RED); b.setFocusable(true);
        b.setOnFocusChangeListener((v, focus) -> v.setAlpha(focus ? 1f : .78f)); return b;
    }
    private void header(boolean back) {
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(0, 0, 0, dp(18));
        TextView logo = text("MOVY", 25); logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD); logo.setTextColor(RED); top.addView(logo, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (back) { Button b = action("Back"); b.setOnClickListener(v -> showHome()); top.addView(b, new LinearLayout.LayoutParams(dp(100), dp(46))); }
        else {
            Button search = action("Search"); search.setOnClickListener(v -> showSearch()); top.addView(search, new LinearLayout.LayoutParams(dp(100), dp(46)));
            Button account = action("Account"); account.setOnClickListener(v -> openWebsite("/account")); LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(108), dp(46)); ap.setMargins(dp(12),0,0,0); top.addView(account, ap);
        }
        content.addView(top);
    }
    private void showHome() {
        showingDetail = false; beginScreen(); header(false); TextView welcome = text("Browse what’s on now", 20); welcome.setTypeface(Typeface.DEFAULT, Typeface.BOLD); content.addView(welcome);
        TextView note = text("The catalogue refreshes from Movy whenever you open the app.", 14); note.setTextColor(0xffb9bec8); content.addView(note, new LinearLayout.LayoutParams(-1, dp(34)));
        CatalogClient.home((rows, error) -> { spinner.setVisibility(View.GONE); if (error != null) showError("Couldn’t refresh the catalogue."); else renderRows(rows); });
    }
    private void renderRows(List<CatalogClient.Row> rows) {
        if (rows == null || rows.isEmpty()) { showError("No catalogue rows were returned."); return; }
        for (CatalogClient.Row row : rows) {
            TextView title = text(row.name, 19); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(38)); tp.topMargin = dp(16); content.addView(title,tp);
            HorizontalScrollView rail = new HorizontalScrollView(this); rail.setHorizontalScrollBarEnabled(false); LinearLayout cards = new LinearLayout(this); cards.setPadding(dp(4),0,dp(4),dp(5));
            for (CatalogClient.Title item : row.titles) cards.addView(card(item));
            rail.addView(cards); content.addView(rail, new LinearLayout.LayoutParams(-1, dp(244)));
        }
        content.getChildAt(0).requestFocus();
    }
    private View card(final CatalogClient.Title item) {
        LinearLayout card = column(); card.setPadding(0,0,dp(12),0); card.setFocusable(true); card.setClickable(true); card.setBackgroundColor(0xff161a23);
        card.setOnFocusChangeListener((v, focused) -> { v.setScaleX(focused ? 1.06f : 1f); v.setScaleY(focused ? 1.06f : 1f); v.setBackgroundColor(focused ? 0xff343946 : 0xff161a23); });
        card.setOnClickListener(v -> showDetail(item));
        ImageView poster = new ImageView(this); poster.setBackgroundColor(0xff222733); poster.setScaleType(ImageView.ScaleType.CENTER_CROP); card.addView(poster, new LinearLayout.LayoutParams(dp(142), dp(196)));
        TextView name = text(item.title, 13); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(name,new LinearLayout.LayoutParams(dp(142),dp( thirty())));
        CatalogClient.image(item.poster, (bitmap, error) -> { if (bitmap != null) poster.setImageBitmap(bitmap); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(154), dp(232)); cp.setMargins(0,0,dp(6),0); card.setLayoutParams(cp); return card;
    }
    private int thirty() { return dp(32); }
    private void showSearch() {
        beginScreen(); header(true); TextView h = text("Search Movy", 24); h.setTypeface(Typeface.DEFAULT, Typeface.BOLD); content.addView(h);
        LinearLayout bar = new LinearLayout(this); bar.setPadding(0,dp(18),0,dp(16)); EditText query = new EditText(this); query.setSingleLine(true); query.setHint("Movies, shows, and anime"); query.setHintTextColor(0xff9299a6); query.setTextColor(Color.WHITE); query.setTextSize(18); query.setBackgroundColor(0xff202531); bar.addView(query,new LinearLayout.LayoutParams(0,dp(54),1)); Button go=action("Search"); go.setOnClickListener(v -> runSearch(query.getText().toString())); LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(110),dp(54)); gp.setMargins(dp(12),0,0,0);bar.addView(go,gp); content.addView(bar); query.requestFocus();
    }
    private void runSearch(String value) {
        if (value.trim().isEmpty()) return; spinner.setVisibility(View.VISIBLE); CatalogClient.search(value.trim(), (rows,error)-> { spinner.setVisibility(View.GONE); if(error!=null) showError("Search is unavailable. Try again shortly."); else renderRows(rows); });
    }
    private void showDetail(CatalogClient.Title item) {
        selected=item; showingDetail=true; beginScreen(); header(true);
        TextView kind=text(item.slug.startsWith("/tv/") ? "SERIES" : "MOVIE", 13); kind.setTextColor(0xffb8beca); content.addView(kind);
        TextView title=text(item.title, 34); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); content.addView(title,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView meta=text((item.year.isEmpty()?"":item.year+"  ") + (item.rating.isEmpty()?"":"★ "+item.rating),16); meta.setTextColor(0xffc4c9d3); content.addView(meta);
        TextView promise=text("Ready to stream. The source is resolved only after you press Play.",16); promise.setTextColor(0xffc4c9d3); LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(70));pp.topMargin=dp(12);content.addView(promise,pp);
        LinearLayout buttons=new LinearLayout(this); Button play=action("▶  Play"); play.setTextSize(18); play.setOnClickListener(v->resolveAndPlay(item)); buttons.addView(play,new LinearLayout.LayoutParams(dp(170),dp(58))); Button web=action("Open page"); web.setOnClickListener(v->openWebsite(item.slug)); LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(dp(140),dp(58));wp.setMargins(dp(12),0,0,0);buttons.addView(web,wp);content.addView(buttons);
        play.requestFocus();
    }
    @SuppressLint("SetJavaScriptEnabled") private void resolveAndPlay(CatalogClient.Title item) {
        Toast.makeText(this,"Finding the best stream…",Toast.LENGTH_SHORT).show(); resolver=new WebView(this); resolver.getSettings().setJavaScriptEnabled(true); resolver.getSettings().setDomStorageEnabled(true); resolver.getSettings().setMediaPlaybackRequiresUserGesture(false); resolver.getSettings().setUserAgentString(resolver.getSettings().getUserAgentString()+" MovyTV/2.0"); CookieManager.getInstance().setAcceptCookie(true);
        resolver.setWebViewClient(new WebViewClient(){ @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { String u=request.getUrl().toString(); if(u.contains(".m3u8")){ final Map<String,String> headers=request.getRequestHeaders(); runOnUiThread(()->startPlayer(u,headers)); } return super.shouldInterceptRequest(view,request); }
            @Override public void onPageFinished(WebView view,String url){ view.evaluateJavascript("(function(){var b=[].slice.call(document.querySelectorAll('button,a')).find(function(x){return /^(play|play now)$/i.test((x.innerText||'').trim())});if(b)b.click();})()",null); }});
        root.addView(resolver,new FrameLayout.LayoutParams(1,1)); resolver.loadUrl(CatalogClient.SITE+item.slug);
    }
    private void startPlayer(String stream, Map<String,String> requestHeaders) {
        if(resolver==null)return;
        String cookie = CookieManager.getInstance().getCookie(stream);
        resolver.stopLoading();root.removeView(resolver);resolver.destroy();resolver=null;
        Intent i=new Intent(this,PlayerActivity.class);i.setData(Uri.parse(stream));i.putExtra("title",selected.title);
        i.putExtra("referer",CatalogClient.SITE+selected.slug); i.putExtra("cookie",cookie);
        startActivity(i);
    }
    private void openWebsite(String path) { refreshAfterWebsite = true; Intent i=new Intent(this,MainActivity.class);i.putExtra("url",CatalogClient.SITE+path);startActivity(i); }
    private void showError(String message) { TextView error=text(message,17);error.setTextColor(0xffff9a9a);content.addView(error);Button retry=action("Try again");retry.setOnClickListener(v->showHome());content.addView(retry,new LinearLayout.LayoutParams(dp(130),dp(52))); }
    @Override public void onBackPressed(){ if(showingDetail)showHome(); else super.onBackPressed(); }
    @Override protected void onResume() { super.onResume(); if (refreshAfterWebsite) { refreshAfterWebsite = false; showHome(); } }
    @Override protected void onDestroy(){if(resolver!=null)resolver.destroy();super.onDestroy();}
}
