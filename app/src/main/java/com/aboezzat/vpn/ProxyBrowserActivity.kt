package com.aboezzat.vpn

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.aboezzat.vpn.proxy.ProxyBrowserConfig
import com.aboezzat.vpn.vpn.AboEzzatVpnService

class ProxyBrowserActivity : AppCompatActivity() {
    private var browser: WebView? = null
    private var address: EditText? = null
    private var progress: ProgressBar? = null
    private var status: TextView? = null
    private var controller: ProxyController? = null
    private var proxyOverrideRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val protocol = intent.getStringExtra(EXTRA_PROTOCOL).orEmpty()
        val proxyUrl = try {
            ProxyBrowserConfig.buildProxyUrl(host, port, protocol)
        } catch (_: IllegalArgumentException) {
            showMessage(getString(R.string.proxy_browser_invalid_profile))
            return
        }

        buildLayout()
        if (AboEzzatVpnService.isTunnelRunning()) {
            status?.text = getString(R.string.proxy_browser_vpn_running)
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            status?.text = getString(R.string.proxy_browser_unsupported)
            return
        }

        try {
            val proxyController = ProxyController.getInstance()
            controller = proxyController
            proxyOverrideRequested = true
            val config = ProxyConfig.Builder().addProxyRule(proxyUrl).build()
            proxyController.setProxyOverride(config, ContextCompat.getMainExecutor(this), Runnable {
                if (isFinishing || isDestroyed) return@Runnable
                status?.text = getString(R.string.proxy_browser_active)
                loadAddress()
            })
        } catch (error: Exception) {
            proxyOverrideRequested = false
            status?.text = getString(R.string.proxy_browser_apply_failed) + " " + (error.message ?: "")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (browser?.canGoBack() == true) browser?.goBack() else finish()
            }
        })
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            setBackgroundColor(Color.rgb(10, 16, 32))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.proxy_browser_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(36)))
        root.addView(TextView(this).apply {
            text = getString(R.string.proxy_browser_scope)
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))

        val addressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = getString(R.string.proxy_browser_address)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setText("https://example.com")
        }
        address = input
        addressRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        addressRow.addView(Button(this).apply {
            text = getString(R.string.proxy_browser_go)
            setOnClickListener { loadAddress() }
        }, LinearLayout.LayoutParams(dp(68), dp(48)))
        root.addView(addressRow, LinearLayout.LayoutParams(-1, -2))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadAddress()
                true
            } else false
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun addControl(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply {
                text = label
                textSize = 10f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        addControl(getString(R.string.proxy_browser_back)) { browser?.takeIf { it.canGoBack() }?.goBack() }
        addControl(getString(R.string.proxy_browser_forward)) { browser?.takeIf { it.canGoForward() }?.goForward() }
        addControl(getString(R.string.proxy_browser_reload)) { browser?.reload() }
        addControl(getString(R.string.proxy_browser_close)) { finish() }
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        status = TextView(this).apply {
            text = getString(R.string.proxy_browser_loading)
            setTextColor(Color.rgb(98, 230, 181))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))

        val webView = WebView(this)
        browser = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress?.progress = newProgress
                progress?.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                address?.setText(url.orEmpty())
                status?.text = getString(R.string.proxy_browser_loading)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                status?.text = getString(R.string.proxy_browser_loaded)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    status?.text = error?.description?.toString() ?: getString(R.string.proxy_browser_apply_failed)
                }
            }
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                status?.text = getString(R.string.proxy_browser_apply_failed)
                return true
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadAddress() {
        val url = try {
            ProxyBrowserConfig.normalizeUrl(address?.text?.toString().orEmpty())
        } catch (error: IllegalArgumentException) {
            status?.text = error.message ?: getString(R.string.proxy_browser_apply_failed)
            return
        }
        browser?.loadUrl(url)
    }

    private fun showMessage(message: String) {
        setContentView(TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(10, 16, 32))
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onPause() {
        browser?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browser?.onResume()
    }

    override fun onDestroy() {
        if (proxyOverrideRequested) {
            try {
                controller?.clearProxyOverride(ContextCompat.getMainExecutor(this), Runnable {})
            } catch (_: Exception) {
            }
        }
        browser?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        browser = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HOST = "proxy_browser_host"
        const val EXTRA_PORT = "proxy_browser_port"
        const val EXTRA_PROTOCOL = "proxy_browser_protocol"
    }
}
