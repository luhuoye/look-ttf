package com.ufont.viewer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> fileCb;      // <input type="file"> 的回调
    private static final int REQ_FILE = 1001;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);          // 不开整页是死的
        s.setDomStorageEnabled(true);         // 不开 localStorage 抛异常
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);       // 藏掉难看的 +/- 按钮
        s.setSupportZoom(true);
        s.setTextZoom(100);                    // 忽略系统字体大小，防止网格错位
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        // file:// 页面里 fetch/localStorage 的 origin 隔离（Android 7+ 建议开）
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        // ★ 桥：JS 侧 window.Android.savePng(dataUrl, name)
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                // 只拦 http(s) 外链丢给系统浏览器；file:/// 与 blob: 留在 WebView 内
                if (u != null && ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {

            // ★★ 关键：不实现这个，页面里点「＋ 添加字体」100% 没反应
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);  // 防回调泄漏导致 input 永久失灵
                fileCb = cb;
                try {
                    Intent i = params.createIntent();
                    // 限定到文档选择器，并只给字体/所有文件
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/octet-stream", "font/ttf", "font/otf",
                            "application/x-font-ttf", "application/x-font-opentype", "*/*"});
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(i, "选择字体文件"), REQ_FILE);
                } catch (Exception e) {
                    fileCb.onReceiveValue(null);
                    fileCb = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        // 本 app 全程离线，不申请任何危险权限
        web.loadUrl("file:///android_asset/index.html");
    }

    /** JS 可调用的方法必须加 @JavascriptInterface（Android 4.2+ 强制） */
    class Bridge {
        /** 把 canvas 的 dataURL 存到「Pictures/TTFViewer/」，返回 "ok:路径" 或 "err:原因" */
        @JavascriptInterface
        public String savePng(String dataUrl, String name) {
            if (dataUrl == null || !dataUrl.startsWith("data:image/png;base64,"))
                return "err:数据格式不对";
            byte[] raw;
            try {
                raw = Base64.decode(dataUrl.substring("data:image/png;base64,".length()), Base64.DEFAULT);
            } catch (Exception e) {
                return "err:解码失败";
            }
            if (raw.length > 60 * 1024 * 1024) return "err:图片超过 60MB";

            String safe = name == null ? "glyph" : name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
            if (!safe.toLowerCase().endsWith(".png")) safe += ".png";

            // Android 10 (Q) 起：MediaStore 写入相册，零权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutputStream os = null;
                try {
                    MediaStore.LocalUriPermission ignored = null;
                    Uri col = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, safe);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TTFViewer/");
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri item = getContentResolver().insert(col, cv);
                    if (item == null) return "err:MediaStore 插入失败";
                    os = getContentResolver().openOutputStream(item);
                    if (os == null) return "err:无法打开输出流";
                    os.write(raw);
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(item, cv, null, null);
                    return "ok:Pictures/TTFViewer/" + safe;
                } catch (Exception e) {
                    return "err:" + e.getMessage();
                } finally {
                    try { if (os != null) os.close(); } catch (Exception ignored2) {}
                }
            }

            // Android 9 及以下：写应用私有目录，同样零权限（用户可从文件管理器/USB 拷出）
            try {
                File dir = new File(getExternalFilesDir(null), "TTFViewer");
                if (!dir.exists() && !dir.mkdirs()) return "err:创建目录失败";
                File out = new File(dir, safe);
                FileOutputStream fos = new FileOutputStream(out);
                fos.write(raw); fos.close();
                // 让相册立刻扫到
                Intent scan = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out));
                sendBroadcast(scan);
                return "ok:Android/data/com.ufont.viewer/files/TTFViewer/" + safe;
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void onReady() { /* 桥已注入，JS 侧据此切换保存策略 */ }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            Uri[] result = null;
            try {
                if (res == Activity.RESULT_OK && data != null) {
                    result = WebChromeClient.FileChooserParams.parseResult(res, data);
                }
            } catch (Exception ignored) {}
            fileCb.onReceiveValue(result);
            fileCb = null;                     // 必须置空，否则下次点按钮全部失效
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; }
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    // 保留：万一以后想加「从 URL 下载字体」功能才需要 INTERNET；现在不用
    @SuppressWarnings("unused")
    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("unused")
    private void ask(String perm) {
        ActivityCompat.requestPermissions(this, new String[]{perm}, 200);
    }
}
