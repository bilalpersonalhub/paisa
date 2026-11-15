package app.paisa.tracker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A thin shell around the web app in /docs. The app's files are served from the APK
 * under a fixed https address so storage works as it does in a browser. Nothing is
 * loaded from the network.
 *
 * The page reaches the phone through the methods on {@link Bridge}; the shell reaches
 * the page by calling the window.paisa* functions the page defines.
 */
public class MainActivity extends Activity {
    static final String PREFS = "paisa";
    static final String ACTION_ADD_EXPENSE = "app.paisa.tracker.ADD_EXPENSE";
    static final String ACTION_ADD_INCOME = "app.paisa.tracker.ADD_INCOME";

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/index.html";
    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_FOLDER = 3;
    private static final int REQ_NOTIFY = 4;
    private static final int KEEP_AUTO_BACKUPS = 10;
    private static final String AUTO_PREFIX = "paisa-auto-";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingSave;
    private String pendingAction = "";
    private boolean pageReady;

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

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
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

        pendingAction = actionOf(getIntent());
        NudgeReceiver.schedule(this);
        web.loadUrl(START);
    }

    /** A launcher shortcut or the reminder notification was tapped while the app was open. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String a = actionOf(intent);
        if (a.isEmpty()) {
            return;
        }
        if (pageReady) {
            callPage("window.paisaAction && window.paisaAction('" + a + "')");
        } else {
            pendingAction = a;
        }
    }

    private static String actionOf(Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_ADD_EXPENSE.equals(a)) return "expense";
        if (ACTION_ADD_INCOME.equals(a)) return "income";
        return "";
    }

    private void callPage(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    // ---- automatic backups into a folder the user picked ----

    private Uri backupTree() {
        String s = prefs().getString("backupTree", "");
        return s.isEmpty() ? null : Uri.parse(s);
    }

    private String backupFolderName() {
        Uri tree = backupTree();
        if (tree == null) {
            return "";
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.lastIndexOf(':');
            String name = colon >= 0 ? id.substring(colon + 1) : id;
            return name.isEmpty() ? "Storage" : name;
        } catch (Exception e) {
            return "";
        }
    }

    /** Writes one backup file and trims older automatic backups. Runs off the UI thread. */
    private boolean writeAutoBackup(String name, String json) {
        Uri tree = backupTree();
        if (tree == null) {
            return false;
        }
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);

            // Existing automatic backups: replace today's, and find the oldest ones to remove.
            List<String> names = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            Cursor c = getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String n = c.getString(1);
                        if (n != null && n.startsWith(AUTO_PREFIX)) {
                            ids.add(c.getString(0));
                            names.add(n);
                        }
                    }
                } finally {
                    c.close();
                }
            }
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals(name)) {
                    DocumentsContract.deleteDocument(getContentResolver(),
                            DocumentsContract.buildDocumentUriUsingTree(tree, ids.get(i)));
                    names.remove(i);
                    ids.remove(i);
                    break;
                }
            }

            Uri doc = DocumentsContract.createDocument(getContentResolver(), parent, "application/json", name);
            if (doc == null) {
                return false;
            }
            try (OutputStream out = getContentResolver().openOutputStream(doc)) {
                if (out == null) {
                    return false;
                }
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }

            // File names carry the date, so sorting by name sorts by age.
            List<String> sorted = new ArrayList<>(names);
            Collections.sort(sorted);
            int extra = sorted.size() + 1 - KEEP_AUTO_BACKUPS;
            for (int i = 0; i < extra; i++) {
                int at = names.indexOf(sorted.get(i));
                try {
                    DocumentsContract.deleteDocument(getContentResolver(),
                            DocumentsContract.buildDocumentUriUsingTree(tree, ids.get(at)));
                } catch (Exception ignored) {
                    // An old file that cannot be removed is not a failed backup.
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- what the page can call ----

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

        /** Saves a backup or export through the system "save as" screen. */
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

        /** "expense" or "income" if the app was opened from a shortcut or the reminder; read once. */
        @JavascriptInterface
        public String takeAction() {
            String a = pendingAction;
            pendingAction = "";
            return a;
        }

        /** Name of the automatic-backup folder, or "" when none is chosen. */
        @JavascriptInterface
        public String backupFolder() {
            return backupFolderName();
        }

        @JavascriptInterface
        public void pickBackupFolder() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "No app available to choose a folder", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void clearBackupFolder() {
            Uri tree = backupTree();
            if (tree != null) {
                try {
                    getContentResolver().releasePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                    // Already gone.
                }
            }
            prefs().edit().remove("backupTree").apply();
        }

        /** Writes a backup into the chosen folder, then tells the page whether it worked. */
        @JavascriptInterface
        public void autoBackup(final String name, final String json) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean ok = writeAutoBackup(name, json);
                    callPage("window.paisaAutoBackup && window.paisaAutoBackup(" + ok + ")");
                }
            }).start();
        }

        /** Evening reminder on or off, and the hour (0-23) it fires. */
        @JavascriptInterface
        public void setNudge(boolean on, int hour) {
            prefs().edit().putBoolean("nudgeOn", on).putInt("nudgeHour", Math.max(0, Math.min(23, hour))).apply();
            NudgeReceiver.schedule(MainActivity.this);
            if (on && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
                    }
                });
            }
        }

        /** The page reports the date of the newest entry so the reminder knows whether to fire. */
        @JavascriptInterface
        public void setLastLogged(String date) {
            prefs().edit().putString("lastLogged", date == null ? "" : date).apply();
        }

        @JavascriptInterface
        public boolean canBiometric() {
            return Build.VERSION.SDK_INT >= 28 && Biometric.available(MainActivity.this);
        }

        /** Shows the fingerprint prompt; the page hears back through window.paisaBio(true|false). */
        @JavascriptInterface
        public void biometric() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT < 28) {
                        callPage("window.paisaBio && window.paisaBio(false)");
                        return;
                    }
                    try {
                        Biometric.prompt(MainActivity.this);
                    } catch (Exception e) {
                        callPage("window.paisaBio && window.paisaBio(false)");
                    }
                }
            });
        }
    }

    /** Kept in its own class so phones older than Android 9 never load the biometric APIs. */
    private static class Biometric {
        static boolean available(MainActivity a) {
            if (a.checkSelfPermission("android.permission.USE_BIOMETRIC") != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                android.hardware.biometrics.BiometricManager m =
                        a.getSystemService(android.hardware.biometrics.BiometricManager.class);
                return m != null && m.canAuthenticate()
                        == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
            }
            return a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        }

        static void prompt(final MainActivity a) {
            android.hardware.biometrics.BiometricPrompt p =
                    new android.hardware.biometrics.BiometricPrompt.Builder(a)
                            .setTitle("Unlock Paisa")
                            .setNegativeButton("Use PIN", a.getMainExecutor(),
                                    new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d, int which) {
                                            a.callPage("window.paisaBio && window.paisaBio(false)");
                                        }
                                    })
                            .build();
            p.authenticate(new CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            a.callPage("window.paisaBio && window.paisaBio(true)");
                        }

                        @Override
                        public void onAuthenticationError(int code, CharSequence message) {
                            a.callPage("window.paisaBio && window.paisaBio(false)");
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
        } else if (requestCode == REQ_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    prefs().edit().putString("backupTree", tree.toString()).apply();
                } catch (Exception e) {
                    Toast.makeText(this, "That folder cannot be used", Toast.LENGTH_LONG).show();
                }
            }
            callPage("window.paisaFolder && window.paisaFolder()");
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
