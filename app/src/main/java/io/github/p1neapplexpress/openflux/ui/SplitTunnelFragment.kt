package io.github.p1neapplexpress.openflux.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.AppItem
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.RussianAppsPreset
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app split tunnel picker. VpnServiceController already routes traffic
 * per-app (INTENT_PER_APP/APP_BYPASS/APP_LIST, wired since the original
 * OpenFlux PR #5); this screen is the missing UI, and VpnIntentFactory reads
 * SplitTunnelPreferences to populate those intent extras on every connect.
 */
class SplitTunnelFragment : BaseFragment() {

    private lateinit var prefs: SplitTunnelPreferences
    private lateinit var adapter: AppsAdapter
    private lateinit var selectedCountLabel: TextView
    private lateinit var modeToggle: MaterialButtonToggleGroup

    private var allApps: List<AppItem> = emptyList()
    private var suppressCallbacks = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_split_tunnel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = SplitTunnelPreferences(requireContext())

        val enableSwitch = view.findViewById<Switch>(R.id.enableSwitch)
        modeToggle = view.findViewById(R.id.modeToggle)
        val searchInput = view.findViewById<EditText>(R.id.searchInput)
        val appsList = view.findViewById<RecyclerView>(R.id.appsList)
        selectedCountLabel = view.findViewById(R.id.selectedCount)

        view.findViewById<View>(R.id.backButton).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        adapter = AppsAdapter { item ->
            val current = (if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) prefs.bypassApps else prefs.proxyApps).toMutableSet()
            if (item.isSelected) current.add(item.packageName) else current.remove(item.packageName)
            if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) prefs.bypassApps = current else prefs.proxyApps = current
            updateSelectedCount()
        }
        adapter.onListFiltered = { updateSelectedCount() }
        appsList.layoutManager = LinearLayoutManager(requireContext())
        appsList.adapter = adapter

        enableSwitch.isChecked = prefs.isEnabled
        modeToggle.check(if (prefs.mode == SplitTunnelPreferences.MODE_PROXY) R.id.modeProxy else R.id.modeBypass)
        suppressCallbacks = false

        enableSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            prefs.isEnabled = checked
        }

        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (suppressCallbacks || !isChecked) return@addOnButtonCheckedListener
            prefs.mode = if (checkedId == R.id.modeProxy) SplitTunnelPreferences.MODE_PROXY else SplitTunnelPreferences.MODE_BYPASS
            applySelectionToItems()
            updateSelectedCount()
        }

        searchInput.addTextChangedListener { text -> adapter.filter(text?.toString().orEmpty()) }

        view.findViewById<View>(R.id.presetRu).setOnClickListener {
            val ruPackages = RussianAppsPreset.PACKAGE_NAMES
            if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                prefs.bypassApps = allApps.map { it.packageName }.filter { it in ruPackages }.toSet()
            } else {
                prefs.proxyApps = allApps.map { it.packageName }.filter { it in ruPackages }.toSet()
            }
            applySelectionToItems()
            updateSelectedCount()
        }

        view.findViewById<View>(R.id.selectAll).setOnClickListener {
            val all = adapter.getDisplayedApps().map { it.packageName }.toSet()
            val merged = currentSelection() + all
            setCurrentSelection(merged)
            applySelectionToItems()
            updateSelectedCount()
        }

        view.findViewById<View>(R.id.clearAll).setOnClickListener {
            setCurrentSelection(emptySet())
            applySelectionToItems()
            updateSelectedCount()
        }

        loadApps()
    }

    private fun currentSelection(): Set<String> =
        if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) prefs.bypassApps else prefs.proxyApps

    private fun setCurrentSelection(pkgs: Set<String>) {
        if (prefs.mode == SplitTunnelPreferences.MODE_BYPASS) prefs.bypassApps = pkgs else prefs.proxyApps = pkgs
    }

    private fun applySelectionToItems() {
        val selection = currentSelection()
        allApps.forEach { it.isSelected = it.packageName in selection }
        adapter.submitList(allApps)
    }

    private fun updateSelectedCount() {
        val selectedInAll = allApps.count { it.packageName in currentSelection() }
        selectedCountLabel.text = getString(R.string.split_tunnel_selected_count, selectedInAll, allApps.size)
    }

    private fun loadApps() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pm = requireContext().packageManager
            val items = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { it.packageName != requireContext().packageName }
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { info ->
                        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        AppItem(
                            name = pm.getApplicationLabel(info).toString(),
                            packageName = info.packageName,
                            icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                            isSystem = isSystem,
                            isRussianPreset = info.packageName in RussianAppsPreset.PACKAGE_NAMES,
                        )
                    }
                    .filter { prefs.hideSystemApps.not() || !it.isSystem }
                    .sortedBy { it.name.lowercase() }
                    .toList()
            }

            if (!prefs.isInitialized()) {
                prefs.initializeDefaults(items.map { it.packageName }.toSet())
            }

            allApps = items
            applySelectionToItems()
            updateSelectedCount()
        }
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
