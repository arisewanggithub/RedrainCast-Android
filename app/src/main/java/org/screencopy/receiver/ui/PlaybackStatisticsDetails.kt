package org.screencopy.receiver.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.redrain.cast.R
import org.screencopy.receiver.media.PlaybackStatisticsSnapshot
import org.screencopy.receiver.media.PlaybackDisplayMeasurements

class PlaybackStatisticsDetails(context: Context) : ScrollView(context) {
    internal val panel = PlaybackStatisticsOverlay(context, detailed = true)
    private val unavailable = TextView(context).apply {
        setText(R.string.rc_playback_diagnostics_unavailable)
        textSize = 16f
        setTextColor(context.getColor(R.color.rc_text))
        val inset = (16 * resources.displayMetrics.density + 0.5f).toInt()
        setPadding(inset, inset, inset, inset)
    }
    init {
        tag = "playbackStatisticsDetails"
        setBackgroundColor(context.getColor(R.color.rc_surface))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(unavailable, LinearLayout.LayoutParams(-1, -2))
            addView(panel, LinearLayout.LayoutParams(-1, -2))
        })
    }

    fun show(snapshot: PlaybackStatisticsSnapshot?, canvasWidth: Int, canvasHeight: Int,
        measurements: PlaybackDisplayMeasurements = PlaybackDisplayMeasurements()) {
        unavailable.visibility = if (snapshot == null) View.VISIBLE else View.GONE
        panel.show(snapshot, canvasWidth, canvasHeight,
            availableWidth = (width - paddingLeft - paddingRight).takeIf { it > 0 } ?: canvasWidth,
            availableHeight = height.takeIf { it > 0 } ?: canvasHeight, measurements = measurements)
    }
}
