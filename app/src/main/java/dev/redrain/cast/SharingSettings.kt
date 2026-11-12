package dev.redrain.cast

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.screencopy.sender.Settings
import dev.screencopy.sender.HardwareEncoderDiscovery
import dev.screencopy.sender.HardwareEncoders
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale

object SharingSettings {
    private class Choice(var value: String?) {
        var changed: () -> Unit = {}
    }

    internal const val PREFERENCES = "sharing_settings"
    internal const val REQUEST = "request"
    private var appContext: Context? = null

    fun load(context: Context, onError: (Exception) -> Unit): Settings {
        appContext = context.applicationContext
        return try {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            if (!preferences.contains(REQUEST)) Settings()
            else Settings.from(JSONObject(checkNotNull(preferences.getString(REQUEST, null)))).apply {
                videoEncoder = ""
                audioEncoder = ""
            }
        } catch (error: Exception) {
            onError(IOException(context.getString(R.string.rc_settings_read_error), error))
            Settings()
        }
    }

    fun summary(settings: Settings): String {
        val context = appContext ?: return settings.videoCodec.uppercase(Locale.ROOT)
        val size = if (settings.maxSize == 0) context.getString(R.string.rc_original_size)
            else context.getString(R.string.rc_summary_size, settings.maxSize)
        val audio = if (settings.audio) settings.audioCodec.uppercase(Locale.ROOT)
            else context.getString(R.string.rc_summary_audio_off)
        return context.getString(
            R.string.rc_summary, settings.videoCodec.uppercase(Locale.ROOT), size,
            if (settings.maxFps == 0) context.getString(R.string.rc_fps_uncapped_summary)
                else context.getString(R.string.rc_summary_fps, settings.maxFps),
            decimal(settings.videoBitrate, 1_000_000), audio
        )
    }

    fun show(activity: Activity, current: Settings, onSave: (Settings) -> Unit, onNotices: () -> Unit,
        onDiagnostics: (() -> Unit)? = null): AlertDialog =
        show(activity, current, onSave, onNotices, HardwareEncoderDiscovery.read(), onDiagnostics)

    internal fun show(activity: Activity, current: Settings, onSave: (Settings) -> Unit, onNotices: () -> Unit,
        encoders: HardwareEncoders, onDiagnostics: (() -> Unit)? = null): AlertDialog {
        appContext = activity.applicationContext
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun spacing(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
        val body = column().apply {
            setPadding(dp(24), dp(8), dp(24), dp(16))
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        lateinit var dialog: AlertDialog
        var choiceDialog: AlertDialog? = null
        val error = TextView(activity).apply {
            tag = "settingsError"
            textSize = 15f
            visibility = View.GONE
            setTextColor(activity.getColor(R.color.rc_error))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            body.addView(this, spacing(8))
        }

        fun label(parent: LinearLayout, title: Int, heading: Boolean = false): TextView =
            TextView(activity).apply {
                id = View.generateViewId()
                setText(title)
                textSize = if (heading) 20f else 14f
                setTextColor(activity.getColor(if (heading) R.color.rc_text else R.color.rc_text_secondary))
                if (heading) {
                    setTypeface(typeface, Typeface.BOLD)
                    isAccessibilityHeading = true
                }
                parent.addView(this, spacing(if (heading) 24 else 16))
            }

        fun edit(parent: LinearLayout, title: Int, value: String, key: String, numeric: Boolean = true, hint: Int? = null): EditText {
            val caption = label(parent, title)
            caption.tag = "${key}Label"
            return EditText(activity).apply {
                id = View.generateViewId()
                tag = key
                minHeight = dp(48)
                minimumHeight = dp(48)
                isSingleLine = true
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(value)
                setSelectAllOnFocus(true)
                if (hint != null) setHint(hint)
                caption.labelFor = id
                parent.addView(this, spacing())
            }
        }

        fun choice(parent: LinearLayout, title: Int, values: List<String>, labels: List<String>, value: String?, key: String): Choice {
            require(values.size == labels.size && (value == null || value in values)) { "Invalid choice configuration" }
            val caption = label(parent, title)
            val selected = value?.let(values::indexOf) ?: -1
            val button = Button(activity).apply {
                id = View.generateViewId()
                tag = key
                minimumHeight = dp(48)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = if (selected >= 0) labels[selected] else activity.getString(R.string.rc_select_hardware_codec)
                isEnabled = values.isNotEmpty()
                caption.labelFor = id
                parent.addView(this, spacing())
            }
            val selection = Choice(value)
            button.setOnClickListener {
                choiceDialog?.dismiss()
                val previousScroll = scroll.scrollY
                val picker = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setSingleChoiceItems(labels.toTypedArray(), selection.value?.let(values::indexOf) ?: -1) { picker, which ->
                        selection.value = values[which]
                        button.text = labels[which]
                        selection.changed()
                        picker.dismiss()
                    }
                    .setNegativeButton(R.string.rc_cancel, null)
                    .create()
                choiceDialog = picker
                picker.setOnDismissListener {
                    if (choiceDialog === picker) choiceDialog = null
                    scroll.post {
                        if (dialog.isShowing && !activity.isDestroyed) scroll.scrollTo(0, previousScroll)
                    }
                }
                picker.show()
            }
            return selection
        }

        fun button(parent: LinearLayout, title: Int, key: String, action: () -> Unit) = Button(activity).apply {
            tag = key
            setText(title)
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener { action() }
            parent.addView(this, spacing(12))
        }

        label(body, R.string.rc_settings_description)
        label(body, R.string.rc_video_heading, heading = true)
        val supportedVideo = listOf("h264", "h265", "av1").filter { encoders.names(it).isNotEmpty() }
        val savedVideoSupported = current.videoCodec in supportedVideo
        val videoCodecs = supportedVideo
        val videoLabels = videoCodecs.map {
            when (it) {
                "h264" -> "H.264"
                "h265" -> "H.265 (HEVC)"
                "av1" -> "AV1"
                else -> throw IllegalStateException("Unknown video codec")
            }
        }
        val videoCodec = choice(body, R.string.rc_video_codec, videoCodecs, videoLabels,
            if (savedVideoSupported) current.videoCodec else null, "videoCodec")
        if (!savedVideoSupported) {
            error.text = if (supportedVideo.isEmpty()) activity.getString(R.string.rc_no_hardware_video)
                else activity.getString(R.string.rc_saved_codec_unavailable, current.videoCodec.uppercase(Locale.ROOT))
            error.visibility = View.VISIBLE
        }
        val size = edit(body, R.string.rc_max_size, current.maxSize.toString(), "maxSize", hint = R.string.rc_max_size_hint)
        label(body, R.string.rc_max_size_hint)
        val fpsValues = (listOf(0, 15, 24, 25, 30, 48, 50, 60, 90, 120) + current.maxFps)
            .distinct().sorted().map(Int::toString)
        val fpsLabels = fpsValues.map { if (it == "0") activity.getString(R.string.rc_fps_uncapped) else it }
        val fps = choice(body, R.string.rc_fps, fpsValues, fpsLabels, current.maxFps.toString(), "maxFps")
        val bitrate = edit(body, R.string.rc_bitrate, decimal(current.videoBitrate, 1_000_000), "videoBitrate")
        val orientations = listOf("auto", "landscape", "portrait")
        val orientation = choice(
            body, R.string.rc_receiver_orientation, orientations,
            listOf(R.string.rc_orientation_auto, R.string.rc_orientation_landscape, R.string.rc_orientation_portrait).map(activity::getString),
            current.receiverOrientation, "receiverOrientation"
        )
        val audio = CheckBox(activity).apply {
            tag = "audio"
            setText(R.string.rc_audio_enabled)
            isChecked = current.audio
            minimumHeight = dp(48)
            body.addView(this, spacing(16))
        }

        val advanced = column().apply { visibility = View.GONE }
        lateinit var toggle: Button
        toggle = button(body, R.string.rc_advanced_show, "advancedToggle") {
            val expanded = advanced.visibility == View.GONE
            advanced.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.setText(if (expanded) R.string.rc_advanced_hide else R.string.rc_advanced_show)
        }
        body.addView(advanced, spacing())
        label(advanced, R.string.rc_advanced_description)
        label(advanced, R.string.rc_crop_heading, heading = true)
        val clearCrop = CheckBox(activity).apply {
            tag = "clearCrop"
            setText(R.string.rc_crop_clear)
            isChecked = current.cropWidth == 0
            minimumHeight = dp(48)
            advanced.addView(this, spacing(8))
        }
        val cropWidth = edit(advanced, R.string.rc_crop_width, current.cropWidth.toString(), "cropWidth")
        val cropHeight = edit(advanced, R.string.rc_crop_height, current.cropHeight.toString(), "cropHeight")
        val cropX = edit(advanced, R.string.rc_crop_x, current.cropX.toString(), "cropX")
        val cropY = edit(advanced, R.string.rc_crop_y, current.cropY.toString(), "cropY")
        val cropFields = listOf(cropWidth, cropHeight, cropX, cropY)
        fun updateCrop() { cropFields.forEach { it.isEnabled = !clearCrop.isChecked } }
        clearCrop.setOnCheckedChangeListener { _, _ -> updateCrop() }
        updateCrop()

        label(advanced, R.string.rc_audio_heading, heading = true)
        val audioCodecs = listOf("raw", "opus", "aac")
        val audioCodec = choice(
            advanced, R.string.rc_audio_codec, audioCodecs,
            listOf(R.string.rc_audio_raw, R.string.rc_audio_opus, R.string.rc_audio_aac).map(activity::getString),
            current.audioCodec, "audioCodec"
        )
        // Existing output-mode requests remain editable without silently changing their source.
        val sources = if (current.audioSource == "output") listOf("playback", "mic", "output") else listOf("playback", "mic")
        val sourceLabels = sources.map {
            activity.getString(when (it) {
                "mic" -> R.string.rc_audio_mic
                "output" -> R.string.rc_audio_output
                else -> R.string.rc_audio_playback
            })
        }
        val audioSource = choice(advanced, R.string.rc_audio_source, sources, sourceLabels, current.audioSource, "audioSource")
        val audioBitrate = edit(advanced, R.string.rc_audio_bitrate, decimal(current.audioBitrate, 1_000), "audioBitrate")
        val audioBitrateLabel = checkNotNull(advanced.findViewWithTag<TextView>("audioBitrateLabel"))
        val rawHint = label(advanced, R.string.rc_raw_hint)
        fun updateAudioBitrate() {
            val raw = audioCodec.value == "raw"
            audioBitrate.isEnabled = !raw
            audioBitrate.visibility = if (raw) View.GONE else View.VISIBLE
            audioBitrateLabel.visibility = audioBitrate.visibility
            rawHint.visibility = if (raw) View.VISIBLE else View.GONE
        }
        audioCodec.changed = ::updateAudioBitrate
        updateAudioBitrate()
        if (onDiagnostics != null)
            button(advanced, R.string.rc_playback_diagnostics, "playbackDiagnostics", onDiagnostics)
        val playbackStatistics = CheckBox(activity).apply {
            tag = "showPlaybackStatistics"
            setText(R.string.rc_playback_statistics)
            isChecked = current.showPlaybackStatistics
            minimumHeight = dp(48)
            advanced.addView(this, spacing(16))
        }
        label(advanced, R.string.rc_playback_statistics_hint).tag = "playbackStatisticsHint"
        val remoteDiagnostics = CheckBox(activity).apply {
            tag = "allowRemoteDiagnostics"
            text = "Allow unpaired LAN diagnostics (this process only)"
            isChecked = dev.redrain.cast.diagnostics.DiagnosticAccess.enabled
            isEnabled = playbackStatistics.isChecked
            minimumHeight = dp(48)
            advanced.addView(this, spacing(8))
        }
        TextView(activity).apply {
            tag = "remoteDiagnosticsWarning"
            text = dev.redrain.cast.diagnostics.DiagnosticAccess.warning
            advanced.addView(this, spacing(8))
        }
        remoteDiagnostics.setOnCheckedChangeListener { _, checked ->
            if (!checked) dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity, false, playbackStatistics.isChecked)
        }
        playbackStatistics.setOnCheckedChangeListener { _, checked ->
            remoteDiagnostics.isEnabled = checked
            if (!checked) {
                remoteDiagnostics.isChecked = false
                dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity, false, false)
            }
        }
        Button(activity).apply {
            tag = "remoteDiagnosticsStatus"
            text = "LAN diagnostics endpoint / status"
            minimumHeight = dp(48)
            setOnClickListener {
                val info = TextView(activity).apply {
                    text = dev.redrain.cast.diagnostics.DiagnosticAccess.status +
                        "\n\nRefresh by reopening. Save settings to apply consent. " +
                        dev.redrain.cast.diagnostics.DiagnosticAccess.warning
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                val status = AlertDialog.Builder(activity).setTitle("LAN diagnostics").setView(info)
                    .setPositiveButton(android.R.string.ok, null).create()
                status.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                status.show()
            }
            advanced.addView(this, spacing(8))
        }
        button(body, R.string.rc_notices, "notices", onNotices)
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.rc_settings)
            .setView(scroll)
            .setNegativeButton(R.string.rc_cancel, null)
            .setPositiveButton(R.string.rc_save, null)
            .create()
        dialog.setOnDismissListener {
            choiceDialog?.dismiss()
            choiceDialog = null
        }

        fun number(field: EditText, title: Int, multiplier: Int = 1): Int {
            try {
                return field.text.toString().trim().toBigDecimal().multiply(BigDecimal(multiplier)).intValueExact()
            } catch (exception: Exception) {
                val message = activity.getString(R.string.rc_invalid_number, activity.getString(title))
                field.error = message
                throw IllegalArgumentException(message, exception)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).minimumHeight = dp(48)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = supportedVideo.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minimumHeight = dp(48)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next: Settings
                try {
                    // Clone the entire request so hidden options and capture orientation survive edits.
                    next = Settings.from(current.saved(), validate = false).apply {
                        showPlaybackStatistics = playbackStatistics.isChecked
                        this.videoCodec = requireNotNull(videoCodec.value) { activity.getString(R.string.rc_select_hardware_codec) }
                        encoders.resolve(this.videoCodec, "")
                        maxSize = number(size, R.string.rc_max_size)
                        maxFps = checkNotNull(fps.value).toInt()
                        videoBitrate = number(bitrate, R.string.rc_bitrate, 1_000_000)
                        receiverOrientation = checkNotNull(orientation.value)
                        this.audio = audio.isChecked
                        videoEncoder = ""
                        this.cropWidth = if (clearCrop.isChecked) 0 else number(cropWidth, R.string.rc_crop_width)
                        this.cropHeight = if (clearCrop.isChecked) 0 else number(cropHeight, R.string.rc_crop_height)
                        this.cropX = if (clearCrop.isChecked) 0 else number(cropX, R.string.rc_crop_x)
                        this.cropY = if (clearCrop.isChecked) 0 else number(cropY, R.string.rc_crop_y)
                        this.audioCodec = checkNotNull(audioCodec.value)
                        this.audioSource = checkNotNull(audioSource.value)
                        if (this.audioCodec != "raw") this.audioBitrate = number(audioBitrate, R.string.rc_audio_bitrate, 1_000)
                        this.audioEncoder = ""
                        validate()
                    }
                    val saved = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit().putString(REQUEST, next.saved().toString()).commit()
                    if (!saved) throw IOException(activity.getString(R.string.rc_settings_write_error))
                } catch (exception: Exception) {
                    error.text = activity.getString(R.string.rc_settings_error, exception.message ?: exception.javaClass.simpleName)
                    error.visibility = View.VISIBLE
                    scroll.post { scroll.scrollTo(0, 0) }
                    return@setOnClickListener
                }
                onSave(next)
                dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity,
                    remoteDiagnostics.isChecked, next.showPlaybackStatistics)
                dialog.dismiss()
            }
        }
        body.requestFocus()
        dialog.show()
        return dialog
    }

    private fun decimal(value: Int, divisor: Int) =
        BigDecimal(value).divide(BigDecimal(divisor)).stripTrailingZeros().toPlainString()
}
