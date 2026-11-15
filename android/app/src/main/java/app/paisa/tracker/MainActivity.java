package app.paisa.tracker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A thin shell around the web app in /docs. The app's files are served from the APK
 * under a fixed https address so storage works as it does in a browser. Nothing is
 * loaded from the network.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/index.html";
    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked", null,
                            new ByteArrayInputStream(new byte[0]));
                }
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) {
                    path = "/index.html";
                }
                path = path.substring(1);
                try {
                    InputStream in = getAssets().open(path);
                    return new WebResourceResponse(mime(path), mime(path).startsWith("image/") ? null : "utf-8", in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null,
                            new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HOST.equals(request.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(START);
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }

    /** Called from the web app to save a backup or export through the system file picker. */
    private class Bridge {
        /** Lets the app's chosen look colour the system bars. */
        @JavascriptInterface
        public void setBars(final String status, final String nav, final boolean darkStatusIcons,
                            final boolean darkNavIcons) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Window w = getWindow();
                        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                        w.setStatusBarColor(Color.parseColor(status));
                        w.setNavigationBarColor(Color.parseColor(nav));
                        int flags = 0;
                        if (darkStatusIcons) {
                            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        }
                        if (darkNavIcons && Build.VERSION.SDK_INT >= 26) {
                            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        }
                        w.getDecorView().setSystemUiVisibility(flags);
                    } catch (Exception ignored) {
                        // A bad colour string must never crash the app.
                    }
                }
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String mimeType, String base64) {
            final byte[] data = Base64.decode(base64, Base64.DEFAULT);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingSave = data;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mimeType);
                    i.putExtra(Intent.EXTRA_TITLE, name);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        pendingSave = null;
                        Toast.makeText(MainActivity.this, "No app available to save files", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE) {
            byte[] bytes = pendingSave;
            pendingSave = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && bytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out == null) {
                        throw new IOException("no stream");
                    }
                    out.write(bytes);
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Could not save the file", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.paisaBack && window.paisaBack()) ? 'y' : 'n'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value == null || !value.contains("y")) {
                    finish();
                }
            }
        });
    }

}
