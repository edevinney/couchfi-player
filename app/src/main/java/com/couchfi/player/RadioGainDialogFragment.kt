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

/**
 * Simple 6-step gain picker for the current radio station. Values are in
 * dB (−12, −9, −6, −3, 0, +3). Selecting applies immediately and the
 * Host persists the value per-station-URL.
 */
class RadioGainDialogFragment : DialogFragment() {

    interface Host {
        /** Currently-playing station URL, used as the key for per-station
         *  gain storage. Null if nothing is playing. */
        fun currentRadioStationUrl(): String?
        /** Current gain for the currently-playing station. */
        fun currentRadioGainDb(): Float
        /** Apply the new gain live and persist against the station. */
        fun onRadioGainPicked(gainDb: Float)
    }

    private val host: Host? get() = activity as? Host

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.dialog_radio_gain, container, false)
        val group: RadioGroup = v.findViewById(R.id.gain_group)

        val current = host?.currentRadioGainDb() ?: 0f
        val matchId = when {
            current <= -11f -> R.id.gain_minus_12
            current <=  -8f -> R.id.gain_minus_9
            current <=  -5f -> R.id.gain_minus_6
            current <=  -2f -> R.id.gain_minus_3
            current <=   1f -> R.id.gain_zero
            else            -> R.id.gain_plus_3
        }
        v.findViewById<RadioButton>(matchId).isChecked = true

        group.setOnCheckedChangeListener { _, id ->
            val db = when (id) {
                R.id.gain_minus_12 -> -12f
                R.id.gain_minus_9  ->  -9f
                R.id.gain_minus_6  ->  -6f
                R.id.gain_minus_3  ->  -3f
                R.id.gain_zero     ->   0f
                else               ->  +3f
            }
            host?.onRadioGainPicked(db)
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
