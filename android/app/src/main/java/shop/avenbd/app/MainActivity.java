package shop.avenbd.app;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebSettings;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = getBridge().getWebView();

        // Stop the page from being panned/scrolled sideways (the "black empty
        // area on the right"). Clamp the document to the screen width and
        // disable horizontal over-scroll.
        if (webView != null) {
            webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

            // Performance: use the HTTP cache so repeat loads are fast instead
            // of re-fetching everything, and keep the WebView hardware-layered
            // for smoother scrolling of long order lists.
            WebSettings settings = webView.getSettings();
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setDomStorageEnabled(true);
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

            getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    String js =
                        "(function(){try{" +
                        "var id='avenbd-fix';" +
                        "if(document.getElementById(id))return;" +
                        "var s=document.createElement('style');s.id=id;" +
                        "s.innerHTML='html,body{overflow-x:hidden !important;" +
                        "max-width:100% !important;width:100% !important;}';" +
                        "(document.head||document.documentElement).appendChild(s);" +
                        "}catch(e){}})();";
                    view.evaluateJavascript(js, null);
                }
            });
        }

        // Hardware back button: go back in the website history,
        // and only exit the app when there is no page to go back to.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = getBridge().getWebView();
                if (wv != null && wv.canGoBack()) {
                    wv.goBack();
                } else {
                    finish();
                }
            }
        });
    }
}
