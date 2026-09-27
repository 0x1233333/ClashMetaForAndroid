package com.github.kr328.clash

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

class SmartStatusActivity : Activity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // allow the bundled page (file://) to query the loopback controller
            allowUniversalAccessFromFileURLs = true
            allowFileAccessFromFileURLs = true
        }
        webView.webViewClient = WebViewClient()
        setContentView(webView)

        if (savedInstanceState != null)
            webView.restoreState(savedInstanceState)
        else
            webView.loadUrl("file:///android_asset/smart.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack())
            webView.goBack()
        else
            super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        // 页面在前台时才让 WebView 跑 JS 定时器
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        // 这个页面每 5 秒拉 /connections、每 15 秒拉 /proxies(110 个节点含历史的完整 JSON)
        // 并向内核要一次 /group(约 90 KB)。以前切到后台没有 onPause 处理,setInterval
        // 会一直跑下去:内核不停做 JSON 序列化、WebView 不停解析,白耗 CPU/电量。
        webView.onPause()
        webView.pauseTimers()

        super.onPause()
    }
}
