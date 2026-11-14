package co.dothq.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import co.dothq.browser.managers.ApplicationManager
import co.dothq.browser.managers.StorageManager
import co.dothq.browser.subactivities.AddressBar
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView


class BrowserActivity : AppCompatActivity() {
    // Set by the navigation delegate so Back can step through page history.
    var canGoBack = false
    private var session: GeckoSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        this.initStatusbar();

        ApplicationManager().init(applicationContext, this);
        setContentView(R.layout.activity_main);

        val appSetup = StorageManager().get(applicationContext, "setup", "appValues", false);

        if (appSetup == false) return;
        val view = findViewById<GeckoView>(R.id.geckoview)
        val runtime = GeckoRuntime.getDefault(this)
        val session = GeckoSession()
        this.session = session

        session.open(runtime)
        view.setSession(session)

        session.navigationDelegate = BrowserDelegates().createNavigationDelegate("main", this, applicationContext);
        session.progressDelegate = BrowserDelegates().createProgressDelegate("main", this, applicationContext);

        var addressBarLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // There are no request codes
                val data: Intent? = result.data

                val url = data?.getStringExtra("targetURI")?.toUri();

                val host = url?.host.toString();
                val path = url.toString().replace("${url?.scheme}://${url?.host}", "");

                findViewById<TextView>(R.id.addressBarDomain).text = host.toString();

                if (path != "/") findViewById<TextView>(R.id.addressBarPath).text = path
                if (path == "/") findViewById<TextView>(R.id.addressBarPath).text = ""

                session.loadUri(url.toString())
            }
        }


        val addressBar = findViewById<LinearLayout>(R.id.addressBarContainer);

        addressBar.setOnClickListener {
            val addressBarIntent = Intent(this, AddressBar::class.java);

            addressBarIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            addressBarIntent.putExtra("currentURI", StorageManager().get(applicationContext, "currentUri", "appValues", "about:blank").toString())
            addressBarLauncher.launch(addressBarIntent)
            overridePendingTransition(0, 0);
        }

        val deeplinkHeader = findViewById<LinearLayout>(R.id.deeplinkAddressBar);
        val browserHeader = findViewById<LinearLayout>(R.id.normalAddressbar);

        // Opened from another app with a link: load the full URL (query string, port and
        // fragment included) and show the compact "opened from link" header.
        val uri = intent?.data
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            browserHeader.visibility = View.GONE;
            deeplinkHeader.visibility = View.VISIBLE;
            session.loadUri(uri.toString())
            deepLinkActivitySetup()
        } else {
            browserHeader.visibility = View.VISIBLE;
            deeplinkHeader.visibility = View.GONE;
            session.loadUri("https://ddg.gg")
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val session = this.session
        if (canGoBack && session != null) {
            session.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    fun initStatusbar() {
        if (resources.getString(R.string.mode) == "Day") {
            getWindow().getDecorView()
                .setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    fun deepLinkActivitySetup() {
        val deeplinkHeader = findViewById<LinearLayout>(R.id.deeplinkAddressBar);
        val browserHeader = findViewById<LinearLayout>(R.id.normalAddressbar);

        val deeplinkCloseButton = findViewById<LinearLayout>(R.id.closeButtonDeeplink);
        val deeplinkOpenInBrowserButton = findViewById<LinearLayout>(R.id.openInBrowserButtonDeeplink);

        deeplinkCloseButton.setOnClickListener {
            finish()
        }

        deeplinkOpenInBrowserButton.setOnClickListener {
            browserHeader.visibility = View.VISIBLE;
            deeplinkHeader.visibility = View.GONE;
        }
    }
}
