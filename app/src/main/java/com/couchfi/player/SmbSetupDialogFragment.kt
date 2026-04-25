package com.couchfi.player

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.couchfi.player.settings.AdvancedSettings
import com.couchfi.player.smb.SmbClient
import com.hierynomus.mssmb2.SMBApiException

/**
 * Structured SMB setup dialog — drop-in replacement for the YAML editor
 * for the common case of "configure my NAS." Form fields for host /
 * share / username / password / music path, a Test button that runs a
 * real connect on a background thread and reports the precise failure
 * (auth, network, share, music path) inline, and a Save that persists
 * via [AdvancedSettings] and triggers the same downstream
 * [Host.onAdvancedChanged] callback the YAML editor uses — so the
 * service reconfigures SMB and re-walks the library.
 *
 * The Advanced YAML editor is still available for power users who want
 * to edit `fir_headroom` or paste a config. This dialog is the
 * happy-path "set up my NAS from scratch" entry point.
 */
class SmbSetupDialogFragment : DialogFragment() {

    interface Host {
        fun onAdvancedChanged(headroomChanged: Boolean, smbChanged: Boolean)
    }

    private val host: Host? get() = activity as? Host
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var hostEdit: EditText
    private lateinit var shareEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var pathEdit: EditText
    private lateinit var status:   TextView
    private lateinit var btnTest:  Button
    private lateinit var btnSave:  Button

    @Volatile private var testInFlight: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.dialog_smb_setup, container, false)
        hostEdit  = v.findViewById(R.id.smb_host)
        shareEdit = v.findViewById(R.id.smb_share)
        userEdit  = v.findViewById(R.id.smb_user)
        passEdit  = v.findViewById(R.id.smb_password)
        pathEdit  = v.findViewById(R.id.smb_music_path)
        status    = v.findViewById(R.id.smb_status)
        btnTest   = v.findViewById(R.id.smb_test)
        btnSave   = v.findViewById(R.id.smb_save)
        val btnCancel: Button = v.findViewById(R.id.smb_cancel)

        // Pre-fill from persisted settings.
        val ctx = requireContext()
        hostEdit.setText(AdvancedSettings.smbHost(ctx))
        shareEdit.setText(AdvancedSettings.smbShare(ctx))
        userEdit.setText(AdvancedSettings.smbUser(ctx))
        passEdit.setText(AdvancedSettings.smbPassword(ctx))
        pathEdit.setText(AdvancedSettings.smbMusicPath(ctx))

        btnTest.setOnClickListener   { runTest() }
        btnSave.setOnClickListener   { saveAndDismiss() }
        btnCancel.setOnClickListener { dismiss() }

        return v
    }

    /** Connects via [SmbClient] on a background thread, reports precise
     *  category (auth / share / network / path / unknown). Doesn't
     *  persist — that's Save's job. */
    private fun runTest() {
        if (testInFlight) return
        val host  = hostEdit.text.toString().trim()
        val share = shareEdit.text.toString().trim()
        val user  = userEdit.text.toString().trim()
        val pass  = passEdit.text.toString()
        val path  = pathEdit.text.toString()

        if (host.isEmpty() || share.isEmpty() || user.isEmpty()) {
            showStatus("Fill in host, share, and username before testing.", error = true)
            return
        }

        testInFlight = true
        showStatus("Testing connection…", error = false)
        btnTest.isEnabled = false
        btnSave.isEnabled = false

        Thread {
            val result = probe(host, user, pass, share, path)
            mainHandler.post {
                testInFlight = false
                btnTest.isEnabled = true
                btnSave.isEnabled = true
                showStatus(result.message, error = !result.ok)
            }
        }.start()
    }

    private data class ProbeResult(val ok: Boolean, val message: String)

    /** Runs the same SmbClient flow the app uses for real, plus a
     *  list() of the music subpath to verify the user's path config. */
    private fun probe(
        host: String, user: String, pass: String, share: String, musicPath: String,
    ): ProbeResult {
        val client = SmbClient(host, user, pass, share)
        return try {
            val disk = client.connect()
            // Verify music_path resolves (empty = share root, always ok).
            if (musicPath.isNotBlank()) {
                try {
                    disk.list(musicPath)
                } catch (e: SMBApiException) {
                    val ntStatus = e.status
                    return ProbeResult(false,
                        "Music path '$musicPath' not found in share '$share' " +
                            "(SMB status $ntStatus). Check the path field.")
                }
            }
            ProbeResult(true, "Connection OK — share + music path resolve cleanly.")
        } catch (e: SMBApiException) {
            val s = e.status
            val human = when {
                s.toString().contains("LOGON_FAILURE", ignoreCase = true) ||
                    s.toString().contains("WRONG_PASSWORD", ignoreCase = true) ->
                    "Authentication failed — check username and password."
                s.toString().contains("BAD_NETWORK_NAME", ignoreCase = true) ||
                    s.toString().contains("BAD_NETWORK_PATH", ignoreCase = true) ->
                    "Share '$share' not found on host '$host' — check the share field."
                s.toString().contains("ACCESS_DENIED", ignoreCase = true) ->
                    "Permission denied — the user account can't access this share."
                else ->
                    "SMB error: $s. Check host / share / credentials."
            }
            ProbeResult(false, human)
        } catch (e: java.net.UnknownHostException) {
            ProbeResult(false, "Host '$host' couldn't be resolved. Check spelling and that the NAS is on this Wi-Fi.")
        } catch (e: java.net.SocketTimeoutException) {
            ProbeResult(false, "Timed out reaching '$host'. Check that the NAS is powered and on this Wi-Fi.")
        } catch (e: java.net.ConnectException) {
            ProbeResult(false, "Couldn't reach '$host' (network unreachable). Check Wi-Fi and that the NAS is on.")
        } catch (e: java.io.IOException) {
            ProbeResult(false, "Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (t: Throwable) {
            ProbeResult(false, "Unexpected error: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** Persist field values via [AdvancedSettings], notify the host so
     *  the service reconfigures SMB + re-walks the library, dismiss. */
    private fun saveAndDismiss() {
        val ctx = requireContext()
        val newHost  = hostEdit.text.toString().trim()
        val newShare = shareEdit.text.toString().trim()
        val newUser  = userEdit.text.toString().trim()
        val newPass  = passEdit.text.toString()
        val newPath  = pathEdit.text.toString()

        var changed = false
        if (newHost  != AdvancedSettings.smbHost(ctx))      { AdvancedSettings.setSmbHost(ctx, newHost);   changed = true }
        if (newShare != AdvancedSettings.smbShare(ctx))     { AdvancedSettings.setSmbShare(ctx, newShare); changed = true }
        if (newUser  != AdvancedSettings.smbUser(ctx))      { AdvancedSettings.setSmbUser(ctx, newUser);   changed = true }
        if (newPass  != AdvancedSettings.smbPassword(ctx))  { AdvancedSettings.setSmbPassword(ctx, newPass); changed = true }
        if (newPath  != AdvancedSettings.smbMusicPath(ctx)) { AdvancedSettings.setSmbMusicPath(ctx, newPath); changed = true }

        if (changed) host?.onAdvancedChanged(headroomChanged = false, smbChanged = true)
        dismiss()
    }

    private fun showStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(if (error) Color.parseColor("#E08A8A") else Color.parseColor("#9CD58F"))
        status.visibility = View.VISIBLE
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val d = super.onCreateDialog(savedInstanceState)
        d.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.65f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        return d
    }
}
