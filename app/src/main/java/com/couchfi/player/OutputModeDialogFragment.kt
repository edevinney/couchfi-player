package com.couchfi.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.fragment.app.DialogFragment
import com.couchfi.player.settings.OutputMode
import com.couchfi.player.settings.Settings

/**
 * Small picker for the three output paths. Selecting a mode applies
 * the switch immediately and, if playback was active before, resumes
 * at the captured position — so comparing the paths on the same spot
 * of a track is a two-click gesture (open picker, choose mode).
 */
class OutputModeDialogFragment : DialogFragment() {

    interface Host {
        /** Apply [mode] and automatically resume if something was playing. */
        fun onOutputModePicked(mode: OutputMode)
    }

    private val host: Host? get() = activity as? Host

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.dialog_output_mode, container, false)
        val group: RadioGroup    = v.findViewById(R.id.out_group)
        val btnNative: RadioButton = v.findViewById(R.id.out_native)
        val btnNos:    RadioButton = v.findViewById(R.id.out_nos)
        val btn4x:     RadioButton = v.findViewById(R.id.out_4x)

        when (Settings.outputMode(requireContext())) {
            OutputMode.NATIVE     -> btnNative.isChecked = true
            OutputMode.DIRECT_NOS -> btnNos.isChecked    = true
            OutputMode.DIRECT_4X  -> btn4x.isChecked     = true
        }

        group.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.out_native -> OutputMode.NATIVE
                R.id.out_nos    -> OutputMode.DIRECT_NOS
                else            -> OutputMode.DIRECT_4X
            }
            Settings.setOutputMode(requireContext(), mode)
            host?.onOutputModePicked(mode)
            dismiss()
        }
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
}
