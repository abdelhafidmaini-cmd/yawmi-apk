package com.yawmi.personalassistant

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.yawmi.personalassistant.health.WebViewNativeBridge
import com.yawmi.personalassistant.health.YawmiHealthWearablePlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(YawmiHealthWearablePlugin::class.java)
        super.onCreate(savedInstanceState)

        // ربط الجسر الأصلي الإضافي (YawmiNativeBridge) لدعم البلوتوث، Health Connect، الكاميرا، والمنبهات مباشرة من WebView
        bridge?.webView?.let { wv ->
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.allowFileAccess = true
            wv.settings.mediaPlaybackRequiresUserGesture = false
            wv.addJavascriptInterface(WebViewNativeBridge(this, wv), "YawmiNativeBridge")
        }
    }
}
