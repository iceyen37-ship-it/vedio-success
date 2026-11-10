package com.ansimjia.swallowcare;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import java.io.File;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.core.content.ContextCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.webkit.JavaScriptReplyProxy;
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import android.os.CancellationSignal;
import org.json.JSONObject;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Collections;
import android.Manifest;
import android.content.pm.PackageManager;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    // Bundled web app is served from a real https origin (not file://) so that
    // localStorage keeps a stable origin and embedded YouTube players get a valid referrer.
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/www/index.html";

    private WebView webView;
    private ProgressBar progressBar;
    private WebViewAssetLoader assetLoader;
    private CredentialManager credentialManager;

    // 手機通知：網頁按「開啟通知」→ 詢問權限 → 回傳通知代碼
    private JavaScriptReplyProxy pushReply;
    private String pendingLink;
    private boolean pageReady;
    private final ActivityResultLauncher<String> notifyPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (pushReply == null) return;
                if (granted) sendPushToken(pushReply);
                else reply(pushReply, "error", "denied");
            });

    // 「拍照記錄」：<input type="file"> 交給手機相機（或相簿）
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) return;
                Uri[] uris = null;
                if (result.getResultCode() == RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.getData() != null) {
                        uris = new Uri[]{data.getData()};          // 從相簿挑選
                    } else if (cameraUri != null) {
                        uris = new Uri[]{cameraUri};               // 剛拍的照片
                    }
                }
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
                cameraUri = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        // Android 15+ always draws edge-to-edge: pad content so it never sits under
        // the status bar, gesture bar or on-screen keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(APP_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        credentialManager = CredentialManager.create(this);
        initWebView();
        initGoogleBridge();
        initFileBridge();
        initPushBridge();
        initBackPressHandler();
        pendingLink = getIntent() != null ? getIntent().getStringExtra("link") : null;

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Everything is served through WebViewAssetLoader, so direct file access stays off.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " SwallowCareApp/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                openPushLink();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Embedded players (YouTube iframes) navigate inside their own frame.
                if (!request.isForMainFrame()) {
                    return false;
                }
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) {
                    return false;
                }
                // Anything else (tel:, mailto:, maps, other websites) opens outside the app.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // No app can handle this link; stay on the current page.
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                try {
                    File dir = new File(getCacheDir(), "images");
                    dir.mkdirs();
                    File photo = File.createTempFile("meal_", ".jpg", dir);
                    cameraUri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", photo);
                    camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    camera = null;
                    cameraUri = null;
                }
                Intent gallery = new Intent(Intent.ACTION_GET_CONTENT);
                gallery.addCategory(Intent.CATEGORY_OPENABLE);
                gallery.setType("image/*");
                Intent chooser;
                if (camera != null && params.isCaptureEnabled()) {
                    chooser = camera;                               // capture 屬性：直接開相機
                } else {
                    chooser = Intent.createChooser(gallery, "選擇照片");
                    if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                }
                try {
                    fileChooserLauncher.launch(chooser);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // The app does not use the camera or microphone.
                request.deny();
            }
        });
    }

    // Google 登入：網頁裡的 AndroidGoogle.postMessage({type:"signIn", clientId}) 會打開手機內建的
    // 「使用 Google 帳戶登入」畫面，拿到的 ID token 只回傳給 App 自己的網頁（appassets 網域）。
    private void initGoogleBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        WebViewCompat.addWebMessageListener(webView, "AndroidGoogle",
                Collections.singleton("https://" + APP_HOST),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || message.getData() == null) return;
                    try {
                        JSONObject req = new JSONObject(message.getData());
                        String type = req.optString("type");
                        if ("signIn".equals(type)) {
                            googleSignIn(req.optString("clientId"), replyProxy);
                        } else if ("signOut".equals(type)) {
                            credentialManager.clearCredentialStateAsync(new ClearCredentialStateRequest(),
                                    new CancellationSignal(), ContextCompat.getMainExecutor(this),
                                    new CredentialManagerCallback<Void, ClearCredentialException>() {
                                        @Override public void onResult(Void unused) { }
                                        @Override public void onError(ClearCredentialException e) { }
                                    });
                        }
                    } catch (Exception e) {
                        reply(replyProxy, "error", "failed");
                    }
                });
    }

    // 下載週報／照片：網頁傳來 base64 檔案，Android 10 以上存到「下載/安心呷」，舊手機改用分享選單
    private void initFileBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        WebViewCompat.addWebMessageListener(webView, "AndroidFiles",
                Collections.singleton("https://" + APP_HOST),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || message.getData() == null) return;
                    final String raw = message.getData();
                    new Thread(() -> {
                        String result;
                        try {
                            JSONObject req = new JSONObject(raw);
                            String name = req.optString("name", "安心呷").replaceAll("[\\\\/:*?\"<>|]", "_");
                            String mime = req.optString("mime", "application/octet-stream");
                            byte[] bytes = Base64.decode(req.optString("data"), Base64.DEFAULT);
                            result = saveDownload(name, mime, bytes);
                        } catch (Exception e) {
                            result = "{\"ok\":false}";
                        }
                        final String r = result;
                        runOnUiThread(() -> {
                            try { replyProxy.postMessage(r); } catch (Exception ignored) { }
                        });
                    }).start();
                });
    }

    private String saveDownload(String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/安心呷");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("no stream");
                out.write(bytes);
            }
            return "{\"ok\":true}";
        }
        File dir = new File(getCacheDir(), "exports");
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        runOnUiThread(() -> startActivity(Intent.createChooser(send, name)));
        return "{\"ok\":true,\"shared\":true}";
    }

    private void googleSignIn(String clientId, JavaScriptReplyProxy replyProxy) {
        if (clientId == null || clientId.isEmpty()) {
            reply(replyProxy, "error", "failed");
            return;
        }
        GetSignInWithGoogleOption option = new GetSignInWithGoogleOption.Builder(clientId).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        credentialManager.getCredentialAsync(this, request, new CancellationSignal(),
                ContextCompat.getMainExecutor(this),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        Credential c = response.getCredential();
                        if (c instanceof CustomCredential
                                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                            try {
                                GoogleIdTokenCredential g = GoogleIdTokenCredential.createFrom(c.getData());
                                reply(replyProxy, "token", g.getIdToken());
                                return;
                            } catch (Exception ignored) { }
                        }
                        reply(replyProxy, "error", "failed");
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        String code = e instanceof GetCredentialCancellationException ? "cancel"
                                : e instanceof NoCredentialException ? "no_account" : "failed";
                        reply(replyProxy, "error", code);
                    }
                });
    }

    private void reply(JavaScriptReplyProxy proxy, String key, String value) {
        try {
            proxy.postMessage(new JSONObject().put(key, value).toString());
        } catch (Exception ignored) { }
    }

    private void initPushBridge() {
        PushService.ensureChannel(this);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        if (FirebaseApp.getApps(this).isEmpty()) return;          // 沒有 Firebase 設定：網頁不會顯示通知選項
        WebViewCompat.addWebMessageListener(webView, "AndroidPush",
                Collections.singleton("https://" + APP_HOST),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!isMainFrame || message.getData() == null) return;
                    String type;
                    try { type = new JSONObject(message.getData()).optString("type"); }
                    catch (Exception e) { reply(replyProxy, "error", "failed"); return; }
                    boolean granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this,
                            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                    if (granted) { sendPushToken(replyProxy); return; }
                    if ("enable".equals(type)) {
                        pushReply = replyProxy;
                        notifyPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    } else {
                        reply(replyProxy, "error", "denied");
                    }
                });
    }

    private void sendPushToken(JavaScriptReplyProxy proxy) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) reply(proxy, "token", task.getResult());
            else reply(proxy, "error", "failed");
        });
    }

    // 點通知打開 App：跳到通知說的那一頁（只接受英文小寫的頁面名稱）
    private void openPushLink() {
        if (!pageReady || pendingLink == null) return;
        String go = Uri.parse(pendingLink).getQueryParameter("go");
        pendingLink = null;
        if (go == null || !go.matches("[a-z]{1,12}")) go = "home";
        webView.evaluateJavascript("window.openFromPush&&openFromPush('" + go + "')", null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String link = intent.getStringExtra("link");
        if (link != null) { pendingLink = link; openPushLink(); }
    }

    private void initBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
