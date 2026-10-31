package takagi.ru.monica.autofill_ng.fixture;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Opt-in device fixture: loads Coolapk's real HTTPS document; never sets DOM values. */
public class CoolapkLiveFormActivity extends Activity {
    public static final String URL = "https://account.coolapk.com/auth/loginByCoolapk";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private boolean destroyed;
    private volatile int authWrites;
    private boolean observerStarted;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFocusableInTouchMode(true);
        TextView title = new TextView(this);
        title.setText("Coolapk official live login");
        title.setContentDescription("coolapk-instance-" + getIntent().getStringExtra("fixtureInstance"));
        layout.addView(title);
        status = new TextView(this);
        status.setContentDescription("coolapk-live-status");
        status.setText("live=LOADING");
        layout.addView(status);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("account.coolapk.com".equals(uri.getHost()) && uri.getPath() != null &&
                        uri.getPath().startsWith("/auth/") && !"GET".equals(request.getMethod())) authWrites++;
                return null;
            }
            @Override public void onPageFinished(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (!"https".equals(uri.getScheme()) || !"account.coolapk.com".equals(uri.getHost()) ||
                        !"/auth/loginByCoolapk".equals(uri.getPath())) {
                    status.setText("live=UNEXPECTED_ORIGIN");
                    return;
                }
                if (!observerStarted) {
                    observerStarted = true;
                    view.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) { observe(); }
                    });
                }
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        web.loadUrl(URL);
    }

    private void observe() {
        if (destroyed) return;
        // Read-only DOM observation. No rewritten HTML, hints, focus, values or page handlers.
        String script = "(function(){var u=document.getElementById('loginInput'),p=document.getElementById('passwordInput');"
                + "function state(e,v){return !e||!e.value?'EMPTY':e.value===v?'OK':'BAD'}"
                + "return {origin:location.origin,path:location.pathname,ready:!!u&&!!p,"
                + "uType:u&&u.type,pType:p&&p.type,uHint:u&&u.getAttribute('placeholder'),"
                + "pHint:p&&p.getAttribute('placeholder'),"
                + "user:state(u," + JSONObject.quote(AutofillFormFixtureActivity.USERNAME) + "),"
                + "password:state(p," + JSONObject.quote(AutofillFormFixtureActivity.PASSWORD) + "),"
                + "focus:document.activeElement&&document.activeElement.id};})()";
        web.evaluateJavascript(script, value -> {
            if (destroyed) return;
            try {
                JSONObject result = new JSONObject(value);
                boolean ready = result.optBoolean("ready") &&
                        "https://account.coolapk.com".equals(result.optString("origin")) &&
                        "/auth/loginByCoolapk".equals(result.optString("path")) &&
                        "text".equals(result.optString("uType")) && "password".equals(result.optString("pType")) &&
                        "用户名、邮箱或手机号".equals(result.optString("uHint")) &&
                        "请输入密码".equals(result.optString("pHint"));
                String text = "live=" + (ready ? "READY" : "FORM_CHANGED") +
                        " user=" + result.optString("user") + " password=" + result.optString("password") +
                        " focus=" + result.optString("focus") + " authWrites=" + authWrites;
                if (!text.contentEquals(status.getText())) status.setText(text);
            } catch (Exception error) {
                status.setText("live=OBSERVER_ERROR");
            }
            handler.postDelayed(this::observe, 200);
        });
    }

    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
