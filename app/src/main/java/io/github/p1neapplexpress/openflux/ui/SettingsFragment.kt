package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.activityViewModels
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.AppSettings

/**
 * Exposes the settings ported from FluxonAndroid (MTU, kill switch, LAN bypass,
 * IP stack, DNS mode incl. DNS-over-TLS, hotspot sharing) that VpnServiceController
 * / Tun2SocksLauncher / DnsTcpRelay already read from [AppSettings]. Changes that
 * affect an already-running tunnel only take effect on the next connect, so an
 * active tunnel is stopped and the user is told to reconnect.
 */
class SettingsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()
    private lateinit var settings: AppSettings

    private var suppressCallbacks = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = AppSettings(requireContext())

        view.findViewById<View>(R.id.appRoutingRow).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.main, SplitTunnelFragment())
                .addToBackStack("split_tunnel")
                .commit()
        }

        val languageSpinner = view.findViewById<Spinner>(R.id.languageSpinner)
        val mtuInput = view.findViewById<EditText>(R.id.mtuInput)
        val bypassLanSwitch = view.findViewById<Switch>(R.id.bypassLanSwitch)
        val killSwitchSwitch = view.findViewById<Switch>(R.id.killSwitchSwitch)
        val ipStackSpinner = view.findViewById<Spinner>(R.id.ipStackSpinner)
        val dnsModeSpinner = view.findViewById<Spinner>(R.id.dnsModeSpinner)
        val dnsPrimaryInput = view.findViewById<EditText>(R.id.dnsPrimaryInput)
        val dnsSecondaryInput = view.findViewById<EditText>(R.id.dnsSecondaryInput)
        val dotSwitch = view.findViewById<Switch>(R.id.dotSwitch)
        val hotspotSwitch = view.findViewById<Switch>(R.id.hotspotSwitch)
        val hotspotPortInput = view.findViewById<EditText>(R.id.hotspotPortInput)
        val hotspotAuthSwitch = view.findViewById<Switch>(R.id.hotspotAuthSwitch)
        val showSpeedSwitch = view.findViewById<Switch>(R.id.showSpeedSwitch)
        val verboseLogSwitch = view.findViewById<Switch>(R.id.verboseLogSwitch)
        val autoBootSwitch = view.findViewById<Switch>(R.id.autoBootSwitch)

        languageSpinner.setSelection(currentLanguageSelection())
        mtuInput.setText(settings.mtu.toString())
        bypassLanSwitch.isChecked = settings.bypassLan
        killSwitchSwitch.isChecked = settings.killSwitch
        ipStackSpinner.setSelection(settings.ipType)
        dnsModeSpinner.setSelection(settings.dnsMode)
        dnsPrimaryInput.setText(settings.customDnsPrimary)
        dnsSecondaryInput.setText(settings.customDnsSecondary)
        applyCustomDnsVisibility(settings.dnsMode, dnsPrimaryInput, dnsSecondaryInput)
        dotSwitch.isChecked = settings.dotEnabled
        hotspotSwitch.isChecked = settings.shareLanProxy
        hotspotPortInput.setText(settings.lanProxyPort.toString())
        hotspotAuthSwitch.isChecked = settings.socks5AuthEnabled
        showSpeedSwitch.isChecked = settings.showNotificationSpeed
        verboseLogSwitch.isChecked = settings.verboseLog
        autoBootSwitch.isChecked = settings.autoConnectOnBoot

        suppressCallbacks = false

        mtuInput.onCommit { text ->
            val value = text.toIntOrNull()
            if (value == null || value !in AppSettings.MIN_MTU..AppSettings.MAX_MTU) {
                Toast.makeText(requireContext(), R.string.settings_invalid_mtu, Toast.LENGTH_SHORT).show()
                mtuInput.setText(settings.mtu.toString())
                return@onCommit
            }
            settings.mtu = value
            restartIfActive()
        }

        bypassLanSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.bypassLan = checked
            restartIfActive()
        }

        killSwitchSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.killSwitch = checked
            restartIfActive()
        }

        ipStackSpinner.onItemSelected { position ->
            settings.ipType = position
            restartIfActive()
        }

        dnsModeSpinner.onItemSelected { position ->
            settings.dnsMode = position
            applyCustomDnsVisibility(position, dnsPrimaryInput, dnsSecondaryInput)
            restartIfActive()
        }

        dnsPrimaryInput.onCommit { text ->
            settings.customDnsPrimary = text.ifBlank { "1.1.1.1" }
            restartIfActive()
        }

        dnsSecondaryInput.onCommit { text ->
            settings.customDnsSecondary = text.ifBlank { "8.8.8.8" }
            restartIfActive()
        }

        dotSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.dotEnabled = checked
            restartIfActive()
        }

        hotspotSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.shareLanProxy = checked
            restartIfActive()
        }

        hotspotPortInput.onCommit { text ->
            val value = text.toIntOrNull()
            if (value == null || value !in 1..65535) {
                Toast.makeText(requireContext(), R.string.settings_invalid_port, Toast.LENGTH_SHORT).show()
                hotspotPortInput.setText(settings.lanProxyPort.toString())
                return@onCommit
            }
            settings.lanProxyPort = value
            restartIfActive()
        }

        hotspotAuthSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.socks5AuthEnabled = checked
            restartIfActive()
        }

        showSpeedSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.showNotificationSpeed = checked
        }

        verboseLogSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.verboseLog = checked
        }

        autoBootSwitch.setOnCheckedChangeListener { _, checked ->
            if (skip()) return@setOnCheckedChangeListener
            settings.autoConnectOnBoot = checked
        }

        languageSpinner.onItemSelected { position ->
            val locales = when (position) {
                1 -> LocaleListCompat.forLanguageTags("ru")
                2 -> LocaleListCompat.forLanguageTags("en")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            // Recreates every activity in the task with the new locale applied;
            // AppCompat persists the choice itself, no separate pref needed.
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    /** 0 = system default, 1 = Russian, 2 = English - matches @array/app_languages order. */
    private fun currentLanguageSelection(): Int {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            tag.startsWith("ru") -> 1
            tag.startsWith("en") -> 2
            else -> 0
        }
    }

    private fun skip(): Boolean = suppressCallbacks

    private fun applyCustomDnsVisibility(mode: Int, primary: EditText, secondary: EditText) {
        val visible = mode == AppSettings.DNS_MODE_CUSTOM
        primary.visibility = if (visible) View.VISIBLE else View.GONE
        secondary.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Network-affecting settings only take hold on (re)connect; stop the running tunnel so the user reconnects with the new values. */
    private fun restartIfActive() {
        if (vm.active.value is TunnelState.Running) {
            vm.stop()
            Toast.makeText(requireContext(), R.string.settings_title, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    /** Commits on focus loss (e.g. tapping elsewhere) rather than per-keystroke, so a partially typed value isn't validated mid-edit. */
    private fun EditText.onCommit(action: (String) -> Unit) {
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !skip()) action(text?.toString()?.trim().orEmpty())
        }
    }

    private fun Spinner.onItemSelected(action: (Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (skip()) return
                action(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
}
