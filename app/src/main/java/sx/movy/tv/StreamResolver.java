package sx.movy.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/** A bounded browser session started only by Play. No media URLs are logged or persisted. */
final class StreamResolver {
    interface Callback {
        void ready(String url, HashMap<String,String> headers, String tracks);
        void failed(String message);
        default void progress(String message) {}
    }
    private final Activity activity;
    private final FrameLayout parent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView browser;
    private Callback callback;
    private String candidate, pageUrl;
    private HashMap<String,String> candidateHeaders;
    private int bestHeight;
    private boolean closed;
    private String tracks = "{}";
    private boolean readingTracks;
    private String preferredServer;
    private boolean checkingSelected;
    private final LinkedHashMap<String,HashMap<String,String>> pendingPlaylists=new LinkedHashMap<>();
    private final Runnable selectedCheck=this::inspectSelectedServer;
    private final Runnable finish = this::deliver;
    StreamResolver(Activity activity, FrameLayout parent) { this.activity=activity; this.parent=parent; }
    @SuppressLint("SetJavaScriptEnabled") void start(String pageUrl, Callback callback) {
        start(pageUrl,"",callback);
    }
    @SuppressLint("SetJavaScriptEnabled") void start(String pageUrl,String preferredServer,Callback callback) {
        this.preferredServer=preferredServer==null?"":preferredServer;
        this.pageUrl=pageUrl; this.callback=callback;
        browser = new WebView(activity);
        WebSettings settings=browser.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(true);
        settings.setSupportMultipleWindows(true); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(browser,false);
        String script=script();
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(browser,"movyResolverBridge",new HashSet<>(Arrays.asList("https://movy.sx","https://www.movy.sx")),(view,message,origin,mainFrame,reply)->{
                if(closed||!mainFrame||!site(origin))return;
                try {org.json.JSONObject data=new org.json.JSONObject(message.getData());if("tracks".equals(data.optString("type")))tracks=data.toString();}catch(Exception ignored){}
            });
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(browser,script,new HashSet<>(Arrays.asList("https://movy.sx","https://*.movy.sx")));
        browser.setWebChromeClient(new WebChromeClient(){ @Override public boolean onCreateWindow(WebView v,boolean d,boolean g,android.os.Message m){return false;} });
        browser.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return r.isForMainFrame() && !site(r.getUrl());}
            @Override public void onPageFinished(WebView v,String url){ if(site(Uri.parse(url)))v.evaluateJavascript(script,null); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                Uri uri=r.getUrl(); String path=uri.getPath();
                if("https".equals(uri.getScheme()) && path!=null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                    String url=uri.toString(); HashMap<String,String> headers=new HashMap<>(r.getRequestHeaders());
                    activity.runOnUiThread(()->capture(url,headers));
                }
                return null;
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())fail("Movy could not load this title. Check your connection and try again.");}
        });
        // Keep a real layout viewport so the site's player mounts. The native
        // loading overlay covers it and it cannot receive remote focus.
        browser.setFocusable(false); browser.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        parent.addView(browser,0,new FrameLayout.LayoutParams(-1,-1)); browser.setAlpha(0f);
        handler.postDelayed(()->{if(candidate!=null)deliver();else fail("No playable stream was returned. Please try again shortly.");},45000);
        browser.loadUrl(pageUrl);
    }
    private void capture(String url,HashMap<String,String> headers){
        if(closed||readingTracks)return;
        if(!preferredServer.isEmpty()){
            pendingPlaylists.put(url,headers);if(pendingPlaylists.size()>12)pendingPlaylists.remove(pendingPlaylists.keySet().iterator().next());
            if(!checkingSelected){handler.removeCallbacks(selectedCheck);handler.postDelayed(selectedCheck,50);}return;
        }
        captureAccepted(url,headers);
    }
    private void inspectSelectedServer(){
        if(closed||readingTracks||checkingSelected)return;checkingSelected=true;
        String check="JSON.stringify({ready:window.__movyNativeServerChosen==="+org.json.JSONObject.quote(preferredServer)+",acceptDefault:!!window.__movyNativeDefaultRequested,urls:(window.__movyNativeBundles||[]).reduce(function(all,b){return all.concat(b.sources.map(function(s){return s.url;}));},[])})";
        browser.evaluateJavascript(check,value->{
            checkingSelected=false;if(closed||readingTracks)return;
            try{Object raw=new org.json.JSONTokener(value).nextValue();if(raw instanceof String){org.json.JSONObject data=new org.json.JSONObject((String)raw);if(data.optBoolean("ready")){
                Set<String> urls=new HashSet<>();org.json.JSONArray list=data.optJSONArray("urls");if(list!=null)for(int i=0;i<list.length();i++)urls.add(list.optString(i));
                for(String url:new ArrayList<>(pendingPlaylists.keySet()))if(data.optBoolean("acceptDefault")||urls.contains(url))captureAccepted(url,pendingPlaylists.remove(url));
            }}}catch(Exception ignored){}
            handler.removeCallbacks(selectedCheck);if(!pendingPlaylists.isEmpty())handler.postDelayed(selectedCheck,200);
        });
    }
    private void captureAccepted(String url,HashMap<String,String> headers){
        if(closed||readingTracks)return;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?:s|[-_/])(\\d{3,4})p",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url);
        int height=match.find()?Integer.parseInt(match.group(1)):0;
        if(candidate==null || height>=bestHeight){candidate=url;candidateHeaders=headers;bestHeight=height;}
        callback.progress(height>=2160?"Preparing your stream in 4K…":"Preparing your stream…");
        handler.removeCallbacks(finish);handler.postDelayed(finish,2500);
    }
    private void deliver(){
        if(closed||candidate==null||readingTracks)return;
        readingTracks=true;
        String selected=org.json.JSONObject.quote(candidate);
        String pull="(function(){var bundles=window.__movyNativeBundles||[],data=window.__movyNativeTracks||{};for(var i=0;i<bundles.length;i++){var sources=bundles[i].sources||[];for(var j=0;j<sources.length;j++)if(sources[j].url==="+selected+")data=bundles[i];}var info=window.__movyNativeServerInfo?window.__movyNativeServerInfo():{};data.servers=info.servers||[];data.server=info.server||'';return JSON.stringify(data);})()";
        browser.evaluateJavascript(pull,value->{
            if(closed)return;
            try{Object raw=new org.json.JSONTokener(value).nextValue();if(raw instanceof String){org.json.JSONObject data=new org.json.JSONObject((String)raw);if(data.optJSONArray("subtitles")!=null||data.optJSONArray("servers")!=null)tracks=data.toString();}}catch(Exception ignored){}
            complete();
        });
        // Some older TV WebViews fail to return an evaluation callback. Do
        // not strand playback; native subtitle lookup remains available.
        handler.postDelayed(this::complete,1000);
    }
    private void complete(){
        if(closed||candidate==null)return;
        String url=candidate; HashMap<String,String> headers=candidateHeaders;
        if(!headers.containsKey("Referer"))headers.put("Referer",pageUrl);
        if(!headers.containsKey("Origin")) {Uri origin=Uri.parse(pageUrl);headers.put("Origin",origin.getScheme()+"://"+origin.getAuthority());}
        headers.put("User-Agent",browser.getSettings().getUserAgentString());
        String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null)headers.put("Cookie",cookie);
        Callback done=callback;String bundle=tracks;close();done.ready(url,headers,bundle);
    }
    private void fail(String message){if(closed)return;Callback done=callback;close();done.failed(message);}
    void close(){closed=true;handler.removeCallbacksAndMessages(null);if(browser!=null){browser.stopLoading();parent.removeView(browser);browser.destroy();browser=null;}}
    static boolean site(Uri uri){String h=uri.getHost();return "https".equals(uri.getScheme())&&h!=null&&(h.equals("movy.sx")||h.endsWith(".movy.sx"));}
    private String script(){StringBuilder b=new StringBuilder("window.__movyNativeRequestedServer=").append(org.json.JSONObject.quote(preferredServer)).append(';');try(BufferedReader r=new BufferedReader(new InputStreamReader(activity.getAssets().open("native_resolver.js")))){String l;while((l=r.readLine())!=null)b.append(l).append('\n');}catch(Exception ignored){}return b.toString();}
}
