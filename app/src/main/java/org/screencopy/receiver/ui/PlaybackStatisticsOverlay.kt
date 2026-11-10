package org.screencopy.receiver.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import dev.redrain.cast.R
import org.screencopy.receiver.media.PlaybackStatisticsDisplay
import org.screencopy.receiver.media.PlaybackStatisticsSnapshot
import org.screencopy.receiver.media.PlaybackStatisticsText
import org.screencopy.receiver.media.StatisticsField
import org.screencopy.receiver.media.PlaybackDisplayMeasurements
import org.screencopy.receiver.media.VideoPresentationOutput

/** Display-only grid; every pointer remains owned by the underlying player. */
class PlaybackStatisticsOverlay @JvmOverloads constructor(
    context: Context, private val detailed: Boolean = false
) : LinearLayout(context) {
    private val values = mutableMapOf<String, TextView>()
    private var mode: Int? = null
    internal var display: PlaybackStatisticsDisplay? = null
        private set
    val text: String get() = display?.let { model ->
        (listOf(model.header) + (model.kpis + model.totals + model.video + model.audio +
            model.footer + model.details).map { "${it.label}: ${it.value}" } +
            model.connections.map { "${it.channel}: ${it.queued}, ${it.span}, ${it.lastActivity}, ${it.rtt}" } +
            model.notes + listOf(model.queueCaption, model.peakCaption, model.totalsCaption) +
            if (detailed) model.diagnostics.map { "${it.label}: ${it.value}" } else emptyList()).joinToString("\n")
    }.orEmpty()
    val textSize: Float get() = values.values.firstOrNull()?.textSize ?: 0f

    private val foregroundColor = Color.rgb(235, 242, 255)
    private val secondary = Color.rgb(183, 199, 222)
    private val blue = Color.rgb(112, 200, 255)
    private val amber = Color.rgb(255, 191, 93)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    init {
        tag = "playbackStatistics"
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xD0141B23.toInt())
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), 0x665D718A)
        }
        setPadding(dp(6), dp(4), dp(6), dp(4))
        isClickable = false
        isLongClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean = false

    private fun label(value: String, size: Float, numeric: Boolean = false, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(if (numeric) foregroundColor else secondary)
            gravity = if (numeric) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            typeface = if (numeric) Typeface.MONOSPACE else Typeface.create(Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            setPadding(dp(2), if (size <= 14f) 0 else dp(1), dp(2), if (size <= 12f) 0 else dp(1))
            isFocusable = false
            isClickable = false
            isLongClickable = false
            setTextIsSelectable(false)
        }

    private fun rule(parent: LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(0x665D718A) },
            LayoutParams(-1, dp(1)).apply {
                topMargin = dp(if (mode != 0) 1 else 3)
                bottomMargin = topMargin
            })
    }

    private fun table(parent: LinearLayout, fields: List<StatisticsField>, size: Float,
        labelWeight: Float = 1f, valueWeight: Float = 1f) {
        fun caption(field: StatisticsField): String = if (size <= 12f) when (field.id) {
            "videoDynamicRange" -> context.getString(R.string.rc_stats_stream_range_compact)
            "videoDecodeFormat" -> context.getString(R.string.rc_stats_profile_depth_compact)
            "encodedSize" -> "Encoded"
            "captureLimit" -> "FPS limit"
            "receiverCanvas" -> "Canvas px"
            "renderTarget" -> "Render px"
            "displayOutput" -> "Display px"
            "configuredBitrate" -> "Set bitrate"
            "videoBitrate" -> "Bitrate"
            "networkLoss" -> "Loss"
            "audioFormat" -> "Format"
            "audioBitrate" -> "Bitrate"
            "pcmBufferTarget" -> "PCM/target"
            "devicePadding" -> "Padding"
            "insertedSilence" -> "Silence ms"
            "discardedPcm" -> "Discard ms"
            "avSkew" -> "Skew"
            "peakObservedSkew" -> "Peak |skew|"
            "scheduledFrames" -> "Scheduled"
            "devicePlayed" -> "Device ms"
            else -> field.label
        } else field.label
        val captions = fields.map { label(caption(it), size) }
        val captionWidth = captions.maxOfOrNull {
            kotlin.math.ceil(it.paint.measureText(it.text.toString())).toInt() + it.compoundPaddingLeft + it.compoundPaddingRight
        } ?: 0
        val table = LinearLayout(context).apply { orientation = VERTICAL }
        fields.forEachIndexed { index, field ->
            table.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                isBaselineAligned = false
                tag = "row:${field.id}"
                addView(captions[index].apply {
                    tag = "label:${field.id}"
                    contentDescription = field.label
                }, if (size <= 12f) LayoutParams(captionWidth, -2) else LayoutParams(0, -2, labelWeight))
                val value = label(fieldValue(field, size), size, numeric = true).apply { tag = "value:${field.id}" }
                values[field.id] = value
                addView(value, LayoutParams(0, -2, valueWeight))
            }, LayoutParams(-1, -2))
        }
        parent.addView(table, LayoutParams(-1, -2))
    }

    private fun section(parent: LinearLayout, title: String, size: Float): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        column.addView(label(title, size, bold = true))
        parent.addView(column, LayoutParams(0, -2, 1f))
        return column
    }

    private fun stackedFields(parent: LinearLayout, fields: List<StatisticsField>, size: Float, key: String) {
        val grid = TableLayout(context).apply {
            tag = key
            isStretchAllColumns = true
            isShrinkAllColumns = true
        }
        val labels = TableRow(context)
        val numbers = TableRow(context)
        fields.forEach { field ->
            val caption = if (size <= 12f && field.id == "playbackEpoch") "Play epoch" else field.label
            labels.addView(label(caption, size).apply {
                tag = "label:${field.id}"
                contentDescription = field.label
            },
                TableRow.LayoutParams(0, -2, 1f))
            val value = label(field.value, size, numeric = true).apply {
                tag = "value:${field.id}"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            values[field.id] = value
            numbers.addView(value, TableRow.LayoutParams(0, -2, 1f))
        }
        grid.addView(labels)
        grid.addView(numbers)
        parent.addView(grid, LayoutParams(-1, -2))
    }

    private fun connections(parent: LinearLayout, model: PlaybackStatisticsDisplay, size: Float) {
        parent.addView(label("CONNECTIONS & QUEUES", size, bold = true))
        val table = TableLayout(context).apply {
            tag = "connectionTable"
            isStretchAllColumns = true
        }
        fun row(id: String?, content: List<String>) = TableRow(context).apply {
            content.forEachIndexed { index, text ->
                val title = if (size <= 12f) {
                    if (id == null) when (index) {
                        0 -> "Ch."
                        2 -> "Span ms"
                        3 -> "Last ms"
                        4 -> "RTT ms"
                        else -> text
                    } else if (index == 0 && text == "Control") "Ctrl"
                    else if (index >= 2) text.removeSuffix(" ms") else text
                } else text
                val cell = label(title, size, numeric = id != null && index > 0, bold = id == null).apply {
                    contentDescription = if (id == null || index == 0) text else null
                    if (index > 0) gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    tag = "connection:${id ?: "header"}:$index"
                }
                if (id != null && index > 0) values["connection:$id:$index"] = cell
                addView(cell, TableRow.LayoutParams(-2, -2))
            }
        }
        table.addView(row(null, model.connectionHeaders), LayoutParams(-1, -2))
        model.connections.forEach { item ->
            table.addView(row(item.id, listOf(item.channel, queueText(item.queued, size),
                item.span, item.lastActivity, item.rtt)), LayoutParams(-1, -2))
        }
        parent.addView(table, LayoutParams(-1, -2))
        parent.addView(label(if (size <= 12f) "Waiting packets / bytes" else model.queueCaption, size))
    }

    private fun queueText(value: String, size: Float): String = if (size <= 12f)
        value.replace(" packets · ", "/").replace(" B", "B") else value

    private fun fieldValue(field: StatisticsField, size: Float): String = if (size > 12f) field.value else
        when (field.id) {
            "videoDynamicRange", "videoDecodeFormat" -> field.value.replace(" · ", "·")
            "encodedSize" -> field.value.replace(" × ", "×")
            "receiverCanvas" -> field.value.replace(" × ", "×").removeSuffix(" px")
            "renderTarget", "displayOutput" -> field.value.replace(" × ", "×").removeSuffix(" px")
            "rotation" -> field.value.replace(" · ", "/")
            "pcmBufferTarget" -> field.value.replace(" / ", "/")
            "insertedSilence", "discardedPcm" -> field.value.replace(" (+", "(+").removeSuffix(" ms")
            else -> field.value
        }

    private fun build(model: PlaybackStatisticsDisplay, layoutMode: Int) {
        removeAllViews()
        values.clear()
        val compact = layoutMode != 0
        val verticalInset = dp(if (compact) 2 else 4)
        setPadding(dp(6), verticalInset, dp(6), verticalInset)
        val size = if (compact) 10f else 16f
        val heading = label(model.header, size, bold = true).apply {
            tag = "statisticsHeader"
            setTextColor(foregroundColor)
        }
        if (compact) {
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(heading, LayoutParams(-2, -2))
                addView(label("Generation totals · skips ≠ network loss", size).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }, LayoutParams(0, -2, 1f))
            }, LayoutParams(-1, -2))
        } else addView(heading)
        rule(this)
        val kpis = TableLayout(context).apply {
            tag = "statisticsKpis"
            isStretchAllColumns = true
            isShrinkAllColumns = true
        }
        val kpiLabels = TableRow(context)
        val kpiValues = TableRow(context)
        model.kpis.forEach { field ->
            val caption = if (layoutMode == 2) when (field.id) {
                "receivedFps" -> "Rx FPS"
                "decodedFps" -> "Dec FPS"
                "presentedFps" -> "Out FPS"
                "skippedRecent" -> "Skip %"
                else -> field.label
            } else field.label
            kpiLabels.addView(label(caption, size).apply { contentDescription = field.label },
                TableRow.LayoutParams(0, -2, 1f))
            val value = label(field.value, size, numeric = true).apply { tag = "value:${field.id}"; gravity = Gravity.START }
            values[field.id] = value
            kpiValues.addView(value, TableRow.LayoutParams(0, -2, 1f))
        }
        kpis.addView(kpiLabels)
        kpis.addView(kpiValues)
        val totals = TableLayout(context).apply {
            tag = "statisticsTotals"
            isStretchAllColumns = true
            isShrinkAllColumns = true
        }
        val totalLabels = TableRow(context)
        val totalValues = TableRow(context)
        model.totals.forEach { field ->
            totalLabels.addView(label(field.label, size), TableRow.LayoutParams(0, -2, 1f))
            val value = label(field.value, size, numeric = true).apply {
                tag = "value:${field.id}"
                gravity = Gravity.START
            }
            values[field.id] = value
            totalValues.addView(value, TableRow.LayoutParams(0, -2, 1f))
        }
        totals.addView(totalLabels)
        totals.addView(totalValues)
        if (layoutMode == 2) {
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                isBaselineAligned = false
                addView(kpis, LayoutParams(0, -2, 1f))
                addView(totals, LayoutParams(0, -2, 1f))
            }, LayoutParams(-1, -2))
        } else {
            addView(kpis, LayoutParams(-1, -2))
            addView(totals, LayoutParams(-1, -2))
        }
        if (!compact) addView(label(model.totalsCaption, size))
        rule(this)
        val body = LinearLayout(context).apply { orientation = if (layoutMode == 1) VERTICAL else HORIZONTAL }
        fun bodySection(title: String): LinearLayout {
            val column = section(body, title, size)
            if (layoutMode == 1) column.layoutParams = LayoutParams(-1, -2)
            return column
        }
        val video = bodySection("VIDEO STREAM")
        if (layoutMode == 2) video.layoutParams = LayoutParams(0, -2, 1.35f)
        table(video, model.video, size)
        val audio = bodySection("AUDIO & SYNC")
        if (layoutMode == 2) audio.layoutParams = LayoutParams(0, -2, 1.25f)
        table(audio, model.audio, size)
        val compactNote = "Source skew: +video ahead, not latency · peak 1 Hz · PCM total(+recent) ms"
        if (layoutMode == 0 || layoutMode == 1) {
            audio.addView(label(model.peakCaption, size))
            model.notes.forEach { audio.addView(label(it, size)) }
        } else if (layoutMode == 2) {
            audio.addView(label(compactNote, size))
        }
        addView(body, LayoutParams(-1, -2))
        rule(this)
        connections(this, model, size)
        stackedFields(this, model.details, size, "statisticsPlatformDetails")
        if (layoutMode == 4) {
            addView(label(compactNote, size))
        }
        if (detailed) {
            rule(this)
            addView(label("DISPLAY DIAGNOSTICS", size, bold = true))
            table(this, model.diagnostics, size)
        }
        rule(this)
        stackedFields(this, model.footer, size, "statisticsFooter")
    }

    private fun localizedModel(snapshot: PlaybackStatisticsSnapshot, canvasWidth: Int, canvasHeight: Int,
        measurements: PlaybackDisplayMeasurements): PlaybackStatisticsDisplay {
        val model = PlaybackStatisticsText.display(snapshot, canvasWidth, canvasHeight, measurements)
        val unknown = context.getString(R.string.rc_stats_unknown)
        return model.copy(video = model.video.map { field ->
            when (field.id) {
                "videoDynamicRange" -> field.copy(label = context.getString(R.string.rc_stats_stream_range),
                    value = field.value.replace("N/A", unknown))
                "videoDecodeFormat" -> field.copy(label = context.getString(R.string.rc_stats_profile_depth),
                    value = PlaybackStatisticsText.decodeFormat(snapshot.input.decodedVideo) {
                        context.getString(R.string.rc_stats_bit_depth, it)
                    }.replace("N/A", unknown))
                "presentationOutput" -> field.copy(label = context.getString(R.string.rc_stats_output),
                    value = when (snapshot.input.presentation?.output) {
                        VideoPresentationOutput.Hdr -> context.getString(R.string.rc_stats_hdr_output)
                        VideoPresentationOutput.Sdr -> context.getString(R.string.rc_stats_sdr_output)
                        VideoPresentationOutput.SdrToneMapped -> context.getString(R.string.rc_stats_sdr_tone_mapped)
                        null -> unknown
                    })
                else -> field
            }
        })
    }

    fun show(snapshot: PlaybackStatisticsSnapshot?, canvasWidth: Int, canvasHeight: Int,
        availableWidth: Int = canvasWidth, availableHeight: Int = canvasHeight,
        measurements: PlaybackDisplayMeasurements = PlaybackDisplayMeasurements()) {
        if (snapshot == null) {
            display = null
            values.values.forEach { it.text = "" }
            visibility = View.GONE
            return
        }
        val density = resources.displayMetrics.density
        val compact = minOf(availableWidth, availableHeight) / density < 600
        val panelWidth = if (!detailed && compact)
            minOf((availableWidth * if (availableWidth > availableHeight) 0.50f else 0.90f).toInt(), dp(760))
            else minOf(availableWidth, dp(1100))
        layoutParams?.let {
            if (panelWidth > 0 && it.width != panelWidth) {
                it.width = panelWidth
                layoutParams = it
            }
        }
        val layoutMode = when {
            compact && panelWidth / density >= 380 && availableWidth > availableHeight -> 2
            compact && !detailed -> 4
            panelWidth / density < 600 -> 1
            else -> 0
        }
        val model = localizedModel(snapshot, canvasWidth, canvasHeight, measurements)
        if (mode != layoutMode) {
            mode = layoutMode
            build(model, layoutMode)
        }
        display = model
        (model.kpis + model.totals + model.video + model.audio + model.footer + model.details +
            if (detailed) model.diagnostics else emptyList()).forEach {
            values[it.id]?.text = fieldValue(it, if (mode == 0) 16f else 10f)
        }
        model.connections.forEach {
            listOf(queueText(it.queued, if (mode == 0) 16f else 10f),
                it.span, it.lastActivity, it.rtt).forEachIndexed { index, value ->
                values["connection:${it.id}:${index + 1}"]?.text =
                    if (mode != 0 && index > 0) value.removeSuffix(" ms") else value
            }
        }
        model.kpis.take(3).forEach { values[it.id]?.setTextColor(blue) }
        values["skippedRecent"]?.setTextColor(if (snapshot.recentSkippedPercent?.let { it > 0 } == true) amber else foregroundColor)
        visibility = View.VISIBLE
    }
}
