package com.example.ttfviewer;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.io.OutputStream;

public class MainActivity extends Activity {
    WebView wv;
    ValueCallback<Uri[]> cb;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        wv = new WebView(this);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        wv.addJavascriptInterface(new Bridge(), "Android");
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cbk, FileChooserParams p) {
                if (cb != null) cb.onReceiveValue(null);
                cb = cbk;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "选择字体文件"), 1); }
                catch (Exception e) { cb = null; return false; }
                return true;
            }
        });
        wv.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && cb != null) {
            cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            cb = null;
        }
    }

    @Override public void onBackPressed() { super.onBackPressed(); }

    class Bridge {
        @JavascriptInterface public String savePng(String dataUrl, String name) {
            try {
                byte[] bytes = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TTFViewer");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                return "已保存到相册：Pictures/TTFViewer/" + name;
            } catch (Exception e) {
                return "保存失败：" + e.getMessage();
            }
        }
    }
}
