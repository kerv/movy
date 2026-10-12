package sx.movy.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.webkit.*;
import android.widget.FrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.*;
import java.io.*;
import java.util.*;

/** Origin-restricted connection to Movy's existing session. Only account
 * metadata crosses the bridge; its JWT remains in WebView local storage. */
final class AccountClient {
    static final class Snapshot {
        final boolean signedIn, partial;
        final String username,email;
        final JSONObject profile;
        final JSONArray profiles;
        final List<CatalogClient.Row> rows=new ArrayList<>();
        final List<CatalogClient.Title> completedSeries=new ArrayList<>(),recentHistory=new ArrayList<>();
        Snapshot(JSONObject data){
            signedIn=data.optBoolean("signedIn");partial=data.optBoolean("partial");username=data.optString("username");email=data.optString("email");profile=data.optJSONObject("profile");profiles=data.optJSONArray("profiles")==null?new JSONArray():data.optJSONArray("profiles");
            JSONArray list=data.optJSONArray("rows");if(list!=null)for(int i=0;i<list.length();i++){JSONObject row=list.optJSONObject(i);if(row==null)continue;JSONArray items=row.optJSONArray("titles");List<CatalogClient.Title> titles=new ArrayList<>();if(items!=null)for(int n=0;n<items.length();n++){JSONObject item=items.optJSONObject(n);if(item!=null&&item.optString("slug").matches("^/(movie|tv)/[0-9]+(?:/[0-9]+/[0-9]+)?$"))titles.add(new CatalogClient.Title(item));}if(!titles.isEmpty())rows.add(new CatalogClient.Row(row.optString("name","Movy"),titles));}
            for(String key:new String[]{"completedSeries","recentHistory"}){JSONArray items=data.optJSONArray(key);if(items!=null)for(int i=0;i<Math.min(40,items.length());i++){JSONObject item=items.optJSONObject(i);if(item!=null&&item.optString("slug").matches("^/(movie|tv)/[0-9]+(?:/[0-9]+/[0-9]+)?$"))(key.equals("completedSeries")?completedSeries:recentHistory).add(new CatalogClient.Title(item));}}
        }
        CatalogClient.Title resume(CatalogClient.Title title){for(CatalogClient.Row row:rows)for(CatalogClient.Title t:row.titles)if(t.id==title.id&&t.isSeries()==title.isSeries()&&t.resumeMs>0)return t;return title;}
    }
    private final Activity activity;
    private final FrameLayout parent;
    private final Handler handler=new Handler();
    private WebView browser;
    private boolean ready,closed;
    private int sequence;
    private final Map<Integer,CatalogClient.Result<Snapshot>> pending=new HashMap<>();
    private final List<String> queued=new ArrayList<>();
    AccountClient(Activity activity,FrameLayout parent){this.activity=activity;this.parent=parent;}
    void request(String action,JSONObject args,CatalogClient.Result<Snapshot> callback){
        if(closed){callback.accept(null,new IllegalStateException("Account connection closed"));return;}
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){callback.accept(null,new IllegalStateException("Update Android System WebView to connect your Movy account."));return;}
        int id=++sequence;pending.put(id,callback);
        try{JSONObject request=new JSONObject();request.put("id",id);request.put("action",action);request.put("args",args==null?new JSONObject():args);String command="window.__movyAccountRequest("+request+")";
            if(browser==null)create();if(ready)browser.evaluateJavascript(command,null);else queued.add(command);
            handler.postDelayed(()->{CatalogClient.Result<Snapshot> cb=pending.remove(id);if(cb!=null)cb.accept(null,new IllegalStateException("Movy’s account service timed out. Please try again."));},25000);
        }catch(Exception e){pending.remove(id);callback.accept(null,e);}
    }
    @SuppressLint("SetJavaScriptEnabled") private void create(){
        browser=new WebView(activity);browser.getSettings().setJavaScriptEnabled(true);browser.getSettings().setDomStorageEnabled(true);browser.getSettings().setAllowFileAccess(false);browser.getSettings().setAllowContentAccess(false);browser.setFocusable(false);browser.setAlpha(0);browser.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WebViewCompat.addWebMessageListener(browser,"movyAccountBridge",new HashSet<>(Arrays.asList("https://www.movy.sx")),(view,message,origin,mainFrame,reply)->{
            if(closed||!mainFrame||!"https://www.movy.sx".equals(origin.toString()))return;
            try{JSONObject data=new JSONObject(message.getData());CatalogClient.Result<Snapshot> callback=pending.remove(data.optInt("id"));if(callback!=null){if(data.optBoolean("ok"))callback.accept(new Snapshot(data.getJSONObject("snapshot")),null);else callback.accept(null,new IllegalStateException(data.optString("error","Account connection failed.")));}}catch(Exception ignored){}
        });
        browser.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}@Override public void onPageFinished(WebView v,String url){v.evaluateJavascript(asset(),value->{if(closed)return;ready=true;for(String command:queued)v.evaluateJavascript(command,null);queued.clear();});}});
        parent.addView(browser,0,new FrameLayout.LayoutParams(1,1));
        // A local, blank document under the existing Movy origin. No website
        // UI is loaded, and no catalogue/trailer/media runs in this connection.
        browser.loadDataWithBaseURL(CatalogClient.SITE+"/","<html><head></head><body></body></html>","text/html","UTF-8",null);
    }
    private String asset(){StringBuilder out=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(activity.getAssets().open("native_account.js")))){String line;while((line=r.readLine())!=null)out.append(line).append('\n');}catch(Exception ignored){}return out.toString();}
    void close(){closed=true;handler.removeCallbacksAndMessages(null);queued.clear();pending.clear();if(browser!=null){parent.removeView(browser);browser.destroy();browser=null;}}
}
