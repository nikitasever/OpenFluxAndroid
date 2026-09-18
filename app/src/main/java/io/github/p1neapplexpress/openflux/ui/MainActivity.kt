package io.github.p1neapplexpress.openflux.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TunnelLink
import io.github.p1neapplexpress.openflux.event.DeepLinkImport
import io.github.p1neapplexpress.openflux.event.EventBus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main, MainFragment(), "")
            .commit()


        lifecycleScope.launch {
            EventBus.events.collect { ev ->
                supportFragmentManager.fragments.forEach { f ->
                    if (f is BaseFragment) f.onNewEvent(ev)
                }
            }
        }

        handleIntent(intent)
    }

    // launchMode="singleTask": a link tapped while the app is already running
    // arrives here instead of a fresh onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val tunnel = TunnelLink.decode(intent?.data) ?: return
        DeepLinkImport.offer(tunnel)
    }

}
