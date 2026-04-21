package com.couchfi.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.couchfi.player.settings.ConfigYaml

/**
 * Full-text YAML editor for the experimenter-editable config — FIR
 * headroom plus SMB credentials / path. On Done the text is parsed and
 * applied; any errors surface inline and the dialog stays open so the
 * user can fix them without losing their edits.
 */
class AdvancedSettingsDialogFragment : DialogFragment() {

    interface Host {
        fun onAdvancedChanged(headroomChanged: Boolean, smbChanged: Boolean)
    }

    private val host: Host? get() = activity as? Host

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.dialog_advanced_settings, container, false)
        val editor:  EditText = v.findViewById(R.id.adv_yaml)
        val error:   TextView = v.findViewById(R.id.adv_error)
        val btnDone: Button   = v.findViewById(R.id.adv_done)

        editor.setText(ConfigYaml.read(requireContext()))

        btnDone.setOnClickListener {
            val text = editor.text.toString()
            val result = ConfigYaml.write(requireContext(), text)
            if (!result.ok) {
                error.text = result.error ?: "invalid config"
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            error.visibility = View.GONE
            if (result.headroomChanged || result.smbChanged) {
                host?.onAdvancedChanged(result.headroomChanged, result.smbChanged)
            }
            dismiss()
        }
        return v
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.75f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        return d
    }
}
