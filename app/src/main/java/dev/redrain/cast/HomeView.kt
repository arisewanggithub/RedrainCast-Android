package dev.redrain.cast

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.screencopy.sender.Discovery
import dev.screencopy.sender.ReceiverTarget

class HomeView(
    private val activity: Activity,
    onSettings: () -> Unit,
    private val onManualConnect: () -> Unit,
    private val onSend: (ReceiverTarget) -> Unit,
    private val onStopSending: () -> Unit,
    onHelp: () -> Unit,
    onDismissError: () -> Unit
) : ScrollView(activity) {
    data class State(
        val readiness: String = "STARTING",
        val detail: String = "",
        val endpoints: List<String> = emptyList(),
        val sending: Boolean = false,
        val receiving: Boolean = false,
        val outgoing: ReceiverTarget? = null,
        val status: String = "",
        val error: String? = null,
        val settingsSummary: String = "",
        val localNetworkPermissionRequired: Boolean = false
    )

    private var state = State()
    var onLocalNetworkPermission: () -> Unit = {}
    private var receivers = emptyList<Discovery.Receiver>()
    private val body = column()
    private val cards = LinearLayout(activity)
    private val sendCard = card()
    private val receiveCard = card()
    private val nearby = column()
    private val empty = column()
    private val summary = text("", secondary = true)
    private val status = text("", secondary = true)
    private val manual = button(R.string.rc_manual_connect) {
        if (state.localNetworkPermissionRequired) onLocalNetworkPermission() else onManualConnect()
    }
    private val networkPermission = button(R.string.flow_network_permission_action) { onLocalNetworkPermission() }
    private val readiness = text("", 15f, bold = true)
    private val detail = text("", secondary = true)
    private val banner = column()
    private val addresses = column()
    private val noAddresses = text(activity.getString(R.string.rc_no_endpoints), secondary = true)
    private val errorCard = card()
    private val errorMessage = text("")
    private val receiverRows = linkedMapOf<Pair<String, Int>, ReceiverRow>()
    private val endpointRows = linkedMapOf<String, View>()
    private var wide: Boolean? = null

    init {
        isFillViewport = true
        clipToPadding = false
        setBackgroundColor(activity.getColor(R.color.rc_background))
        body.setPadding(dp(20), dp(24), dp(20), dp(24))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val identity = column().apply {
            addView(text(activity.getString(R.string.rc_app_name), 30f, bold = true))
            addView(text(activity.getString(R.string.rc_subtitle), secondary = true))
        }
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.rc_gear)
            contentDescription = activity.getString(R.string.rc_settings_button)
            val ripple = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onSettings() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header)
        body.addView(cards, spaced(top = 24))
        cards.tag = "home_cards"
        sendCard.tag = "send_card"
        receiveCard.tag = "receive_card"
        cards.addView(sendCard)
        cards.addView(receiveCard)
        arrangeCards(false)

        sendCard.addView(text(activity.getString(R.string.rc_send_heading), 24f, bold = true))
        sendCard.addView(text(activity.getString(R.string.rc_send_description), secondary = true), spaced(top = 8))
        sendCard.addView(text(activity.getString(R.string.rc_nearby), 17f, bold = true), spaced(top = 24))
        sendCard.addView(text(activity.getString(R.string.rc_discovery_hint), 14f, secondary = true), spaced(top = 4))
        empty.setBackgroundResource(R.drawable.rc_row)
        empty.setPadding(dp(16), dp(16), dp(16), dp(16))
        empty.addView(text(activity.getString(R.string.rc_no_receivers), bold = true))
        empty.addView(text(activity.getString(R.string.rc_no_receivers_detail), secondary = true), spaced(top = 6))
        sendCard.addView(empty, spaced(top = 12))
        sendCard.addView(nearby, spaced(top = 4))
        sendCard.addView(manual, spaced(top = 12))
        sendCard.addView(summary, spaced(top = 8))
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        sendCard.addView(status, spaced(top = 8))

        receiveCard.addView(text(activity.getString(R.string.rc_receive_heading), 24f, bold = true))
        receiveCard.addView(text(activity.getString(R.string.rc_receive_description), secondary = true), spaced(top = 8))
        banner.setPadding(dp(16), dp(16), dp(16), dp(16))
        banner.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        banner.addView(readiness)
        banner.addView(detail, spaced(top = 6))
        receiveCard.addView(banner, spaced(top = 20))
        networkPermission.tag = "local_network_permission"
        networkPermission.visibility = View.GONE
        receiveCard.addView(networkPermission, spaced(top = 12))
        receiveCard.addView(text(activity.getString(R.string.rc_device_name), 14f, secondary = true), spaced(top = 20))
        receiveCard.addView(text(Build.MODEL, 18f, bold = true), spaced(top = 4))
        receiveCard.addView(text(activity.getString(R.string.rc_endpoints), 14f, secondary = true), spaced(top = 20))
        receiveCard.addView(noAddresses, spaced(top = 8))
        receiveCard.addView(addresses, spaced(top = 4))

        errorCard.setBackgroundResource(R.drawable.rc_error)
        errorCard.tag = "home_error"
        errorCard.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        errorCard.addView(text(activity.getString(R.string.rc_error_heading), 18f, bold = true).apply {
            setTextColor(activity.getColor(R.color.rc_error))
        })
        errorMessage.setTextIsSelectable(true)
        errorMessage.minHeight = dp(48)
        errorCard.addView(errorMessage, spaced(top = 8))
        errorCard.addView(button(R.string.rc_dismiss, onDismissError), spaced(top = 8))
        body.addView(errorCard, 1, spaced(top = 16))

        val footer = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        footer.addView(text(activity.getString(R.string.rc_version, BuildConfig.VERSION_NAME), 13f, secondary = true))
        footer.addView(button(R.string.rc_help, onHelp))
        body.addView(footer, spaced(top = 24))
        render(state)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val widthDp = width / resources.displayMetrics.density
        arrangeCards(widthDp >= 840f || widthDp >= 600f && height > 0 && width > height)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun arrangeCards(isWide: Boolean) {
        if (wide == isWide) return
        wide = isWide
        cards.orientation = if (isWide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        sendCard.layoutParams = LinearLayout.LayoutParams(if (isWide) 0 else -1, -2, if (isWide) 1f else 0f)
        receiveCard.layoutParams = LinearLayout.LayoutParams(if (isWide) 0 else -1, -2, if (isWide) 1f else 0f).apply {
            if (isWide) marginStart = dp(16) else topMargin = dp(16)
        }
    }

    fun render(state: State) {
        val revealError = !state.error.isNullOrBlank() && state.error != this.state.error
        this.state = state
        val label = when (state.readiness) {
            "READY" -> R.string.rc_ready
            "PAUSED" -> R.string.rc_paused
            "CONNECTING" -> R.string.rc_connecting
            "UNAVAILABLE" -> R.string.rc_unavailable
            "NO NETWORK" -> R.string.rc_no_network
            "PERMISSION REQUIRED" -> R.string.flow_network_permission_title
            "RECEIVING", "PLAYING" -> R.string.rc_receiving
            else -> R.string.rc_starting
        }
        readiness.setText(label)
        val ready = state.readiness == "READY"
        banner.setBackgroundResource(if (ready) R.drawable.rc_ready else R.drawable.rc_row)
        readiness.setTextColor(activity.getColor(if (ready) R.color.rc_ready else R.color.rc_text))
        detail.text = state.detail
        detail.visibility = if (state.detail.isBlank()) GONE else VISIBLE
        networkPermission.visibility = if (state.localNetworkPermissionRequired) VISIBLE else GONE
        manual.setText(if (state.localNetworkPermissionRequired) R.string.flow_network_permission_action else R.string.rc_manual_connect)
        summary.text = state.settingsSummary
        summary.visibility = if (state.settingsSummary.isBlank()) GONE else VISIBLE
        status.text = state.status
        status.visibility = if (state.status.isBlank()) GONE else VISIBLE
        if (errorMessage.text.toString() != state.error.orEmpty()) errorMessage.text = state.error
        errorCard.visibility = if (state.error.isNullOrBlank()) GONE else VISIBLE
        if (revealError) post {
            if (this.state.error == state.error && errorCard.visibility == VISIBLE) scrollTo(0, 0)
        }
        manual.isEnabled = !state.sending && !state.receiving && state.outgoing == null
        renderEndpoints(state.endpoints.distinct())
        renderReceivers()
    }

    fun setReceivers(receivers: List<Discovery.Receiver>) {
        this.receivers = receivers.distinctBy { it.host to it.port }
        renderReceivers()
    }

    private fun renderReceivers() {
        val current = receivers.associateByTo(linkedMapOf()) { it.host to it.port }
        state.outgoing?.let { target ->
            val key = target.host to target.port
            if (key !in current) current[key] = Discovery.Receiver(target.name, target.host, target.port, "busy", 0)
        }
        val obsolete = receiverRows.keys.filter { it !in current }
        for (key in obsolete) nearby.removeView(receiverRows.remove(key)!!.view)
        for ((key, receiver) in current) {
            val row = receiverRows.getOrPut(key) {
                ReceiverRow().also { nearby.addView(it.view, spaced(top = 8)) }
            }
            row.render(receiver)
        }
        empty.visibility = if (current.isEmpty()) VISIBLE else GONE
    }

    private inner class ReceiverRow {
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(8), dp(12))
            setBackgroundResource(R.drawable.rc_row)
        }
        private val name = text("", 17f, bold = true)
        private val description = text("", 13f, secondary = true)
        private var target: ReceiverTarget? = null
        private var selected = false
        private val action = button(R.string.rc_send) {
            if (selected) onStopSending() else target?.let(onSend)
        }

        init {
            val labels = column()
            labels.addView(name)
            labels.addView(description, spaced(top = 4))
            view.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            view.addView(action, LinearLayout.LayoutParams(-2, -2))
        }

        fun render(receiver: Discovery.Receiver) {
            target = receiver.target()
            selected = state.outgoing?.let { it.host == receiver.host && it.port == receiver.port } == true
            name.text = receiver.name
            val availability = activity.getString(
                if (selected) R.string.rc_selected else if (receiver.state == "available") R.string.rc_available else R.string.rc_busy
            )
            description.text = activity.getString(R.string.rc_receiver_description, endpoint(receiver.host, receiver.port), availability)
            action.setText(if (selected) R.string.rc_stop else R.string.rc_send)
            action.contentDescription = activity.getString(
                if (selected) R.string.rc_stop_to else R.string.rc_send_to, receiver.name
            )
            action.isEnabled = selected || (!state.sending && !state.receiving && state.outgoing == null && receiver.state == "available")
        }
    }

    private fun renderEndpoints(endpoints: List<String>) {
        for (endpoint in endpointRows.keys.filter { it !in endpoints }) {
            addresses.removeView(endpointRows.remove(endpoint))
        }
        for (endpoint in endpoints) {
            if (endpoint in endpointRows) continue
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.rc_row)
                setPadding(dp(12), dp(4), dp(4), dp(4))
            }
            row.addView(text(endpoint, 16f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                textDirection = View.TEXT_DIRECTION_LTR
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button(R.string.rc_copy) {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(activity.getString(R.string.rc_clipboard_label), endpoint)
                )
                Toast.makeText(activity, R.string.rc_copied, Toast.LENGTH_SHORT).show()
            }.apply {
                contentDescription = activity.getString(R.string.rc_copy_endpoint, endpoint)
            }, LinearLayout.LayoutParams(-2, -2))
            endpointRows[endpoint] = row
            addresses.addView(row, spaced(top = 8))
        }
        noAddresses.visibility = if (endpoints.isEmpty()) VISIBLE else GONE
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        setBackgroundResource(R.drawable.rc_card)
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }

    private fun text(value: String, size: Float = 16f, secondary: Boolean = false, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(activity.getColor(if (secondary) R.color.rc_text_secondary else R.color.rc_text))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (bold && size >= 18f) isAccessibilityHeading = true
        }

    private fun button(label: Int, action: () -> Unit) = Button(activity).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        minimumHeight = dp(48)
        minimumWidth = dp(48)
        val disabled = activity.getColor(R.color.rc_text_secondary)
        val accent = activity.getColor(R.color.rc_accent)
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabled, accent))
        setTextColor(activity.getColor(R.color.rc_on_accent))
        setOnClickListener { action() }
    }

    private fun spaced(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun endpoint(host: String, port: Int) = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
}
