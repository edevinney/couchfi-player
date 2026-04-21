package com.couchfi.player

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import com.couchfi.player.settings.OutputMode
import com.couchfi.player.settings.Settings

/**
 * Settings dialog: output-mode radio group and a Re-index Library
 * action. Settings commit when Done is tapped (or the dialog is
 * otherwise dismissed) — there is no explicit Cancel.
 */
class SettingsDialogFragment : DialogFragment() {

    interface Host {
        fun onSettingsOutputModeChanged(mode: OutputMode)
        fun onSettingsReindexRequested()
        fun onSettingsOpenAdvanced()
    }

    private val host: Host? get() = activity as? Host

    private var pendingMode: OutputMode = OutputMode.DEFAULT
    private var pendingReindex: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.dialog_settings, container, false)

        val group: RadioGroup   = v.findViewById(R.id.settings_mode_group)
        val btnNative: RadioButton = v.findViewById(R.id.settings_mode_native)
        val btnNos:    RadioButton = v.findViewById(R.id.settings_mode_nos)
        val btn4x:     RadioButton = v.findViewById(R.id.settings_mode_4x)
        val btnReindex:  Button = v.findViewById(R.id.settings_reindex)
        val btnAdvanced: Button = v.findViewById(R.id.settings_advanced)
        val btnDone:     Button = v.findViewById(R.id.settings_done)

        val current = Settings.outputMode(requireContext())
        pendingMode = current
        when (current) {
            OutputMode.NATIVE     -> btnNative.isChecked = true
            OutputMode.DIRECT_NOS -> btnNos.isChecked    = true
            OutputMode.DIRECT_4X  -> btn4x.isChecked     = true
        }

        group.setOnCheckedChangeListener { _, id ->
            pendingMode = when (id) {
                R.id.settings_mode_native -> OutputMode.NATIVE
                R.id.settings_mode_nos    -> OutputMode.DIRECT_NOS
                else                      -> OutputMode.DIRECT_4X
            }
        }

        btnReindex.setOnClickListener {
            pendingReindex = true
            btnReindex.isEnabled = false
            btnReindex.text = "Will re-index on Done"
        }

        btnAdvanced.setOnClickListener {
            host?.onSettingsOpenAdvanced()
            dismiss()
        }
        btnDone.setOnClickListener { dismiss() }

        return v
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.window?.setLayout(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        return d
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        val ctx = context ?: return
        val previous = Settings.outputMode(ctx)
        if (pendingMode != previous) {
            Settings.setOutputMode(ctx, pendingMode)
            host?.onSettingsOutputModeChanged(pendingMode)
        }
        if (pendingReindex) host?.onSettingsReindexRequested()
    }
}
