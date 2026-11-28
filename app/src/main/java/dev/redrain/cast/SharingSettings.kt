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
import dev.redrain.cast.diagnostics.IncidentStore
import dev.screencopy.sender.Settings
import dev.screencopy.sender.HardwareEncoderDiscovery
import dev.screencopy.sender.HardwareEncoders
import dev.screencopy.sender.CodecBitrateDiscovery
import dev.screencopy.sender.ProjectionInventory
import dev.screencopy.sender.VideoEncoderPreflight
import dev.screencopy.sender.VideoGeometryException
import dev.screencopy.sender.VideoGeometryReason
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale

object SharingSettings {
    private class Choice(var value: String?) {
        var changed: () -> Unit = {}
        var optionProvider: (() -> Pair<List<String>, List<String>>)? = null
    }
    private data class CropRect(val width: Int, val height: Int, val x: Int, val y: Int)

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
            Settings().apply {
                invalidSavedError = context.getString(R.string.rc_settings_read_error)
            }
        }
    }

    fun summary(settings: Settings): String {
        val context = appContext ?: return if (settings.video) settings.videoCodec.uppercase(Locale.ROOT)
            else "Video off · ${if (settings.audio) settings.audioCodec.uppercase(Locale.ROOT) else "Audio off"}"
        if (!settings.video) return context.getString(R.string.rc_summary_audio_only,
            context.getString(R.string.rc_summary_video_off),
            if (settings.audio) settings.audioCodec.uppercase(Locale.ROOT)
            else context.getString(R.string.rc_summary_audio_off))
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
        show(activity, current, onSave, onNotices, HardwareEncoderDiscovery.read(), onDiagnostics, null)

    fun show(activity: Activity, current: Settings, onSave: (Settings) -> Unit, onNotices: () -> Unit,
        onDiagnostics: (() -> Unit)?, onIncidentExport: (String) -> Unit): AlertDialog =
        show(activity, current, onSave, onNotices, HardwareEncoderDiscovery.read(), onDiagnostics, onIncidentExport)

    internal fun show(activity: Activity, current: Settings, onSave: (Settings) -> Unit, onNotices: () -> Unit,
        encoders: HardwareEncoders, onDiagnostics: (() -> Unit)? = null): AlertDialog =
        show(activity, current, onSave, onNotices, encoders, onDiagnostics, null)

    internal fun show(activity: Activity, current: Settings, onSave: (Settings) -> Unit, onNotices: () -> Unit,
        encoders: HardwareEncoders, onDiagnostics: (() -> Unit)?,
        onIncidentExport: ((String) -> Unit)?,
        storeFactory: (Activity) -> IncidentStore = { IncidentStore(it) }): AlertDialog {
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
        var closed = false
        fun parentActive() = !closed && dialog.isShowing && !activity.isFinishing && !activity.isDestroyed
        fun showChild(child: AlertDialog, onDismiss: () -> Unit = {}) {
            choiceDialog?.dismiss()
            choiceDialog = child
            child.setOnDismissListener {
                if (choiceDialog === child) choiceDialog = null
                onDismiss()
            }
            child.show()
        }
        val error = TextView(activity).apply {
            tag = "settingsError"
            textSize = 15f
            visibility = View.GONE
            setTextColor(activity.getColor(R.color.rc_error))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            body.addView(this, spacing(8))
        }
        var videoCapabilityError: CharSequence? = null
        var showingVideoCapabilityError = false

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
                val (availableValues, availableLabels) = selection.optionProvider?.invoke() ?: (values to labels)
                require(availableValues.size == availableLabels.size && availableValues.isNotEmpty())
                val previousScroll = scroll.scrollY
                val picker = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setSingleChoiceItems(availableLabels.toTypedArray(),
                        selection.value?.let(availableValues::indexOf) ?: -1) { picker, which ->
                        selection.value = availableValues[which]
                        button.text = availableLabels[which]
                        selection.changed()
                        picker.dismiss()
                    }
                    .setNegativeButton(R.string.rc_cancel, null)
                    .create()
                showChild(picker) {
                    scroll.post {
                        if (parentActive()) scroll.scrollTo(0, previousScroll)
                    }
                }
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
        val videoGroup = column()
        body.addView(videoGroup, spacing(16))
        label(videoGroup, R.string.rc_video_heading, heading = true)
        val videoEnabled = CheckBox(activity).apply {
            id = View.generateViewId()
            tag = "videoEnabled"
            setText(R.string.rc_video_enabled)
            isChecked = current.video
            minimumHeight = dp(48)
            videoGroup.addView(this, spacing())
        }
        val videoDisabledHint = label(videoGroup, R.string.rc_last_track_hint).apply {
            tag = "videoDisabledHint"
            visibility = View.GONE
            labelFor = videoEnabled.id
        }
        val videoControls = column()
        videoGroup.addView(videoControls, spacing())
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
        val videoCodec = choice(videoControls, R.string.rc_video_codec, videoCodecs, videoLabels,
            if (savedVideoSupported) current.videoCodec else null, "videoCodec")
        if (current.invalidSavedError != null) {
            error.text = current.invalidSavedError
            error.visibility = View.VISIBLE
        } else if (!savedVideoSupported) {
            videoCapabilityError = if (supportedVideo.isEmpty()) activity.getString(R.string.rc_no_hardware_video)
                else activity.getString(R.string.rc_saved_codec_unavailable, current.videoCodec.uppercase(Locale.ROOT))
            error.text = videoCapabilityError
            error.visibility = View.VISIBLE
            showingVideoCapabilityError = true
        }
        val maxSizeValues = (listOf(0, 1280, 1920, 2560, 3840) + current.maxSize).distinct().sorted()
        val maxSizeLabels = maxSizeValues.map {
            if (it == 0) activity.getString(R.string.rc_original_size)
            else activity.getString(R.string.rc_summary_size, it)
        }
        val size = choice(videoControls, R.string.rc_max_size, maxSizeValues.map(Int::toString), maxSizeLabels,
            current.maxSize.toString(), "maxSize")
        val maxSizeHint = label(videoControls, R.string.rc_max_size_hint)
        val fpsValues = (listOf(0, 15, 24, 25, 30, 48, 50, 60, 90, 120) + current.maxFps)
            .distinct().sorted().map(Int::toString)
        val fpsLabels = fpsValues.map { if (it == "0") activity.getString(R.string.rc_fps_uncapped) else it }
        val fps = choice(videoControls, R.string.rc_fps, fpsValues, fpsLabels, current.maxFps.toString(), "maxFps")
        val bitrateValues = ((1..30).map { it * 1_000_000 } + current.videoBitrate).distinct().sorted()
        val bitrateLabels = bitrateValues.map { "${decimal(it, 1_000_000)} Mbps" }
        val bitrate = choice(videoControls, R.string.rc_bitrate, bitrateValues.map(Int::toString), bitrateLabels,
            current.videoBitrate.toString(), "videoBitrate")
        val orientations = (listOf("auto", "landscape", "portrait") + current.receiverOrientation).distinct()
        val orientationLabels = orientations.map {
            when (it) {
                "auto" -> activity.getString(R.string.rc_orientation_auto)
                "landscape" -> activity.getString(R.string.rc_orientation_landscape)
                "portrait" -> activity.getString(R.string.rc_orientation_portrait)
                else -> "$it (${activity.getString(R.string.rc_current_unsupported)})"
            }
        }
        val orientation = choice(
            videoControls, R.string.rc_receiver_orientation, orientations, orientationLabels,
            current.receiverOrientation, "receiverOrientation"
        )
        val audioGroup = column()
        body.addView(audioGroup, spacing(16))
        label(audioGroup, R.string.rc_audio_heading, heading = true)
        val audio = CheckBox(activity).apply {
            id = View.generateViewId()
            tag = "audio"
            setText(R.string.rc_audio_enabled)
            isChecked = current.audio
            minimumHeight = dp(48)
            audioGroup.addView(this, spacing())
        }
        val audioDisabledHint = label(audioGroup, R.string.rc_last_track_hint).apply {
            tag = "audioDisabledHint"
            visibility = View.GONE
            labelFor = audio.id
        }
        val audioControls = column()
        audioGroup.addView(audioControls, spacing())

        val advanced = column().apply { visibility = View.GONE }
        lateinit var toggle: Button
        toggle = button(body, R.string.rc_advanced_show, "advancedToggle") {
            val expanded = advanced.visibility == View.GONE
            advanced.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.setText(if (expanded) R.string.rc_advanced_hide else R.string.rc_advanced_show)
        }
        body.addView(advanced, spacing())
        label(advanced, R.string.rc_advanced_description)
        val videoAdvanced = column()
        advanced.addView(videoAdvanced, spacing())
        label(videoAdvanced, R.string.rc_crop_heading, heading = true)
        val fullscreen = CheckBox(activity).apply {
            tag = "fullscreen"
            setText(R.string.rc_fullscreen)
            isChecked = current.fullscreen || (
                current.cropWidth == 0 && current.cropHeight == 0 && current.cropX == 0 && current.cropY == 0)
            minimumHeight = dp(48)
            videoAdvanced.addView(this, spacing(8))
        }
        val cropWidth = edit(videoAdvanced, R.string.rc_crop_width, current.cropWidth.toString(), "cropWidth")
        val cropHeight = edit(videoAdvanced, R.string.rc_crop_height, current.cropHeight.toString(), "cropHeight")
        val cropX = edit(videoAdvanced, R.string.rc_crop_x, current.cropX.toString(), "cropX")
        val cropY = edit(videoAdvanced, R.string.rc_crop_y, current.cropY.toString(), "cropY")
        val cropFields = listOf(cropWidth, cropHeight, cropX, cropY)
        val cropBoundsHint = label(videoAdvanced, R.string.rc_crop_bounds_unavailable).apply {
            tag = "cropBoundsHint"
            labelFor = cropWidth.id
        }
        fun naturalDisplayBounds(): Pair<Int, Int>? {
            return try {
                val display = ProjectionInventory.displays(activity).displays
                    .singleOrNull() ?: return null
                if (display.rotation == null || display.naturalWidth <= 0 || display.naturalHeight <= 0) null
                else display.naturalWidth to display.naturalHeight
            } catch (_: Exception) { null }
        }
        fun updateCropBoundsHint() {
            cropBoundsHint.text = naturalDisplayBounds()?.let { (width, height) ->
                activity.getString(R.string.rc_crop_natural_hint, width, height)
            } ?: activity.getString(R.string.rc_crop_bounds_unavailable)
        }
        fun cropFits(rect: CropRect, bounds: Pair<Int, Int>): Boolean =
            rect.width > 0 && rect.height > 0 && rect.x >= 0 && rect.y >= 0 &&
                rect.x.toLong() + rect.width <= bounds.first && rect.y.toLong() + rect.height <= bounds.second
        fun cropDraft(): CropRect? {
            val width = cropWidth.text.toString().trim().toIntOrNull() ?: return null
            val height = cropHeight.text.toString().trim().toIntOrNull() ?: return null
            val x = cropX.text.toString().trim().toIntOrNull() ?: return null
            val y = cropY.text.toString().trim().toIntOrNull() ?: return null
            return CropRect(width, height, x, y).takeIf { it.width > 0 && it.height > 0 && it.x >= 0 && it.y >= 0 }
        }
        updateCropBoundsHint()
        fun updateCropVisibility() {
            val visible = !fullscreen.isChecked
            cropFields.forEach { field ->
                field.visibility = if (visible) View.VISIBLE else View.GONE
                videoAdvanced.findViewWithTag<TextView>("${field.tag}Label").visibility = field.visibility
            }
            cropBoundsHint.visibility = if (visible) View.VISIBLE else View.GONE
        }
        var initializeEmptyCrop = current.cropWidth == 0 && current.cropHeight == 0 &&
            current.cropX == 0 && current.cropY == 0
        val legacyNoCrop = initializeEmptyCrop && !current.fullscreen
        var fullscreenTouched = false
        fullscreen.setOnCheckedChangeListener { _, checked ->
            fullscreenTouched = true
            if (!checked && initializeEmptyCrop && cropFields.all { it.text.toString().trim() == "0" }) {
                naturalDisplayBounds()?.let { (width, height) ->
                    cropWidth.setText(width.toString())
                    cropHeight.setText(height.toString())
                    cropX.setText("0")
                    cropY.setText("0")
                } ?: run {
                    cropBoundsHint.text = activity.getString(R.string.rc_crop_bounds_unavailable)
                }
            }
            if (!checked) initializeEmptyCrop = false
            updateCropBoundsHint()
            updateCropVisibility()
        }
        updateCropVisibility()

        fun videoDraft(maximumSize: Int): Settings? = try {
            Settings.from(current.saved(), validate = false).apply {
                this.video = true
                this.videoCodec = videoCodec.value ?: current.videoCodec
                videoEncoder = ""
                maxSize = maximumSize
                maxFps = fps.value?.toInt() ?: current.maxFps
                videoBitrate = bitrate.value?.toInt() ?: current.videoBitrate
                this.fullscreen = fullscreen.isChecked
                if (!this.fullscreen) {
                    this.cropWidth = cropWidth.text.toString().trim().toInt()
                    this.cropHeight = cropHeight.text.toString().trim().toInt()
                    this.cropX = cropX.text.toString().trim().toInt()
                    this.cropY = cropY.text.toString().trim().toInt()
                }
                validate()
            }
        } catch (_: Exception) { null }

        fun geometrySupport(maximumSize: Int): Boolean? {
            val draft = videoDraft(maximumSize) ?: return null
            return try {
                VideoEncoderPreflight.validate(draft, ProjectionInventory.displays(activity))
                true
            } catch (failure: VideoGeometryException) {
                if (failure.reason == VideoGeometryReason.METADATA_UNAVAILABLE) null else false
            } catch (_: Exception) { null }
        }

        fun sourceLongerEdge(): Int? {
            val bounds = naturalDisplayBounds() ?: return null
            val currentRect = CropRect(current.cropWidth, current.cropHeight, current.cropX, current.cropY)
                .takeIf { cropFits(it, bounds) }
            val selectedRect = if (fullscreen.isChecked) null
                else cropDraft()?.takeIf { cropFits(it, bounds) } ?: currentRect
            return selectedRect?.let { maxOf(it.width, it.height) } ?: maxOf(bounds.first, bounds.second)
        }

        size.optionProvider = {
            val values = (listOf(0, 1280, 1920, 2560, 3840) + current.maxSize)
                .distinct().sorted()
            val selected = size.value?.toIntOrNull() ?: current.maxSize
            updateCropBoundsHint()
            val longerEdge = sourceLongerEdge()
            val withinCapture = sizePresetsWithinCapture(values, longerEdge, selected)
            val checked = withinCapture.map { value -> value to geometrySupport(value) }
            val options = checked.filter { (value, supported) -> supported != false || value == selected }
            val labels = options.map { (value, supported) ->
                val base = if (value == 0) activity.getString(R.string.rc_original_size)
                    else activity.getString(R.string.rc_summary_size, value)
                when (supported) {
                    true -> "$base (${activity.getString(R.string.rc_capability_supported)})"
                    false -> "$base (${activity.getString(R.string.rc_current_unsupported)})"
                    null -> "$base (${activity.getString(R.string.rc_capability_unverified)})"
                }
            }
            options.map { it.first.toString() } to labels
        }

        bitrate.optionProvider = {
            val codec = videoCodec.value ?: current.videoCodec
            val encoder = try { encoders.resolve(codec, "") } catch (_: Exception) { "" }
            val ranges = try { CodecBitrateDiscovery.read() } catch (_: Exception) { null }
            val knownRanges = ranges?.candidates(codec, encoder)?.filter { it.min != null }.orEmpty()
            fun supports(value: Int): Boolean? = when {
                knownRanges.isEmpty() -> null
                else -> knownRanges.any { it.permits(value) }
            }
            val values = ((1..30).map { it * 1_000_000 } + current.videoBitrate).distinct().sorted()
            val selected = bitrate.value?.toIntOrNull() ?: current.videoBitrate
            val options = values.filter { supports(it) != false || it == selected }
            val labels = options.map { value ->
                val base = "${decimal(value, 1_000_000)} Mbps"
                when (supports(value)) {
                    true -> "$base (${activity.getString(R.string.rc_capability_supported)})"
                    false -> "$base (${activity.getString(R.string.rc_current_unsupported)})"
                    null -> "$base (${activity.getString(R.string.rc_capability_unverified)})"
                }
            }
            options.map(Int::toString) to labels
        }

        val audioCodecs = (listOf("raw", "opus", "aac") + current.audioCodec).distinct()
        val audioCodec = choice(
            audioControls, R.string.rc_audio_codec, audioCodecs,
            audioCodecs.map {
                when (it) {
                    "raw" -> activity.getString(R.string.rc_audio_raw)
                    "opus" -> activity.getString(R.string.rc_audio_opus)
                    "aac" -> activity.getString(R.string.rc_audio_aac)
                    else -> "$it (${activity.getString(R.string.rc_current_unsupported)})"
                }
            },
            current.audioCodec, "audioCodec"
        )
        // Existing output-mode requests remain editable without silently changing their source.
        val sources = (listOf("playback", "mic") + current.audioSource).distinct()
        val sourceLabels = sources.map {
            when (it) {
                "mic" -> activity.getString(R.string.rc_audio_mic)
                "output" -> activity.getString(R.string.rc_audio_output)
                "playback" -> activity.getString(R.string.rc_audio_playback)
                else -> "$it (${activity.getString(R.string.rc_current_unsupported)})"
            }
        }
        val audioSource = choice(audioControls, R.string.rc_audio_source, sources, sourceLabels, current.audioSource, "audioSource")
        val audioBitrate = edit(audioControls, R.string.rc_audio_bitrate, decimal(current.audioBitrate, 1_000), "audioBitrate")
        val audioBitrateLabel = checkNotNull(audioControls.findViewWithTag<TextView>("audioBitrateLabel"))
        val rawHint = label(audioControls, R.string.rc_raw_hint)
        fun updateAudioBitrate() {
            val raw = audioCodec.value == "raw"
            audioBitrate.isEnabled = !raw
            audioBitrate.visibility = if (raw) View.GONE else View.VISIBLE
            audioBitrateLabel.visibility = audioBitrate.visibility
            rawHint.visibility = if (raw) View.VISIBLE else View.GONE
        }
        audioCodec.changed = ::updateAudioBitrate
        updateAudioBitrate()

        fun updateTrackControls(updateSaveButton: Boolean) {
            val videoOn = videoEnabled.isChecked
            val audioOn = audio.isChecked
            videoControls.visibility = if (videoOn) View.VISIBLE else View.GONE
            videoAdvanced.visibility = if (videoOn) View.VISIBLE else View.GONE
            audioControls.visibility = if (audioOn) View.VISIBLE else View.GONE
            videoEnabled.isEnabled = audioOn
            audio.isEnabled = videoOn
            videoDisabledHint.visibility = if (videoOn && !audioOn) View.VISIBLE else View.GONE
            audioDisabledHint.visibility = if (audioOn && !videoOn) View.VISIBLE else View.GONE
            if (showingVideoCapabilityError)
                error.visibility = if (videoOn) View.VISIBLE else View.GONE
            if (updateSaveButton && dialog.isShowing)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !videoOn || supportedVideo.isNotEmpty()
        }
        videoEnabled.setOnCheckedChangeListener { _, _ -> updateTrackControls(true) }
        audio.setOnCheckedChangeListener { _, _ -> updateTrackControls(true) }
        updateTrackControls(false)
        if (onDiagnostics != null)
            button(advanced, R.string.rc_playback_diagnostics, "playbackDiagnostics", onDiagnostics)
        val playbackStatistics = CheckBox(activity).apply {
            tag = "showPlaybackStatistics"
            setText(R.string.rc_playback_statistics)
            isChecked = current.showPlaybackStatistics
            minimumHeight = dp(48)
            advanced.addView(this, spacing(16))
        }
        Button(activity).apply {
            tag = "sessionTelemetryReports"
            text = "View or clear session telemetry reports"
            minimumHeight = dp(48)
            setOnClickListener {
                val store = storeFactory(activity)
                store.list { reports ->
                    activity.runOnUiThread {
                        if (!parentActive()) return@runOnUiThread
                        val builder = AlertDialog.Builder(activity).setTitle("Session telemetry reports")
                        if (reports == null) builder.setMessage("Reports are unavailable; check the diagnostic log.")
                        else if (reports.isEmpty()) builder.setMessage("No retained reports.")
                        else {
                            val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                            builder.setItems(reports.map { format.format(java.util.Date(it.timestampMs)) }.toTypedArray()) { _, which ->
                                store.read(reports[which].name) { result ->
                                    activity.runOnUiThread {
                                        if (!parentActive()) return@runOnUiThread
                                        val text = TextView(activity).apply {
                                            this.text = when (result) {
                                                dev.redrain.cast.diagnostics.IncidentStore.ReadResult.Missing ->
                                                    "Session telemetry report is no longer retained; reopen Reports and try again."
                                                is dev.redrain.cast.diagnostics.IncidentStore.ReadResult.Failure ->
                                                    "Could not read session telemetry report; reopen Reports and try again."
                                                is dev.redrain.cast.diagnostics.IncidentStore.ReadResult.Success ->
                                                    try { dev.redrain.cast.diagnostics.IncidentSummary.text(JSONObject(result.content)) }
                                                    catch (_: Exception) { "Report could not be read." }
                                            }
                                            setTextIsSelectable(true)
                                            setPadding(dp(16), dp(12), dp(16), dp(12))
                                        }
                                        val detailBuilder = AlertDialog.Builder(activity).setTitle("Session telemetry summary")
                                            .setView(ScrollView(activity).apply { addView(text) })
                                            .setPositiveButton(android.R.string.ok, null)
                                        if (result is dev.redrain.cast.diagnostics.IncidentStore.ReadResult.Success &&
                                            onIncidentExport != null)
                                            detailBuilder.setNeutralButton("Export…") { _, _ ->
                                                onIncidentExport(reports[which].name)
                                            }
                                        val detail = detailBuilder.create()
                                        detail.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                        showChild(detail)
                                    }
                                }
                            }
                        }
                        val list = builder.setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton("Clear all") { _, _ ->
                                val confirmation = AlertDialog.Builder(activity).setTitle("Clear session telemetry reports?")
                                    .setMessage("All saved session telemetry reports on this device will be deleted. " +
                                        "An active or recently stopped cast may save or update a report after clearing.")
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton("Clear") { _, _ ->
                                        store.clear { success -> activity.runOnUiThread {
                                            if (parentActive())
                                                android.widget.Toast.makeText(activity,
                                                    if (success) "Session telemetry reports cleared" else "Could not clear session telemetry reports",
                                                    android.widget.Toast.LENGTH_SHORT).show()
                                        } }
                                    }.create()
                                showChild(confirmation)
                            }.create()
                        list.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        showChild(list)
                    }
                }
            }
            advanced.addView(this, spacing(8))
        }
        val remoteDiagnostics = CheckBox(activity).apply {
            tag = "allowRemoteDiagnostics"
            text = "Allow unpaired LAN / VPN diagnostics (this process only)"
            isChecked = dev.redrain.cast.diagnostics.DiagnosticAccess.enabled
            minimumHeight = dp(48)
            advanced.addView(this, spacing(8))
        }
        TextView(activity).apply {
            tag = "remoteDiagnosticsWarning"
            text = dev.redrain.cast.diagnostics.DiagnosticAccess.warning
            textSize = 14f
            setTextColor(activity.getColor(R.color.rc_text_secondary))
            advanced.addView(this, spacing(8))
        }
        remoteDiagnostics.setOnCheckedChangeListener { _, checked ->
            if (!checked) dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity, false)
        }
        Button(activity).apply {
            tag = "remoteDiagnosticsStatus"
            text = "LAN / VPN diagnostics endpoints / status"
            minimumHeight = dp(48)
            setOnClickListener {
                val info = TextView(activity).apply {
                    text = dev.redrain.cast.diagnostics.DiagnosticAccess.status +
                        "\n\nRefresh by reopening. Save settings to apply consent. " +
                        dev.redrain.cast.diagnostics.DiagnosticAccess.warning
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                val status = AlertDialog.Builder(activity).setTitle("LAN / VPN diagnostics").setView(info)
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
            closed = true
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

        fun cropDraftForSave(active: Boolean): CropRect? {
            cropFields.forEach { it.error = null }
            val specs = listOf(
                Triple(cropWidth, R.string.rc_crop_width, true),
                Triple(cropHeight, R.string.rc_crop_height, true),
                Triple(cropX, R.string.rc_crop_x, false),
                Triple(cropY, R.string.rc_crop_y, false)
            )
            val parsed = specs.map { (field, title, _) ->
                field.text.toString().trim().toIntOrNull().also { value ->
                    if (active && value == null)
                        field.error = activity.getString(R.string.rc_invalid_number, activity.getString(title))
                }
            }
            if (parsed.any { it == null }) return null
            val (width, height, x, y) = parsed.map { checkNotNull(it) }
            val rect = CropRect(width, height, x, y)
            if (width !in 1..16384 || height !in 1..16384 || x < 0 || y < 0) {
                if (active) specs.forEachIndexed { index, (field, _, dimension) ->
                    val value = checkNotNull(parsed[index])
                    if (dimension && value !in 1..16384 || !dimension && value < 0)
                        field.error = activity.getString(
                            if (dimension) R.string.rc_crop_dimensions_invalid else R.string.rc_crop_offsets_invalid)
                }
                return null
            }
            if (active) {
                val bounds = naturalDisplayBounds()
                if (bounds == null) {
                    cropBoundsHint.text = activity.getString(R.string.rc_crop_bounds_unavailable)
                    cropFields.forEach { it.error = activity.getString(R.string.rc_crop_bounds_unavailable) }
                    return null
                }
                cropBoundsHint.text = activity.getString(
                    R.string.rc_crop_natural_hint, bounds.first, bounds.second)
                val boundsError = activity.getString(
                    R.string.rc_crop_out_of_bounds, bounds.first, bounds.second)
                if (x.toLong() + width > bounds.first) {
                    cropX.error = boundsError
                    cropWidth.error = boundsError
                }
                if (y.toLong() + height > bounds.second) {
                    cropY.error = boundsError
                    cropHeight.error = boundsError
                }
                if (cropFields.any { it.error != null }) return null
            }
            return rect
        }

        fun configureSaveButton() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).minimumHeight = dp(48)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !videoEnabled.isChecked || supportedVideo.isNotEmpty()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minimumHeight = dp(48)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val activeCrop = videoEnabled.isChecked && !fullscreen.isChecked
                val draftCrop = cropDraftForSave(activeCrop)
                if (activeCrop && draftCrop == null) return@setOnClickListener
                val next: Settings
                try {
                    // Clone the entire request so hidden options and capture orientation survive edits.
                    next = Settings.from(current.saved(), validate = false).apply {
                        showPlaybackStatistics = playbackStatistics.isChecked
                        this.video = videoEnabled.isChecked
                        this.fullscreen = fullscreen.isChecked && (!legacyNoCrop || fullscreenTouched)
                        if (this.video) {
                            this.videoCodec = videoCodec.value
                                ?: throw IllegalArgumentException(activity.getString(R.string.rc_select_hardware_codec))
                            encoders.resolve(this.videoCodec, "")
                            maxSize = checkNotNull(size.value).toInt()
                            maxFps = checkNotNull(fps.value).toInt()
                            videoBitrate = checkNotNull(bitrate.value).toInt()
                            receiverOrientation = checkNotNull(orientation.value)
                        } else {
                            videoCodec.value?.let { this.videoCodec = it }
                            size.value?.toIntOrNull()?.takeIf { it in 0..16384 }?.let { maxSize = it }
                            fps.value?.toIntOrNull()?.takeIf { it in 0..240 }?.let { maxFps = it }
                            bitrate.value?.toIntOrNull()?.takeIf { it in 100_000..200_000_000 }?.let { videoBitrate = it }
                            orientation.value?.takeIf { it in setOf("auto", "landscape", "portrait") }
                                ?.let { receiverOrientation = it }
                        }
                        this.audio = audio.isChecked
                        if (this.video) videoEncoder = ""
                        if (draftCrop != null) {
                            this.cropWidth = draftCrop.width
                            this.cropHeight = draftCrop.height
                            this.cropX = draftCrop.x
                            this.cropY = draftCrop.y
                        }
                        if (this.audio) {
                            this.audioCodec = checkNotNull(audioCodec.value)
                            this.audioSource = checkNotNull(audioSource.value)
                            if (this.audioCodec != "raw")
                                this.audioBitrate = number(audioBitrate, R.string.rc_audio_bitrate, 1_000)
                            this.audioEncoder = ""
                        } else {
                            audioCodec.value?.takeIf { it in Settings.audioCodecs() }?.let { this.audioCodec = it }
                            audioSource.value?.takeIf { it in setOf("playback", "mic", "output") }
                                ?.let { this.audioSource = it }
                            if (this.audioCodec != "raw") {
                                val parsed = try {
                                    audioBitrate.text.toString().trim().toBigDecimal()
                                        .multiply(BigDecimal(1_000)).intValueExact()
                                } catch (_: Exception) { null }
                                parsed?.takeIf { it in 16_000..512_000 }?.let { this.audioBitrate = it }
                            }
                        }
                        validate()
                    }
                } catch (exception: Exception) {
                    error.text = activity.getString(R.string.rc_settings_error, exception.message ?: exception.javaClass.simpleName)
                    error.visibility = View.VISIBLE
                    showingVideoCapabilityError = false
                    scroll.post { scroll.scrollTo(0, 0) }
                    return@setOnClickListener
                }
                fun persist() {
                    try {
                        val saved = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                            .edit().putString(REQUEST, next.saved().toString()).commit()
                        if (!saved) throw IOException(activity.getString(R.string.rc_settings_write_error))
                        onSave(next)
                        dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity,
                            remoteDiagnostics.isChecked)
                        dialog.dismiss()
                    } catch (exception: Exception) {
                        error.text = activity.getString(R.string.rc_settings_error,
                            exception.message ?: exception.javaClass.simpleName)
                        error.visibility = View.VISIBLE
                        showingVideoCapabilityError = false
                        scroll.post { scroll.scrollTo(0, 0) }
                    }
                }
                if (current.invalidSavedError != null) {
                    val confirmation = AlertDialog.Builder(activity)
                        .setTitle(R.string.rc_settings_reset_title)
                        .setMessage(R.string.rc_settings_reset_message)
                        .setNegativeButton(R.string.rc_cancel, null)
                        .setPositiveButton(R.string.rc_settings_reset_confirm) { _, _ -> persist() }
                        .create()
                    showChild(confirmation)
                } else persist()
            }
        }
        body.requestFocus()
        dialog.show()
        configureSaveButton()
        return dialog
    }

    private fun decimal(value: Int, divisor: Int) =
        BigDecimal(value).divide(BigDecimal(divisor)).stripTrailingZeros().toPlainString()

    internal fun sizePresetsWithinCapture(values: List<Int>, sourceLongerEdge: Int?, selected: Int): List<Int> =
        values.filter { it == 0 || sourceLongerEdge == null || it <= sourceLongerEdge || it == selected }
}
