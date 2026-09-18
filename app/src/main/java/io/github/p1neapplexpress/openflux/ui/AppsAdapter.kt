package io.github.p1neapplexpress.openflux.ui

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.AppItem

class AppsAdapter(
    private val onAppSelectionChanged: (AppItem) -> Unit
) : RecyclerView.Adapter<AppsAdapter.AppViewHolder>() {

    private var allApps: List<AppItem> = emptyList()
    private var displayedApps: MutableList<AppItem> = mutableListOf()
    private var currentQuery: String = ""

    var onListFiltered: ((count: Int) -> Unit)? = null

    fun submitList(apps: List<AppItem>) {
        allApps = ArrayList(apps)
        applyFilter()
    }

    fun filter(query: String) {
        currentQuery = query.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        displayedApps.clear()
        if (currentQuery.isEmpty()) {
            displayedApps.addAll(allApps)
        } else {
            for (app in allApps) {
                if (app.name.lowercase().contains(currentQuery) ||
                    app.packageName.lowercase().contains(currentQuery)
                ) {
                    displayedApps.add(app)
                }
            }
        }
        notifyDataSetChanged()
        onListFiltered?.invoke(displayedApps.size)
    }

    fun getDisplayedApps(): List<AppItem> = displayedApps

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_split_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(displayedApps[position])
    }

    override fun getItemCount(): Int = displayedApps.size

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.app_icon)
        private val name: TextView = itemView.findViewById(R.id.app_name)
        private val pkg: TextView = itemView.findViewById(R.id.app_package)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.app_checkbox)

        fun bind(item: AppItem) {
            name.text = item.name
            pkg.text = item.packageName
            if (item.icon != null) {
                icon.setImageDrawable(item.icon)
            } else {
                icon.setImageResource(R.mipmap.ic_launcher_round)
            }
            checkBox.isChecked = item.isSelected

            itemView.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                item.isSelected = !item.isSelected
                checkBox.isChecked = item.isSelected
                onAppSelectionChanged(item)
            }
        }
    }
}
