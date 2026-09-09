package com.mrdalse2.sbsplusproxy;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SbsLoginActivity extends Activity {
    private static final String LOGIN_URL = "https://join.sbs.co.kr/login/login.do";
    private static final String LIVE_URL = "https://www.sbs.co.kr/live/S03";
    private TextView status;
    private WebView web;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SbsAuthSession.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setText("SBS 공식 로그인 페이지입니다. 로그인 후 아래 버튼을 누르세요.");
        status.setTextSize(16f);
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button save = new Button(this);
        save.setText("로그인 세션 저장 후 닫기");
        root.addView(save, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (captureSession()) {
                    status.setText("SBS 로그인 세션 확인됨 · 저장 완료");
                }
            }
        });

        save.setOnClickListener(v -> {
            if (captureSession()) {
                SbsResolver.invalidateCache();
                Toast.makeText(this, "SBS 로그인 세션을 이 기기에 저장했습니다.", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                status.setText("LOGIN_JWT가 아직 없습니다. SBS 로그인을 완료한 뒤 다시 누르세요.");
                Toast.makeText(this, "아직 로그인 세션을 찾지 못했습니다.", Toast.LENGTH_SHORT).show();
                web.loadUrl(LIVE_URL);
            }
        });

        web.loadUrl(LOGIN_URL);
    }

    private boolean captureSession() {
        CookieManager cm = CookieManager.getInstance();
        String[] urls = {"https://www.sbs.co.kr", "https://join.sbs.co.kr", "https://apis.sbs.co.kr"};
        boolean found = false;
        for (String url : urls) {
            try { found |= SbsAuthSession.captureCookieHeader(cm.getCookie(url)); }
            catch (Exception ignored) {}
        }
        if (found) cm.flush();
        return found;
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }
}
