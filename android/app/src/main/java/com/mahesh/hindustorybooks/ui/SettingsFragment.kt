package com.mahesh.hindustorybooks.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
        when (prefs.zoomDefault) {
            1.25f -> b.zoom125.isChecked = true
            1.5f -> b.zoom150.isChecked = true
            2.0f -> b.zoom200.isChecked = true
            else -> b.zoomFit.isChecked = true
        }

        b.langGroup.setOnCheckedChangeListener { _, checkedId ->
            val lang = if (checkedId == R.id.lang_en) "en" else "hi"
            if (prefs.language != lang) {
                prefs.language = lang
                LocaleHelper.apply(lang)
                requireActivity().recreate()
            }
        }
        b.zoomGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.zoomDefault = when (checkedId) {
                R.id.zoom_125 -> 1.25f
                R.id.zoom_150 -> 1.5f
                R.id.zoom_200 -> 2.0f
                else -> 1.0f
            }
        }
        b.refreshButton.setOnClickListener {
            b.refreshButton.isEnabled = false
            CatalogRefresh.refreshAsync(requireContext()) { result ->
                b.refreshButton.isEnabled = true
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
                        val detail = result.failures.take(3).joinToString("\n")
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.toast_refresh_failed, detail),
                            Toast.LENGTH_LONG
                        ).show()
                    }
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
