package com.mahesh.hindustorybooks.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.mahesh.hindustorybooks.R
import com.mahesh.hindustorybooks.data.CatalogRefresh
import com.mahesh.hindustorybooks.data.Prefs
import com.mahesh.hindustorybooks.data.RefreshResult
import com.mahesh.hindustorybooks.databinding.FragmentSettingsBinding
import com.mahesh.hindustorybooks.util.LocaleHelper

class SettingsFragment : Fragment() {

    private var binding: FragmentSettingsBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentSettingsBinding.inflate(inflater, container, false)
        binding = b
        val prefs = Prefs(requireContext())

        if (prefs.language == "en") b.langEn.isChecked = true else b.langHi.isChecked = true
        // APP zoom (app text size) — never book zoom. 0 = system default.
        when (prefs.appFontScale) {
            1.15f -> b.appzoomMedium.isChecked = true
            1.3f -> b.appzoomLarge.isChecked = true
            1.5f -> b.appzoomXlarge.isChecked = true
            else -> b.appzoomSystem.isChecked = true
        }

        b.langGroup.setOnCheckedChangeListener { _, checkedId ->
            val lang = if (checkedId == R.id.lang_en) "en" else "hi"
            if (prefs.language != lang) {
                prefs.language = lang
                LocaleHelper.apply(lang)
                requireActivity().recreate()
            }
        }
        b.appzoomGroup.setOnCheckedChangeListener { _, checkedId ->
            val scale = when (checkedId) {
                R.id.appzoom_medium -> 1.15f
                R.id.appzoom_large -> 1.3f
                R.id.appzoom_xlarge -> 1.5f
                else -> 0f
            }
            if (prefs.appFontScale != scale) {
                prefs.appFontScale = scale
                requireActivity().recreate()
            }
        }
        b.refreshButton.setOnClickListener {
            // Visible progress state: a slow refresh must never look dead.
            b.refreshButton.isEnabled = false
            b.refreshButton.text = getString(R.string.settings_refresh_checking)
            CatalogRefresh.refreshAsync(requireContext()) { result ->
                // The button ALWAYS comes back, whatever happens below.
                try {
                    if (!isAdded) {
                        // User navigated away mid-refresh: report via the
                        // application context so the result never vanishes
                        // and nothing here can crash on a detached fragment.
                        val ctx = activity?.applicationContext ?: return@refreshAsync
                        when (result) {
                            is RefreshResult.Success -> {
                                if (result.count > 0) {
                                    Toast.makeText(
                                        ctx,
                                        getString(R.string.toast_new_books, result.count),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            is RefreshResult.Failure -> {
                                Toast.makeText(
                                    ctx,
                                    getString(R.string.refresh_failed_title),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        return@refreshAsync
                    }
                    when (result) {
                        is RefreshResult.Success -> {
                            if (result.count > 0) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.toast_new_books, result.count),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    R.string.toast_already_current,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        is RefreshResult.Failure -> {
                            // FULL reason in a dialog, never a toast
                            // truncated to 3 items: a catalog-fetch failure
                            // shows its exception, validation failures list
                            // EVERY failed URL (the dialog scrolls).
                            val detail = if (result.failures.size == 1 &&
                                result.failures[0].startsWith("catalog.json:")
                            ) {
                                getString(R.string.refresh_failed_fetch, result.failures[0])
                            } else {
                                getString(
                                    R.string.refresh_failed_links,
                                    result.failures.joinToString("\n")
                                )
                            }
                            AlertDialog.Builder(requireContext())
                                .setTitle(R.string.refresh_failed_title)
                                .setMessage(detail)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                } finally {
                    b.refreshButton.isEnabled = true
                    b.refreshButton.text = getString(R.string.settings_refresh)
                }
            }
        }
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
