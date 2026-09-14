package sx.movy.tv;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Arrays;

/**
 * Full-screen WebView wrapper for movy.sx tuned for Android TV.
 *
 *  - Blocks ads/trackers/popups via request interception against a host blocklist.
 *  - Blocks JavaScript-initiated new windows (popunders).
 *  - Handles D-pad / remote keys so the site is navigable without a mouse.
 */
public class MainActivity extends AppCompatActivity {

    private static final String START_URL = "https://www.movy.sx/";

    // A tiny 1x1 transparent response used to satisfy blocked requests cleanly.
    private static final String EMPTY_MIME = "text/plain";

    private WebView webView;
    private FrameLayout rootLayout;
    private Updater updater;

    /** True on Android TV boxes, where orientation is fixed and meaningless. */
    private boolean television;
    /** True while the site's watch surface (or native fullscreen video) is up. */
    private boolean playbackActive;

    // Fullscreen HTML5 video state.
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private FrameLayout fullscreenContainer;
    private WebChromeClient chromeClient;

    /**
     * Blocklist of ad/tracker/popup host fragments. A request is blocked when its
     * host contains any of these substrings. Kept broad but conservative so the
     * streaming site itself and its video CDNs keep working.
     */
    private static final String[] BLOCK_FRAGMENTS = new String[]{
            // Reproduced QR / "Confirm you're not a robot" ad supplier.
            "fasolacaymans.com",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "adservice.google",
            "pagead2.googlesyndication",
            "adnxs.com",
            "adsystem.com",
            "amazon-adsystem.com",
            "adform.net",
            "adcolony.com",
            "adroll.com",
            "advertising.com",
            "outbrain.com",
            "taboola.com",
            "criteo.com",
            "criteo.net",
            "moatads.com",
            "scorecardresearch.com",
            "quantserve.com",
            "zedo.com",
            "propellerads.com",
            "propeller-tracking",
            "popads.net",
            "popcash.net",
            "popunder",
            "poptm.com",
            "onclickads",
            "onclckds.com",
            "clickadu.com",
            "hilltopads.net",
            "hilltopads.com",
            "adsterra.com",
            "adsterra.net",
            "adskeeper.com",
            "mgid.com",
            "revcontent.com",
            "juicyads.com",
            "exoclick.com",
            "exosrv.com",
            "trafficjunky.com",
            "trafficjunky.net",
            "adnium.com",
            "admaven",
            "a-ads.com",
            "adnpops",
            "coinhive.com",
            "coin-hive.com",
            "cryptaloot",
            "webcoin",
            "yandex.ru/ads",
            "mc.yandex.ru",
            "bidgear.com",
            "bidvertiser.com",
            "chaturbate.com",
            "histats.com",
            "hotjar.com",
            "mixpanel.com",
            "segment.com",
            "sentry.io",
            "sentry-cdn.com",
            "fundingchoicesmessages.google",
            "smartadserver.com",
            "adtng.com",
            "adtng.net",
            "ads-twitter.com",
            "facebook.com/tr",
            "connect.facebook.net",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "casalemedia.com",
            "contextweb.com",
            "sharethrough.com",
            "teads.tv",
            "3lift.com",
            "yieldmo.com",
            "adhigh.net",
            "adsco.re",
            "clickaine.com",
            "clicksgear.com",
            "luckyfindsonline",
            "vidoza",
            "ad-delivery",
            "adservetx",
            "trackingid",
            "clksite.com",
            "propu.sh",
            "ak.imgaft.com",
            "cdn.adpushup.com",
            "tsyndicate.com",
            "servedbyadbutler.com",
            "qr-code-ad",
            "scanverify",
            "aclib.net",
            "clickndownload",
            "adnxs-simple",
            "cpmstar.com",
            "adbetnet",
            "monetization",
            "runative-syndication.com",
            "galaksion.com",
            "adstadium",
            "vidzstore"
    };

    private final Set<String> blockSet = new HashSet<>();

    /** Origins allowed to receive the injected script and the playback bridge. */
    private static final Set<String> TRUSTED_ORIGINS = new HashSet<>(Arrays.asList(
            "https://movy.sx", "https://*.movy.sx",
            "https://vidy.st", "https://*.vidy.st"));

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while watching.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        television = isTelevision();
        applyOrientation();

        for (String s : BLOCK_FRAGMENTS) {
            blockSet.add(s);
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout = root;

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webView);
        setContentView(root);

        configureWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }

        // Check for an app update in the background (silent on failure).
        checkForAppUpdate();
    }

    /**
     * TV boxes stay landscape. Phones and tablets browse the catalogue in any
     * orientation the user holds, then rotate to landscape for playback.
     */
    private void applyOrientation() {
        if (television) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else if (playbackActive) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        }
    }

    private void setPlaybackActive(boolean active) {
        if (playbackActive == active) {
            return;
        }
        playbackActive = active;
        applyOrientation();
    }

    private boolean isTelevision() {
        UiModeManager modes = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (modes != null && modes.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        return getPackageManager().hasSystemFeature("android.software.leanback")
                || !getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    private void checkForAppUpdate() {
        updater = new Updater();
        updater.checkForUpdate(this, new Updater.PromptCallback() {
            @Override
            public void onUpdateAvailable(String versionName, String notes, Runnable startDownload) {
                String msg = "Version " + versionName + " is available.";
                if (notes != null && !notes.isEmpty()) {
                    msg += "\n\n" + notes;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Update Movy TV")
                        .setMessage(msg)
                        .setPositiveButton("Update now", (d, w) -> {
                            Toast.makeText(MainActivity.this,
                                    "Downloading update…", Toast.LENGTH_SHORT).show();
                            startDownload.run();
                        })
                        .setNegativeButton("Later", null)
                        .show();
            }

            @Override
            public void onDownloadComplete() {
                Toast.makeText(MainActivity.this,
                        "Update downloaded. Follow the prompt to install.",
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onInstallBlocked(Runnable retry) {
                // The APK is on disk; only the hand-off to the system installer
                // failed. Offer it again instead of silently going nowhere.
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Update ready")
                        .setMessage("The update downloaded but the installer did not open.")
                        .setPositiveButton("Install now", (d, w) -> retry.run())
                        .setNegativeButton("Later", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                // Silent: update problems must never block watching.
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        // Fit page contents to the WebView width instead of laying out at a wide
        // desktop viewport that overflows the TV. This is the key fix for the
        // "only 2/3 of the page shows" overflow. The injected JS also scales any
        // residual overflow to guarantee the whole width is visible.
        s.setUseWideViewPort(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        // Block JS from opening new windows automatically (popunders/popups).
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        // With multiple windows disabled, target=_blank can replace this page.
        // Enable window requests, then reject them in onCreateWindow below.
        s.setSupportMultipleWindows(true);
        // Use a desktop-ish UA so the site serves the full player experience.
        s.setUserAgentString(s.getUserAgentString() + " MovyTV/1.0");

        // Enable D-pad spatial navigation so the remote moves focus predictably
        // between real interactive elements instead of wandering.
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setInitialScale(0);
        webView.requestFocus();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        // Install before loadUrl/restoreState so the ad provider sees its own
        // disabled preference before initializing. This API also supports
        // explicit trusted embed origins without a JavaScript/native bridge.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, loadEnhanceJs(),
                    new HashSet<>(TRUSTED_ORIGINS));
        } else {
            Toast.makeText(this, "Update Android System WebView for the best remote controls",
                    Toast.LENGTH_LONG).show();
        }

        // Lets the page report when the watch surface is up so phones can
        // browse in portrait and rotate to landscape for playback. Origin
        // restricted like the injected script: no bridge on arbitrary origins.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "movyTvBridge",
                    new HashSet<>(TRUSTED_ORIGINS), new WebViewCompat.WebMessageListener() {
                        @Override
                        public void onPostMessage(WebView view, WebMessageCompat message,
                                                  android.net.Uri sourceOrigin, boolean isMainFrame,
                                                  JavaScriptReplyProxy replyProxy) {
                            onPageMessage(message.getData());
                        }
                    });
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && isBlockedHost(host.toLowerCase(Locale.ROOT))) {
                    // Refuse to navigate to an ad/redirect target.
                    return true;
                }
                // Keep the main browsing surface on Movy. Third-party player
                // frames and media requests still load via their own origins.
                return request.isForMainFrame() && !isSiteUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isSiteUrl(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String host = request.getUrl().getHost();
                if (host != null && isBlockedHost(host.toLowerCase(Locale.ROOT))) {
                    return new WebResourceResponse(EMPTY_MIME, "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                String url = request.getUrl().toString().toLowerCase(Locale.ROOT);
                if (isBlockedUrl(url)) {
                    return new WebResourceResponse(EMPTY_MIME, "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isSiteUrl(url)) view.evaluateJavascript(loadEnhanceJs(), null);
            }
        });

        // Handle popups + native HTML5 fullscreen video.
        chromeClient = new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                // Popups must never replace the TV browsing surface.
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // The player requested fullscreen: show the video view edge-to-edge.
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;

                fullscreenContainer = new FrameLayout(MainActivity.this);
                fullscreenContainer.setBackgroundColor(0xFF000000);
                fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                rootLayout.addView(fullscreenContainer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                setPlaybackActive(true);
                enterImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                webView.setVisibility(View.VISIBLE);
                webView.requestFocus();
                if (fullscreenContainer != null) {
                    rootLayout.removeView(fullscreenContainer);
                    fullscreenContainer = null;
                }
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                exitImmersive();
            }
        };
        webView.setWebChromeClient(chromeClient);
    }

    /** Handles the small JSON messages posted by tv_enhance.js. */
    private void onPageMessage(String data) {
        if (data == null) {
            return;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject(data);
            if ("playback".equals(json.optString("type"))) {
                setPlaybackActive(json.optBoolean("active", false));
            }
        } catch (org.json.JSONException ignored) {
            // Malformed messages are never worth interrupting playback for.
        }
    }

    private void enterImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private boolean isBlockedHost(String host) {
        for (String frag : blockSet) {
            if (frag.indexOf('/') < 0 && host.contains(frag)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSiteUrl(String url) {
        android.net.Uri uri = android.net.Uri.parse(url);
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null
                && (host.equalsIgnoreCase("movy.sx")
                || host.toLowerCase(Locale.ROOT).endsWith(".movy.sx"));
    }

    private boolean isBlockedUrl(String url) {
        for (String frag : blockSet) {
            if (frag.indexOf('/') >= 0 && url.contains(frag)) {
                return true;
            }
        }
        // Common ad path patterns.
        if (url.contains("/ads/") || url.contains("/adserver")
                || url.contains("popunder") || url.contains("/pop.js")
                || url.contains("banner_ad") || url.contains("/advert")) {
            return true;
        }
        return false;
    }

    /**
     * TV enhancement script (loaded from assets/tv_enhance.js): overscan fitting,
     * visible D-pad focus + spatial navigation, and ad/overlay removal.
     */
    private String enhanceJs;

    private String loadEnhanceJs() {
        if (enhanceJs != null) {
            return enhanceJs;
        }
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(getAssets().open("tv_enhance.js")))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            enhanceJs = sb.toString();
        } catch (java.io.IOException e) {
            enhanceJs = "";
        }
        return enhanceJs;
    }

    // ------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String action;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY: action = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: action = "pause"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: action = "toggle"; break;
            case KeyEvent.KEYCODE_MEDIA_REWIND: action = "rewind"; break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: action = "forward"; break;
            default: return super.dispatchKeyEvent(event);
        }
        // Handle once before WebView dispatch. Redispatch from onKeyDown can
        // recurse and would also allow both native and JS handlers to fire.
        if (event.getAction() == KeyEvent.ACTION_DOWN && webView != null
                && (event.getRepeatCount() == 0 || action.equals("rewind") || action.equals("forward"))) {
            webView.evaluateJavascript("window.__movyTV && window.__movyTV.media('" + action + "')", null);
        }
        return true;
    }
    // Remote control / D-pad handling
    // ------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                // If a video is fullscreen, Back should exit fullscreen first.
                if (customView != null && chromeClient != null) {
                    chromeClient.onHideCustomView();
                    return true;
                }
                if (event.getRepeatCount() > 0) return true;
                // Close site menus / player controls before leaving the page.
                if (webView != null) {
                    webView.evaluateJavascript("!!(window.__movyTV && window.__movyTV.back())", result -> {
                        if (!"true".equals(result) && webView != null) {
                            if (webView.canGoBack()) webView.goBack();
                            else new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Exit Movy TV?")
                                    .setNegativeButton("Keep watching", null)
                                    .setPositiveButton("Exit", (d, w) -> finish()).show();
                        }
                    });
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                if (webView != null && event.getRepeatCount() == 0) {
                    new AlertDialog.Builder(this).setTitle("Movy TV")
                            .setItems(new String[]{"Home", "Reload page", "Cancel"}, (d, item) -> {
                                if (item < 2 && customView != null) chromeClient.onHideCustomView();
                                if (item == 0) webView.loadUrl(START_URL);
                                else if (item == 1) webView.reload();
                            }).show();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Keep the app edge-to-edge; if a video is fullscreen use immersive.
            if (customView != null) {
                enterImmersive();
            } else {
                exitImmersive();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (updater != null) {
            updater.onActivityPaused();
        }
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
        // Picks up an install that could not start while we were backgrounded,
        // and a download that was waiting on the install-unknown-apps grant.
        if (updater != null) {
            updater.onActivityResumed(this);
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
