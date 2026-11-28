package dev.redrain.cast

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.redrain.cast.diagnostics.IncidentStore
import dev.redrain.cast.diagnostics.SessionTelemetryCollector
import dev.redrain.cast.diagnostics.TelemetryHistory
import dev.redrain.cast.diagnostics.TelemetryReport
import dev.redrain.cast.diagnostics.TelemetryRow
import dev.redrain.cast.diagnostics.DiagnosticStage
import dev.screencopy.sender.EncoderMetadata
import dev.screencopy.sender.HardwareEncoders
import dev.screencopy.sender.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SharingSettingsTest {
    private class HeldIncidentIo {
        private val tasks = java.util.ArrayDeque<Runnable>()
        val executor = Executor { tasks.addLast(it) }
        fun runNext() {
            assertFalse(tasks.isEmpty())
            tasks.removeFirst().run()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun seedTelemetryReport(activity: Activity): String {
        val store = IncidentStore(activity, executor = Executor { it.run() })
        store.clear {}
        val key = java.util.UUID.randomUUID().toString().replace("-", "")
        store.writeTelemetry(key, telemetryReport())
        var reports: List<IncidentStore.Report>? = null
        store.list { reports = it }
        return checkNotNull(reports).single().name
    }

    private fun telemetryReport(): ByteArray {
        val collector = SessionTelemetryCollector { 1 }
        collector.event(
            TelemetryRow(1, DiagnosticStage.PRESSURE.ordinal, 1, 1, 1, 5, listOf(0, 0, 0, 0, 1)),
            pressure = true
        )
        val history = TelemetryHistory(receiverSupported = false)
        assertTrue(history.accept(
            TelemetryHistory.Endpoint.SENDER, collector.publish(), 1))
        return TelemetryReport.encode(checkNotNull(history.finish(1)))
    }

    private fun showWithIncidentIo(activity: Activity, io: HeldIncidentIo, export: (String) -> Unit = {}) =
        SharingSettings.show(activity, Settings(), {}, {}, inventory(), null, export,
            { IncidentStore(it, executor = io.executor) })

    @Test fun delayedReportListCannotReopenClosedSettings() {
        val controller = activity()
        val io = HeldIncidentIo()
        val first = showWithIncidentIo(controller.get(), io)
        field<Button>(first, "sessionTelemetryReports").performClick()
        first.dismiss()
        val reopened = showWithIncidentIo(controller.get(), io)
        assertSame(reopened, latestDialog())
        io.runNext()
        assertSame(reopened, latestDialog())
        assertTrue(reopened.isShowing)
        reopened.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun delayedReportReadCannotReopenSettingsAfterActivityStop() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        seedTelemetryReport(activity)
        val io = HeldIncidentIo()
        val first = showWithIncidentIo(activity, io)
        org.robolectric.util.ReflectionHelpers.setField(activity, "settingsDialog", first)
        field<Button>(first, "sessionTelemetryReports").performClick()
        io.runNext()
        val reports = latestDialog()
        val list = reports.listView
        assertTrue(list.adapter.count > 0)
        list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(first.isShowing)
        controller.start().resume().visible()
        val reopened = showWithIncidentIo(activity, io)
        assertSame(reopened, latestDialog())
        io.runNext()
        assertSame(reopened, latestDialog())
        assertTrue(reopened.isShowing)
        reopened.dismiss()
        IncidentStore(activity, executor = Executor { it.run() }).clear {}
        controller.pause().stop().destroy()
    }

    @Test fun reportChildrenCloseWithSettingsAndExportRemainsAvailable() {
        val controller = activity()
        val activity = controller.get()
        val name = seedTelemetryReport(activity)
        val io = HeldIncidentIo()
        var exported: String? = null
        fun settings() = showWithIncidentIo(activity, io) { exported = it }
        fun reports(parent: AlertDialog): AlertDialog {
            field<Button>(parent, "sessionTelemetryReports").performClick()
            io.runNext()
            return latestDialog()
        }
        fun summary(parent: AlertDialog): AlertDialog {
            val list = reports(parent).listView
            list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
            io.runNext()
            return latestDialog()
        }

        var parent = settings()
        val list = reports(parent)
        parent.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(list.isShowing)

        parent = settings()
        val confirmationList = reports(parent)
        click(confirmationList, AlertDialog.BUTTON_NEUTRAL)
        val confirmation = latestDialog()
        parent.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(confirmation.isShowing)

        parent = settings()
        val details = summary(parent)
        assertNull(exported)
        parent.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(details.isShowing)
        assertNull(exported)

        parent = settings()
        val exportDetails = summary(parent)
        click(exportDetails, AlertDialog.BUTTON_NEUTRAL)
        assertEquals(name, exported)
        assertTrue(parent.isShowing)
        parent.dismiss()
        IncidentStore(activity, executor = Executor { it.run() }).clear {}
        controller.pause().stop().destroy()
    }

    @Test fun retainedTelemetrySummaryExportsOnlyAfterExplicitActionWithoutRestoringSettingsProse() {
        val controller = activity()
        val store = dev.redrain.cast.diagnostics.IncidentStore(controller.get())
        val key = java.util.UUID.randomUUID().toString().replace("-", "")
        store.writeTelemetry(key, telemetryReport())
        val saved = CountDownLatch(1)
        store.list { saved.countDown() }
        assertTrue(saved.await(5, TimeUnit.SECONDS))
        var exported: String? = null
        val dialog = SharingSettings.show(controller.get(), Settings(), {}, {}, inventory(), null, { exported = it })
        latestDialog()
        assertNull(checkNotNull(dialog.window).decorView.findViewWithTag<View>("automaticIncidentDisclosure"))
        field<Button>(dialog, "sessionTelemetryReports").performClick()
        val listed = CountDownLatch(1)
        store.list { listed.countDown() }
        assertTrue(listed.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        val reports = latestDialog()
        val list = reports.listView
        assertTrue(list.adapter.count > 0)
        list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
        val read = CountDownLatch(1)
        store.list { read.countDown() }
        assertTrue(read.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        val details = latestDialog()
        assertNull(exported)
        val scroll = details.findViewById<ViewGroup>(android.R.id.custom).getChildAt(0) as ScrollView
        assertTrue((scroll.getChildAt(0) as TextView).text.contains("bounded metadata"))
        click(details, AlertDialog.BUTTON_NEUTRAL)
        val selected = java.util.concurrent.atomic.AtomicReference<dev.redrain.cast.diagnostics.IncidentStore.ReadResult>()
        val selectedRead = CountDownLatch(1)
        store.read(checkNotNull(exported)) { selected.set(it); selectedRead.countDown() }
        assertTrue(selectedRead.await(5, TimeUnit.SECONDS))
        val bundle = org.json.JSONObject(dev.redrain.cast.diagnostics.IncidentSummary.export(
            (selected.get() as dev.redrain.cast.diagnostics.IncidentStore.ReadResult.Success).content))
        assertFalse(bundle.getBoolean("receiverSupported"))
        assertTrue(bundle.getString("summary").contains("Receiver telemetry: unsupported"))
        assertTrue(bundle.getString("summary").contains("unknown"))
        assertTrue(bundle.has("vocabulary"))
        details.dismiss(); reports.dismiss(); dialog.dismiss()
        controller.pause().stop().destroy()
    }
    @Test fun remoteConsentIsIndependentButCannotBeGrantedByAnUnsavedDraft() {
        val controller = activity()
        val dialog = show(controller.get(), Settings(), {}, {})
        latestDialog()
        val statistics = field<CheckBox>(dialog, "showPlaybackStatistics")
        val remote = field<CheckBox>(dialog, "allowRemoteDiagnostics")
        val settingsView = checkNotNull(dialog.window).decorView as ViewGroup
        assertNull(settingsView.findViewWithTag<View>("automaticIncidentDisclosure"))
        assertEquals("View or clear session telemetry reports",
            settingsView.findViewWithTag<Button>("sessionTelemetryReports").text.toString())
        assertNull(settingsView.findViewWithTag<View>("playbackStatisticsHint"))
        assertNotNull(settingsView.findViewWithTag<View>("remoteDiagnosticsWarning"))
        assertEquals(controller.get().getString(R.string.rc_playback_statistics),
            statistics.text.toString())
        assertTrue(remote.text.contains("unpaired LAN"))
        assertTrue(remote.isEnabled)
        remote.isChecked = true
        statistics.isChecked = true
        click(dialog, AlertDialog.BUTTON_NEGATIVE)
        assertFalse(dev.redrain.cast.diagnostics.DiagnosticAccess.enabled)
        assertFalse(dev.redrain.cast.diagnostics.RemoteDiagnostics.recorder.collecting)
        val second = show(controller.get(), Settings().apply { showPlaybackStatistics = true }, {}, {})
        latestDialog()
        val newRemote = field<CheckBox>(second, "allowRemoteDiagnostics")
        assertFalse(newRemote.isChecked)
        newRemote.isChecked = true
        field<CheckBox>(second, "showPlaybackStatistics").isChecked = false
        assertTrue(newRemote.isChecked)
        assertTrue(newRemote.isEnabled)
        second.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun remoteConsentShowsInlineWarningBeforeSavingWithoutPlaybackStatistics() {
        val controller = activity()
        val activity = controller.get()
        dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity, false)
        var saved: Settings? = null
        val dialog = show(activity, Settings(), { saved = it }, {})
        assertSame(dialog, latestDialog())
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            val remote = field<CheckBox>(dialog, "allowRemoteDiagnostics")
            val warning = field<TextView>(dialog, "remoteDiagnosticsWarning")
            val parent = remote.parent as LinearLayout
            assertSame(warning, parent.getChildAt(parent.indexOfChild(remote) + 1))
            assertTrue(warning.isShown)
            assertTrue(warning.text.contains("LAN or VPN"))
            assertTrue(warning.text.contains("unencrypted and unauthenticated"))
            assertTrue(warning.text.contains("without a receiver settings change"))
            assertTrue(warning.text.contains("diagnostic captures"))
            assertFalse(field<CheckBox>(dialog, "showPlaybackStatistics").isChecked)
            remote.performClick()
            assertTrue(remote.isChecked)
            assertTrue(warning.isShown)
            assertFalse(dev.redrain.cast.diagnostics.DiagnosticAccess.enabled)
            val generation = dev.redrain.cast.diagnostics.DiagnosticAccess.consentGeneration
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).hasOnClickListeners())
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertNotNull(field<TextView>(dialog, "settingsError").text.toString(), saved)
            assertFalse(checkNotNull(saved).showPlaybackStatistics)
            assertFalse(dialog.isShowing)
            assertEquals(generation + 1, dev.redrain.cast.diagnostics.DiagnosticAccess.consentGeneration)
        } finally {
            dev.redrain.cast.diagnostics.DiagnosticAccess.setEnabled(activity, false)
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun remoteEndpointDialogIsScreenshotProtectedAndDoesNotSaveSettings() {
        val controller = activity()
        var saved = false
        val dialog = show(controller.get(), Settings(), { saved = true }, {})
        latestDialog()
        field<Button>(dialog, "remoteDiagnosticsStatus").performClick()
        val pairing = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(checkNotNull(pairing.window).attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        val status = pairing.findViewById<ViewGroup>(android.R.id.custom).getChildAt(0) as TextView
        assertTrue(status.text.contains("LAN or VPN"))
        assertTrue(status.text.contains("unencrypted and unauthenticated"))
        assertTrue(status.text.contains("without a receiver settings change"))
        assertFalse(saved)
        assertFalse(dev.redrain.cast.diagnostics.DiagnosticAccess.enabled)
        pairing.dismiss()
        dialog.dismiss()
        controller.pause().stop().destroy()
    }
    @Test fun advancedFullDiagnosticsActionDoesNotSaveOrMutateDraftSettings() {
        val controller = activity()
        val current = Settings()
        var saved = false
        var opened = 0
        val dialog = SharingSettings.show(controller.get(), current, { saved = true }, {}, inventory()) { opened++ }
        latestDialog()
        val diagnostics = field<Button>(dialog, "playbackDiagnostics")
        assertFalse(diagnostics.isShown)
        field<Button>(dialog, "advancedToggle").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(diagnostics.isShown)
        diagnostics.performClick()
        assertEquals(1, opened)
        assertFalse(saved)
        assertFalse(current.showPlaybackStatistics)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun clearReportsWarningIncludesPostStopRewrites() {
        val controller = activity()
        val dialog = show(controller.get(), Settings(), {}, {})
        latestDialog()
        field<Button>(dialog, "advancedToggle").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val settingsView = checkNotNull(dialog.window).decorView as ViewGroup
        assertNull(settingsView.findViewWithTag<View>("automaticIncidentDisclosure"))
        assertEquals("View or clear session telemetry reports",
            field<Button>(dialog, "sessionTelemetryReports").text.toString())
        field<Button>(dialog, "sessionTelemetryReports").performClick()

        val listFinished = CountDownLatch(1)
        dev.redrain.cast.diagnostics.IncidentStore(controller.get()).list { listFinished.countDown() }
        assertTrue(listFinished.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        val reports = latestDialog()
        reports.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        val confirmation = latestDialog()
        val message = confirmation.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains("active or recently stopped cast"))
        assertTrue(message.contains("after clearing"))
        confirmation.dismiss()
        reports.dismiss()
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun playbackStatisticsCheckboxDefaultsOffPersistsAndDoesNotMutateAnAlreadySelectedSession() {
        val controller = activity()
        val current = Settings()
        var saved: Settings? = null
        val dialog = show(controller.get(), current, { saved = it }, {})
        latestDialog()
        val checkbox = field<CheckBox>(dialog, "showPlaybackStatistics")
        assertFalse(checkbox.isChecked)
        assertFalse("Diagnostic option must start inside collapsed Advanced", checkbox.isShown)
        val remote = field<CheckBox>(dialog, "allowRemoteDiagnostics")
        assertFalse(remote.isChecked)
        assertTrue(remote.isEnabled)
        field<Button>(dialog, "advancedToggle").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(checkbox.isShown)
        checkbox.isChecked = true
        assertTrue(remote.isEnabled)
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertNotNull(field<TextView>(dialog, "settingsError").text.toString(), saved)
        assertFalse(current.showPlaybackStatistics)
        assertTrue(checkNotNull(saved).showPlaybackStatistics)
        assertTrue(SharingSettings.load(controller.get()) { throw it }.showPlaybackStatistics)
        assertFalse(checkNotNull(saved).request().has("receiverOptions"))
        controller.pause().stop().destroy()
    }

    class FailedWriteActivity : Activity() {
        var failWrites = false

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (!failWrites || name != SharingSettings.PREFERENCES) return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            editor.putString(key, value)
                            return this
                        }
                        override fun commit() = false
                    }
                }
            }
        }
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).also {
        it.get().setTheme(R.style.Theme_RedrainCast)
        it.setup()
        it.get().getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun disablingAudioPersistsVideoOnlySelectionWithoutChangingOtherSettings() {
        val controller = activity()
        val current = Settings().apply {
            audioCodec = "opus"
            audioSource = "mic"
            audioBitrate = 128_000
            maxFps = 47
            videoBitrate = 9_500_000
            showPlaybackStatistics = true
        }
        var saved: Settings? = null
        val dialog = show(controller.get(), current, { saved = it }, {})
        try {
            assertSame(dialog, latestDialog())
            field<CheckBox>(dialog, "audio").performClick()
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertNotNull(field<TextView>(dialog, "settingsError").text.toString(), saved)
            val selected = checkNotNull(saved)
            val storedAudio = selected.saved().getJSONObject("audio")
            assertEquals("opus", storedAudio.getString("codec"))
            assertEquals("mic", storedAudio.getString("source"))
            assertEquals(128_000, storedAudio.getInt("bitrate"))
            val inactiveAudio = selected.request().getJSONObject("audio")
            assertFalse(inactiveAudio.getBoolean("enabled"))
            assertEquals("raw", inactiveAudio.getString("codec"))
            assertEquals("playback", inactiveAudio.getString("source"))
            assertTrue(selected.showPlaybackStatistics)
            assertTrue("Editing a draft must not mutate a prior session's settings", current.audio)
            val loaded = SharingSettings.load(controller.get()) { throw it }
            assertEquals(selected.saved().toString(), loaded.saved().toString())
            val requests = SendRequests(RoleOwner())
            val pending = requests.reserve(loaded.saved().toString(),
                dev.screencopy.sender.ReceiverTarget("127.0.0.1", 7007))
            val adopted = Settings.from(org.json.JSONObject(requests.adopt(pending.id).settings))
            val effective = org.screencopy.receiver.protocol.Request.parse(adopted.request())
            assertEquals(listOf(1), effective.tracks)
            assertNull(effective.audioCodec)
            assertTrue(requests.finish(pending.id))

            var restored: Settings? = null
            val reopened = show(controller.get(), loaded, { restored = it }, {})
            assertSame(reopened, latestDialog())
            field<CheckBox>(reopened, "audio").performClick()
            click(reopened, AlertDialog.BUTTON_POSITIVE)
            assertEquals(current.saved().toString(), checkNotNull(restored).saved().toString())
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun trackTogglesKeepOneUsableAndFullscreenPreservesDormantCrop() {
        val controller = activity()
        val current = Settings().apply {
            cropWidth = 120
            cropHeight = 80
            cropX = 12
            cropY = 8
        }
        var saved: Settings? = null
        val dialog = show(controller.get(), current, { saved = it }, {})
        try {
            val video = field<CheckBox>(dialog, "videoEnabled")
            val audio = field<CheckBox>(dialog, "audio")
            assertEquals(video.id, field<TextView>(dialog, "videoDisabledHint").labelFor)
            assertEquals(audio.id, field<TextView>(dialog, "audioDisabledHint").labelFor)
            assertTrue(video.isChecked && audio.isChecked)
            assertFalse(field<TextView>(dialog, "videoDisabledHint").isShown)
            assertFalse(field<TextView>(dialog, "audioDisabledHint").isShown)
            video.performClick()
            assertFalse(video.isChecked)
            assertFalse(field<Button>(dialog, "videoCodec").isShown)
            assertFalse(field<TextView>(dialog, "videoDisabledHint").isShown)
            assertTrue(field<TextView>(dialog, "audioDisabledHint").isShown)
            assertEquals(controller.get().getString(R.string.rc_last_track_hint),
                field<TextView>(dialog, "audioDisabledHint").text.toString())
            assertFalse(audio.isEnabled)
            assertTrue(video.isEnabled)
            video.performClick()
            assertTrue(audio.isEnabled)
            audio.performClick()
            assertTrue(field<TextView>(dialog, "videoDisabledHint").isShown)
            assertFalse(field<TextView>(dialog, "audioDisabledHint").isShown)
            assertEquals(controller.get().getString(R.string.rc_last_track_hint),
                field<TextView>(dialog, "videoDisabledHint").text.toString())
            audio.performClick()
            field<Button>(dialog, "advancedToggle").performClick()
            val fullscreen = field<CheckBox>(dialog, "fullscreen")
            fullscreen.isChecked = true
            assertFalse(field<EditText>(dialog, "cropWidth").isShown)
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            val result = checkNotNull(saved)
            assertTrue(result.fullscreen)
            assertEquals(120, result.cropWidth)
            assertEquals(80, result.cropHeight)
            assertEquals(12, result.cropX)
            assertEquals(8, result.cropY)
            assertTrue(result.saved().getJSONObject("video").getBoolean("enabled"))
            assertTrue(result.saved().getJSONObject("video").getBoolean("fullscreen"))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun editedTrackDraftsSurviveDisablingSavingAndReenabling() {
            val controller = activity()
            try {
                var videoSaved: Settings? = null
                val videoDialog = show(controller.get(), Settings(), { videoSaved = it }, {})
                choose(videoDialog, "maxFps", 4)
                choose(videoDialog, "receiverOrientation", 2)
                field<CheckBox>(videoDialog, "videoEnabled").performClick()
                click(videoDialog, AlertDialog.BUTTON_POSITIVE)
                val videoOff = checkNotNull(videoSaved)
                assertEquals(30, videoOff.maxFps)
                assertEquals("portrait", videoOff.receiverOrientation)
                assertEquals(0, videoOff.request().getJSONObject("video").getInt("maxFps"))
                var videoRestored: Settings? = null
                val reopenedVideo = show(controller.get(), SharingSettings.load(controller.get()) { throw it },
                    { videoRestored = it }, {})
                field<CheckBox>(reopenedVideo, "videoEnabled").performClick()
                click(reopenedVideo, AlertDialog.BUTTON_POSITIVE)
                assertEquals(30, checkNotNull(videoRestored).maxFps)
                assertEquals("portrait", checkNotNull(videoRestored).receiverOrientation)

                var audioSaved: Settings? = null
                val audioDialog = show(controller.get(), Settings(), { audioSaved = it }, {})
                choose(audioDialog, "audioCodec", 1)
                choose(audioDialog, "audioSource", 1)
                field<EditText>(audioDialog, "audioBitrate").setText("128")
                field<CheckBox>(audioDialog, "audio").performClick()
                click(audioDialog, AlertDialog.BUTTON_POSITIVE)
                val audioOff = checkNotNull(audioSaved)
                assertEquals("opus", audioOff.audioCodec)
                assertEquals("mic", audioOff.audioSource)
                assertEquals(128_000, audioOff.audioBitrate)
                assertEquals("raw", audioOff.request().getJSONObject("audio").getString("codec"))
                var audioRestored: Settings? = null
                val reopenedAudio = show(controller.get(), SharingSettings.load(controller.get()) { throw it },
                    { audioRestored = it }, {})
                field<CheckBox>(reopenedAudio, "audio").performClick()
                click(reopenedAudio, AlertDialog.BUTTON_POSITIVE)
                assertEquals(128_000, checkNotNull(audioRestored).audioBitrate)
                assertEquals("opus", checkNotNull(audioRestored).audioCodec)
            } finally {
                controller.pause().stop().destroy()
            }
        }

        @Test fun invalidHiddenAudioDraftDoesNotReplacePriorValidBitrate() {
            val controller = activity()
            try {
                var saved: Settings? = null
                val dialog = show(controller.get(), Settings().apply {
                    audioCodec = "opus"; audioBitrate = 128_000
                }, { saved = it }, {})
                field<EditText>(dialog, "audioBitrate").setText("unfinished")
                field<CheckBox>(dialog, "audio").performClick()
                click(dialog, AlertDialog.BUTTON_POSITIVE)
                assertEquals(128_000, checkNotNull(saved).audioBitrate)
            } finally { controller.pause().stop().destroy() }
        }

        @Test fun unsupportedDormantChoicesLoadAndFailValidationWhenReenabled() {
            val controller = activity()
            try {
                val dormantVideo = Settings().apply { video = false; receiverOrientation = "sideways" }
                val videoDialog = show(controller.get(), dormantVideo, {}, {})
                assertTrue(field<Button>(videoDialog, "receiverOrientation").text.contains("sideways"))
                field<CheckBox>(videoDialog, "videoEnabled").performClick()
                click(videoDialog, AlertDialog.BUTTON_POSITIVE)
                assertTrue(videoDialog.isShowing)
                assertTrue(field<TextView>(videoDialog, "settingsError").text.contains("Invalid receiver orientation"))
                videoDialog.dismiss()

                val dormantAudio = Settings().apply {
                    audio = false; audioCodec = "unknown-codec"; audioSource = "unknown-source"
                }
                val audioDialog = show(controller.get(), dormantAudio, {}, {})
                assertTrue(field<Button>(audioDialog, "audioCodec").text.contains("unknown-codec"))
                assertTrue(field<Button>(audioDialog, "audioSource").text.contains("unknown-source"))
                field<CheckBox>(audioDialog, "audio").performClick()
                click(audioDialog, AlertDialog.BUTTON_POSITIVE)
                assertTrue(audioDialog.isShowing)
                assertTrue(field<TextView>(audioDialog, "settingsError").text.contains("Unknown audio codec"))
                audioDialog.dismiss()
            } finally { controller.pause().stop().destroy() }
        }

        @Test fun summaryDescribesOnlyEnabledTracks() {
            val controller = activity()
            try {
                SharingSettings.load(controller.get()) { throw it }
                val settings = Settings().apply { audioCodec = "opus" }
                assertTrue(SharingSettings.summary(settings).contains("H264"))
                assertTrue(SharingSettings.summary(settings).contains("OPUS"))
                settings.audio = false
                assertTrue(SharingSettings.summary(settings).contains(controller.get().getString(R.string.rc_summary_audio_off)))
                settings.video = false; settings.audio = true
                val summary = SharingSettings.summary(settings)
                assertTrue(summary.contains(controller.get().getString(R.string.rc_summary_video_off)))
                assertTrue(summary.contains("OPUS"))
                assertFalse(summary.contains("H264"))
            } finally { controller.pause().stop().destroy() }
        }
    @Test fun sizePresetsAboveCurrentCaptureOrCropAreHiddenButSavedSelectionRemains() {
        val presets = listOf(0, 1280, 1920, 2560, 3840)
        assertEquals(listOf(0, 1280, 1920, 3840),
            SharingSettings.sizePresetsWithinCapture(presets, 1920, 3840))
        assertEquals(listOf(0, 1280, 3840),
            SharingSettings.sizePresetsWithinCapture(presets, 1280, 3840))
        assertEquals(presets, SharingSettings.sizePresetsWithinCapture(presets, null, 3840))

        fun labels(dialog: AlertDialog): List<String> {
            val picker = openChoice(dialog, "maxSize")
            val values = (0 until picker.listView.count).map {
                picker.listView.adapter.getItem(it).toString()
            }
            picker.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            return values
        }
        fun preset(label: String): Int? =
            Regex("^(\\d+) px").find(label)?.groupValues?.get(1)?.toIntOrNull()

        val controller = activity()
        val current = Settings().apply { maxSize = 3840 }
        val dialog = show(controller.get(), current, { fail("Cancel size selection") }, {})
        try {
            val naturalBounds = run {
                field<Button>(dialog, "advancedToggle").performClick()
                val helper = field<TextView>(dialog, "cropBoundsHint").text.toString()
                val dimensions = Regex("\\((\\d+) × (\\d+) px\\)").find(helper)
                    ?: throw AssertionError("Natural display dimensions missing from $helper")
                maxOf(dimensions.groupValues[1].toInt(), dimensions.groupValues[2].toInt())
            }
            val fullScreenOptions = labels(dialog)
            assertTrue(fullScreenOptions.any { preset(it) == current.maxSize })
            assertFalse(fullScreenOptions.any {
                val value = preset(it)
                value != null && value > naturalBounds && value != current.maxSize
            })

            field<EditText>(dialog, "cropWidth").setText("640")
            field<EditText>(dialog, "cropHeight").setText("480")
            field<EditText>(dialog, "cropX").setText("0")
            field<EditText>(dialog, "cropY").setText("0")
            field<CheckBox>(dialog, "fullscreen").isChecked = false
            val cropOptions = labels(dialog)
            assertTrue(cropOptions.any { preset(it) == current.maxSize })
            assertFalse(cropOptions.any { preset(it) != null && preset(it)!! > 640 && preset(it) != current.maxSize })
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun legacyNoCropRendersFullscreenAndInitialUncropUsesNaturalBounds() {
        val controller = activity()
        val current = Settings()
        assertFalse(current.fullscreen)
        assertTrue(current.request().getJSONObject("video").isNull("crop"))
        var saved: Settings? = null
        val dialog = show(controller.get(), current, { saved = it }, {})
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            assertNull(dialog.window!!.decorView.findViewWithTag<View>("clearCrop"))
            val fullscreen = field<CheckBox>(dialog, "fullscreen")
            assertTrue(fullscreen.isChecked)
            assertEquals(View.GONE, field<EditText>(dialog, "cropWidth").visibility)
            fullscreen.isChecked = false
            val dimensions = Regex("\\((\\d+) × (\\d+) px\\)")
                .find(field<TextView>(dialog, "cropBoundsHint").text.toString())
                ?: throw AssertionError("Natural display bounds unavailable")
            assertEquals(dimensions.groupValues[1], field<EditText>(dialog, "cropWidth").text.toString())
            assertEquals(dimensions.groupValues[2], field<EditText>(dialog, "cropHeight").text.toString())
            field<EditText>(dialog, "cropWidth").setText("123")
            fullscreen.isChecked = true
            fullscreen.isChecked = false
            assertEquals("123", field<EditText>(dialog, "cropWidth").text.toString())
            fullscreen.isChecked = true
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            val result = checkNotNull(saved)
            assertTrue(result.fullscreen)
            assertTrue(result.request().getJSONObject("video").isNull("crop"))
            assertEquals(123, result.cropWidth)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun fullscreenAndVideoOffPreserveValidCropDraftsAndIgnoreInvalidHiddenText() {
        for (hideByFullscreen in listOf(true, false)) {
            val controller = activity()
            val current = Settings().apply { cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 8 }
            var saved: Settings? = null
            val dialog = show(controller.get(), current, { saved = it }, {})
            try {
                field<Button>(dialog, "advancedToggle").performClick()
                val width = field<EditText>(dialog, "cropWidth")
                width.setText("700")
                if (hideByFullscreen) field<CheckBox>(dialog, "fullscreen").isChecked = true
                else field<CheckBox>(dialog, "videoEnabled").performClick()
                click(dialog, AlertDialog.BUTTON_POSITIVE)
                val result = checkNotNull(saved)
                assertEquals(700, result.cropWidth)
                assertEquals(480, result.cropHeight)
                assertEquals(12, result.cropX)
                assertEquals(8, result.cropY)
                assertEquals(hideByFullscreen, result.fullscreen)
                assertEquals(hideByFullscreen, result.video)
            } finally {
                dialog.dismiss()
                controller.pause().stop().destroy()
            }
        }

        for (hideByFullscreen in listOf(true, false)) {
            val controller = activity()
            val current = Settings().apply { cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 8 }
            var saved: Settings? = null
            val dialog = show(controller.get(), current, { saved = it }, {})
            try {
                field<Button>(dialog, "advancedToggle").performClick()
                field<EditText>(dialog, "cropWidth").setText("unfinished")
                if (hideByFullscreen) field<CheckBox>(dialog, "fullscreen").isChecked = true
                else field<CheckBox>(dialog, "videoEnabled").performClick()
                click(dialog, AlertDialog.BUTTON_POSITIVE)
                val result = checkNotNull(saved)
                assertEquals(640, result.cropWidth)
                assertEquals(480, result.cropHeight)
                assertEquals(12, result.cropX)
                assertEquals(8, result.cropY)
                assertEquals(hideByFullscreen, result.fullscreen)
                assertEquals(hideByFullscreen, result.video)
            } finally {
                dialog.dismiss()
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun activeCropBoundsErrorIsAttachedToFieldsAndUsesNaturalOrientationHelper() {
        val controller = activity()
        val current = Settings()
        val dialog = show(controller.get(), current, { fail("Invalid active crop must not save") }, {})
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            val helper = field<TextView>(dialog, "cropBoundsHint")
            val dimensions = Regex("\\((\\d+) × (\\d+) px\\)").find(helper.text.toString())
                ?: throw AssertionError("Natural display bounds unavailable: ${helper.text}")
            val naturalWidth = dimensions.groupValues[1].toInt()
            field<CheckBox>(dialog, "fullscreen").isChecked = false
            field<EditText>(dialog, "cropWidth").setText((naturalWidth + 1).toString())
            field<EditText>(dialog, "cropHeight").setText("100")
            field<EditText>(dialog, "cropX").setText("0")
            field<EditText>(dialog, "cropY").setText("0")
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertNotNull(field<EditText>(dialog, "cropWidth").error)
            assertNotNull(field<EditText>(dialog, "cropX").error)
            assertTrue(helper.text.toString().contains("natural screen orientation"))
            assertFalse(field<TextView>(dialog, "settingsError").isShown)
            assertTrue(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun audioOnlySaveEmitsStrictTrackFlagButKeepsVideoPreferences() {
        val settings = Settings().apply {
            video = false
            audio = true
            fullscreen = true
            cropWidth = 480
            cropHeight = 320
            cropX = 10
            cropY = 20
        }
        settings.validate()
        val wire = settings.request()
        val video = wire.getJSONObject("video")
        assertEquals(false, video.get("enabled"))
        assertTrue(video.isNull("crop"))
        assertEquals(480, settings.saved().getJSONObject("video").getJSONObject("crop").getInt("width"))
        val oldSchema = org.json.JSONObject(settings.saved().toString()).apply {
            getJSONObject("video").remove("enabled")
        }
        assertTrue(Settings.from(oldSchema).video)
        assertThrows(IllegalArgumentException::class.java) {
            Settings().apply { this.video = false; audio = false }.validate()
        }
    }

    @Test fun inactiveTrackAndFullscreenCropAreLosslesslySavedButCanonicalizedOnWire() {
        val settings = Settings().apply {
            video = false
            audio = true
            videoCodec = "retired-video-codec"
            videoEncoder = "retired video encoder"
            videoBitrate = -1
            maxSize = -9
            maxFps = 999
            displayId = -7
            orientation = "invalid"
            receiverOrientation = "invalid"
            videoOptions = "not=a valid option"
            cropWidth = -40
            cropHeight = 0
            cropX = -1
            cropY = 8
        }
        settings.validate()
        val wire = settings.request()
        val wireVideo = wire.getJSONObject("video")
        assertEquals(false, wireVideo.getBoolean("enabled"))
        assertEquals("h264", wireVideo.getString("codec"))
        assertEquals(8_000_000, wireVideo.getInt("bitrate"))
        assertEquals(1920, wireVideo.getInt("maxSize"))
        assertEquals(0, wireVideo.getInt("maxFps"))
        assertEquals(0, wireVideo.getInt("displayId"))
        assertEquals("0", wireVideo.getString("orientation"))
        assertEquals("", wireVideo.getString("codecOptions"))
        assertTrue(wireVideo.isNull("crop"))
        assertEquals("auto", wire.getString("receiverOrientation"))

        val restored = Settings.from(settings.saved())
        assertFalse(restored.video)
        assertEquals("retired-video-codec", restored.videoCodec)
        assertEquals(-1, restored.videoBitrate)
        assertEquals(-40, restored.cropWidth)
        assertEquals(0, restored.cropHeight)
        assertEquals(-1, restored.cropX)
        assertEquals(8, restored.cropY)
        restored.validate()

        val fullscreen = Settings().apply {
            fullscreen = true
            cropWidth = -40
            cropHeight = 0
            cropX = -1
            cropY = 8
        }
        fullscreen.validate()
        val fullscreenWire = fullscreen.request().getJSONObject("video")
        assertTrue(fullscreenWire.isNull("crop"))
        val fullscreenRestored = Settings.from(fullscreen.saved())
        assertTrue(fullscreenRestored.fullscreen)
        assertEquals(-40, fullscreenRestored.cropWidth)
        assertEquals(-1, fullscreenRestored.cropX)
    }

    @Test fun persistedSettingsRejectWrongJsonTypesAndMissingCropInsteadOfCoercing() {
        val valid = Settings().saved()
        val invalid = listOf(
            org.json.JSONObject(valid.toString()).apply { getJSONObject("video").put("bitrate", "8000000") },
            org.json.JSONObject(valid.toString()).apply { getJSONObject("video").put("enabled", "false") },
            org.json.JSONObject(valid.toString()).apply { getJSONObject("video").put("fullscreen", 1) },
            org.json.JSONObject(valid.toString()).apply { getJSONObject("video").remove("crop") },
            org.json.JSONObject(valid.toString()).apply {
                getJSONObject("video").put("crop", org.json.JSONObject().put("width", "20")
                    .put("height", 20).put("x", 0).put("y", 0))
            },
            org.json.JSONObject(valid.toString()).apply { getJSONObject("audio").put("enabled", 1) },
            org.json.JSONObject(valid.toString()).apply { put("ShowPlaybackStatistics", "true") }
        )
        for (root in invalid)
            assertThrows(IllegalArgumentException::class.java) { Settings.from(root) }
        val legacy = org.json.JSONObject(valid.toString()).apply {
            getJSONObject("video").remove("enabled")
            getJSONObject("video").remove("fullscreen")
        }
        assertTrue(Settings.from(legacy).video)
        assertFalse(Settings.from(legacy).fullscreen)
    }

    @Test fun audioOnlySettingsRemainSavableWithoutVideoEncoderHardware() {
        val controller = activity()
        var saved: Settings? = null
        val dialog = show(controller.get(), Settings(), { saved = it }, {}, HardwareEncoders(emptyList()))
        try {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            assertFalse(save.isEnabled)
            val video = field<CheckBox>(dialog, "videoEnabled")
            video.performClick()
            assertFalse(video.isChecked)
            assertTrue(save.isEnabled)
            assertFalse(field<Button>(dialog, "videoCodec").isShown)
            assertFalse(field<TextView>(dialog, "settingsError").isShown)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(checkNotNull(saved).video)
            assertTrue(saved!!.audio)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    private fun <T : View> field(dialog: AlertDialog, tag: String): T = dialog.window!!.decorView.findViewWithTag(tag)

    private fun inventory(av1Hardware: Boolean = true) = HardwareEncoders(listOf(
        EncoderMetadata("vendor.avc", "vendor.avc", listOf("video/avc"), true, true, false),
        EncoderMetadata("vendor.hevc", "vendor.hevc", listOf("video/hevc"), true, true, false),
        EncoderMetadata("vendor.av1", "vendor.av1", listOf("video/av01"), true, av1Hardware, !av1Hardware),
        EncoderMetadata("vendor.av1.decoder", "vendor.av1.decoder", listOf("video/av01"), false, true, false)))

    private fun show(activity: Activity, current: Settings, onSave: (Settings) -> Unit, onNotices: () -> Unit,
        encoders: HardwareEncoders = inventory()) = SharingSettings.show(activity, current, onSave, onNotices, encoders)

    private fun latestDialog(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun click(dialog: AlertDialog, which: Int) {
        dialog.getButton(which).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun openChoice(parent: AlertDialog, key: String): AlertDialog {
        val button = field<Button>(parent, key)
        if (!button.isShown && key in listOf("audioCodec", "audioSource")) {
            field<Button>(parent, "advancedToggle").performClick()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(button.isEnabled)
        button.performClick()
        val picker = latestDialog()
        assertNotSame(parent, picker)
        assertTrue(parent.isShowing)
        assertTrue(picker.isShowing)
        assertEquals(AbsListView.CHOICE_MODE_SINGLE, picker.listView.choiceMode)
        return picker
    }

    private fun choose(parent: AlertDialog, key: String, position: Int) {
        val picker = openChoice(parent, key)
        val list = picker.listView
        assertTrue(position in 0 until list.count)
        list.performItemClick(list.adapter.getView(position, null, list), position, list.adapter.getItemId(position))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(picker.isShowing)
        assertTrue(parent.isShowing)
    }

    private fun assertNoSpinners(view: View) {
        assertFalse(view is Spinner)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) assertNoSpinners(view.getChildAt(index))
        }
    }

    @Test fun textInputsSelectAllOnFocusAndAllowCursorEditsUntilFocusReturns() {
        val controller = activity()
        val current = Settings().apply {
            cropWidth = 100
            cropHeight = 200
            cropX = 12
            cropY = 16
            audioCodec = "opus"
        }
        val dialog = show(controller.get(), current, { fail("Focus change saved settings") }, {})
        latestDialog()
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val keys = listOf("cropWidth", "cropHeight", "cropX", "cropY", "audioBitrate")
            for (key in keys) {
                val input = field<EditText>(dialog, key)
                assertTrue(key, input.requestFocus())
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(key, 0, input.selectionStart)
                assertEquals(key, input.text.length, input.selectionEnd)
                input.setSelection(1)
                assertTrue(input.requestFocus())
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(key, 1, input.selectionStart)
                assertEquals(key, 1, input.selectionEnd)
                assertTrue(field<EditText>(dialog, if (key == "cropWidth") "cropHeight" else "cropWidth").requestFocus())
                assertFalse(key, input.hasFocus())
                assertTrue(input.requestFocus())
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(key, 0, input.selectionStart)
                assertEquals(key, input.text.length, input.selectionEnd)
            }
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun replacingSelectedInputNeedsNoDeletionAndCancelPreservesSettings() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings()
        val original = current.saved().toString()
        val preferences = activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().putString(SharingSettings.REQUEST, original).commit()
        val dialog = show(activity, current, { fail("Cancel saved settings") }, {})
        latestDialog()
        try {
            choose(dialog, "maxSize", 0)
            assertTrue(field<Button>(dialog, "maxSize").text.toString().contains("Original"))
            assertEquals(1920, current.maxSize)
            click(dialog, AlertDialog.BUTTON_NEGATIVE)
            assertEquals(original, preferences.getString(SharingSettings.REQUEST, null))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun nativeChoicesCheckCurrentDraftAndOnlyPersistAfterParentSave() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply {
            videoCodec = "h265"
            receiverOrientation = "portrait"
            audioCodec = "aac"
            audioSource = "mic"
        }
        val original = current.request().toString()
        val preferences = activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().putString(SharingSettings.REQUEST, original).commit()
        var saved: Settings? = null
        val dialog = show(activity, current, { saved = it }, {})
        latestDialog()
        try {
            assertEquals(AlertDialog::class.java, dialog.javaClass)
            assertNoSpinners(dialog.window!!.decorView)
            for ((key, checked, selected) in listOf(
                Triple("videoCodec", 1, 2),
                Triple("maxFps", 0, 9),
                Triple("receiverOrientation", 2, 1),
                Triple("audioCodec", 2, 1),
                Triple("audioSource", 1, 0)
            )) {
                assertEquals(Button::class.java, field<Button>(dialog, key).javaClass)
                val picker = openChoice(dialog, key)
                assertEquals(AlertDialog::class.java, picker.javaClass)
                assertNoSpinners(picker.window!!.decorView)
                assertEquals(checked, picker.listView.checkedItemPosition)
                val selectedLabel = picker.listView.getItemAtPosition(selected).toString()
                val list = picker.listView
                list.performItemClick(list.adapter.getView(selected, null, list), selected, list.adapter.getItemId(selected))
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(picker.isShowing)
                assertEquals(selectedLabel, field<Button>(dialog, key).text.toString())
                val reopened = openChoice(dialog, key)
                assertEquals(selected, reopened.listView.checkedItemPosition)
                click(reopened, AlertDialog.BUTTON_NEGATIVE)
                assertTrue(dialog.isShowing)
                assertNull(saved)
                assertEquals(original, current.request().toString())
                assertEquals(original, preferences.getString(SharingSettings.REQUEST, null))
            }
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(dialog.isShowing)
            val result = checkNotNull(saved)
            assertEquals("av1", result.videoCodec)
            assertEquals(120, result.maxFps)
            assertEquals("landscape", result.receiverOrientation)
            assertEquals("opus", result.audioCodec)
            assertEquals("playback", result.audioSource)
            assertEquals(result.saved().toString(), preferences.getString(SharingSettings.REQUEST, null))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun childCancelAndBackKeepPriorChoiceWithoutSavingOrClosingParent() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply {
            videoCodec = "h265"
            receiverOrientation = "landscape"
            audioCodec = "opus"
            audioSource = "mic"
        }
        val original = current.request().toString()
        val dialog = show(activity, current, { fail("Child cancellation saved settings") }, {})
        latestDialog()
        try {
            for (key in listOf("videoCodec", "receiverOrientation", "audioCodec", "audioSource")) {
                val priorLabel = field<Button>(dialog, key).text.toString()
                val canceled = openChoice(dialog, key)
                assertEquals(1, canceled.listView.checkedItemPosition)
                click(canceled, AlertDialog.BUTTON_NEGATIVE)
                assertFalse(canceled.isShowing)
                val backedOut = openChoice(dialog, key)
                assertEquals(1, backedOut.listView.checkedItemPosition)
                backedOut.onBackPressed()
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(backedOut.isShowing)
                assertTrue(dialog.isShowing)
                assertEquals(priorLabel, field<Button>(dialog, key).text.toString())
                val reopened = openChoice(dialog, key)
                assertEquals(1, reopened.listView.checkedItemPosition)
                click(reopened, AlertDialog.BUTTON_NEGATIVE)
            }
            assertEquals(original, current.request().toString())
            assertFalse(activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
                .contains(SharingSettings.REQUEST))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun openingAnotherChoiceClosesPreviousAndParentDismissClosesActiveChild() {
        val controller = activity()
        val activity = controller.get()
        val dialog = show(activity, Settings(), { fail("Dismiss saved settings") }, {})
        latestDialog()
        try {
            val first = openChoice(dialog, "videoCodec")
            val second = openChoice(dialog, "receiverOrientation")
            assertFalse(first.isShowing)
            assertTrue(second.isShowing)
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertFalse(second.isShowing)
            assertFalse(activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
                .contains(SharingSettings.REQUEST))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun saveValidatesPersistsAndPreservesCaptureOptionsWithAutomaticEncoders() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply {
            orientation = "@90"
            displayId = 3
            videoOptions = "max-bframes=0,i-frame-interval=2"
            audioOptions = "complexity=5"
            videoEncoder = "c2.vendor.encoder"
            audioEncoder = "c2.android.raw.encoder"
            audioSource = "output"
            cropWidth = 100
            cropHeight = 200
            cropX = 12
            cropY = 16
        }
        val original = current.request().toString()
        var saved: Settings? = null
        val shownDialog = show(activity, current, { saved = it }, {})
        val dialog = latestDialog()
        assertSame(shownDialog, dialog)
        try {
            choose(dialog, "maxSize", 0)
            choose(dialog, "maxFps", 8)
            choose(dialog, "videoBitrate", 11)
            choose(dialog, "videoCodec", 1)
            choose(dialog, "receiverOrientation", 2)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(dialog.isShowing)
            val result = checkNotNull(saved)
            assertEquals("h265", result.videoCodec)
            assertEquals(0, result.maxSize)
            assertEquals(90, result.maxFps)
            assertEquals(12_000_000, result.videoBitrate)
            assertEquals("portrait", result.receiverOrientation)
            assertEquals("@90", result.orientation)
            assertEquals(3, result.displayId)
            assertEquals(current.videoOptions, result.videoOptions)
            assertEquals(current.audioOptions, result.audioOptions)
            assertEquals("", result.audioEncoder)
            assertEquals("", result.videoEncoder)
            assertEquals("output", result.audioSource)
            assertEquals(100, result.cropWidth)
            assertEquals(200, result.cropHeight)
            assertEquals(12, result.cropX)
            assertEquals(16, result.cropY)
            assertEquals(original, current.request().toString())
            val loaded = SharingSettings.load(activity) { throw AssertionError(it) }
            assertEquals(result.request().toString(), loaded.request().toString())
            assertTrue(SharingSettings.summary(loaded).contains("12"))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun maximumCaptureFpsLabelAndSummaryDescribeTheDefaultAsUncapped() {
        val controller = activity()
        val activity = controller.get()
        val current = SharingSettings.load(activity) { throw it }
        val dialog = show(activity, current, {}, {})
        latestDialog()
        try {
            val fps = field<Button>(dialog, "maxFps")
            val body = fps.parent as LinearLayout
            val caption = (0 until body.childCount).map(body::getChildAt)
                .filterIsInstance<TextView>().single { it.labelFor == fps.id }
            assertEquals("Maximum capture FPS", caption.text.toString())
            assertEquals(activity.getString(R.string.rc_fps_uncapped), fps.text.toString())
            assertEquals(0, current.maxFps)
            assertEquals(1920, current.maxSize)
            assertEquals("raw", current.audioCodec)
            assertEquals(192_000, current.audioBitrate)
            assertTrue(SharingSettings.summary(current).contains("Uncapped FPS"))
            assertFalse(SharingSettings.summary(current).contains("0 fps"))
            assertTrue(SharingSettings.summary(current.apply { maxFps = 30 }).contains("30 fps max"))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun savedLegacyCapsAndRawAudioAreNotMigratedToNewDefaultsWhenEditing() {
        val controller = activity()
        val activity = controller.get()
        val legacy = Settings().apply { maxFps = 60; audioCodec = "raw"; audioBitrate = 192_000 }
        activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(SharingSettings.REQUEST, legacy.saved().toString()).commit()
        val loaded = SharingSettings.load(activity) { throw it }
        var saved: Settings? = null
        val dialog = show(activity, loaded, { saved = it }, {})
        latestDialog()
        try {
            assertEquals("60", field<Button>(dialog, "maxFps").text.toString())
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            val result = checkNotNull(saved)
            assertEquals(60, result.maxFps)
            assertEquals("raw", result.audioCodec)
            assertEquals(192_000, result.audioBitrate)
            assertEquals(legacy.saved().toString(), result.saved().toString())
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun fpsPickerIncludesUncappedAndPersistsEveryCommonCap() {
        val controller = activity()
        val activity = controller.get()
        val presets = listOf(0, 15, 24, 25, 30, 48, 50, 60, 90, 120)
        val labels = presets.map { if (it == 0) activity.getString(R.string.rc_fps_uncapped) else it.toString() }
        try {
            for ((position, expected) in presets.withIndex()) {
                val current = Settings()
                var saved: Settings? = null
                val dialog = show(activity, current, { saved = it }, {})
                latestDialog()
                assertEquals(Button::class.java, field<Button>(dialog, "maxFps").javaClass)
                assertEquals(activity.getString(R.string.rc_fps_uncapped), field<Button>(dialog, "maxFps").text.toString())
                val picker = openChoice(dialog, "maxFps")
                assertEquals(labels,
                    (0 until picker.listView.count).map { picker.listView.getItemAtPosition(it).toString() })
                assertEquals(0, picker.listView.checkedItemPosition)
                click(picker, AlertDialog.BUTTON_NEGATIVE)
                choose(dialog, "maxFps", position)
                assertEquals(labels[position], field<Button>(dialog, "maxFps").text.toString())
                assertEquals(0, current.maxFps)
                click(dialog, AlertDialog.BUTTON_POSITIVE)
                assertFalse(dialog.isShowing)
                assertEquals(expected, checkNotNull(saved).maxFps)
                assertEquals(expected, SharingSettings.load(activity) { throw it }.maxFps)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun fpsPickerPreservesSavedCustomValueAndCancelDiscardsDraft() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply { maxFps = 47 }
        val original = current.saved().toString()
        val preferences = activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().putString(SharingSettings.REQUEST, original).commit()
        try {
            val dialog = show(activity, SharingSettings.load(activity) { throw it }, { fail("Cancel saved settings") }, {})
            latestDialog()
            assertEquals("47", field<Button>(dialog, "maxFps").text.toString())
            val picker = openChoice(dialog, "maxFps")
            assertEquals(listOf(activity.getString(R.string.rc_fps_uncapped), "15", "24", "25", "30", "47", "48", "50", "60", "90", "120"),
                (0 until picker.listView.count).map { picker.listView.getItemAtPosition(it).toString() })
            assertEquals(5, picker.listView.checkedItemPosition)
            click(picker, AlertDialog.BUTTON_NEGATIVE)
            assertEquals("47", field<Button>(dialog, "maxFps").text.toString())
            choose(dialog, "maxFps", 10)
            click(dialog, AlertDialog.BUTTON_NEGATIVE)
            assertEquals(original, preferences.getString(SharingSettings.REQUEST, null))
            assertEquals(47, current.maxFps)
            var saved: Settings? = null
            val reopened = show(activity, SharingSettings.load(activity) { throw it }, { saved = it }, {})
            latestDialog()
            click(reopened, AlertDialog.BUTTON_POSITIVE)
            assertEquals(47, checkNotNull(saved).maxFps)
            assertEquals(47, SharingSettings.load(activity) { throw it }.maxFps)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun invalidSettingsStayOpenWithoutWritingOrCallingSaveAndCanBeCorrected() {
        val controller = activity()
        val activity = controller.get()
        var saves = 0
        show(activity, Settings(), { saves++ }, {})
        val dialog = latestDialog()
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            field<CheckBox>(dialog, "fullscreen").isChecked = false
            field<EditText>(dialog, "cropWidth").setText("-1")
            field<EditText>(dialog, "cropHeight").setText("100")
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertTrue(dialog.isShowing)
            assertEquals(0, saves)
            assertNotNull(field<EditText>(dialog, "cropWidth").error)
            assertEquals(View.GONE, field<TextView>(dialog, "settingsError").visibility)
            assertFalse(activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).contains(SharingSettings.REQUEST))
            field<CheckBox>(dialog, "fullscreen").isChecked = true
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(dialog.isShowing)
            assertEquals(1, saves)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun cancelDoesNotMutateOrPersistAndNoticesAreReachableWithoutSaving() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings()
        var notices = 0
        show(activity, current, { fail("Cancel saved settings") }, { notices++ })
        val dialog = latestDialog()
        try {
            choose(dialog, "maxSize", 0)
            choose(dialog, "videoCodec", 1)
            choose(dialog, "receiverOrientation", 2)
            choose(dialog, "audioCodec", 1)
            choose(dialog, "audioSource", 1)
            field<Button>(dialog, "notices").performClick()
            assertEquals(1, notices)
            assertTrue(dialog.isShowing)
            click(dialog, AlertDialog.BUTTON_NEGATIVE)
            assertFalse(dialog.isShowing)
            assertEquals(1920, current.maxSize)
            assertEquals("h264", current.videoCodec)
            assertEquals("raw", current.audioCodec)
            assertEquals("auto", current.receiverOrientation)
            assertEquals("playback", current.audioSource)
            assertFalse(activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).contains(SharingSettings.REQUEST))
            val reopened = show(activity, current, { fail("Reopening saved settings") }, {})
            latestDialog()
            try {
                for (key in listOf("videoCodec", "receiverOrientation", "audioCodec", "audioSource")) {
                    val picker = openChoice(reopened, key)
                    assertEquals(0, picker.listView.checkedItemPosition)
                    click(picker, AlertDialog.BUTTON_NEGATIVE)
                }
            } finally {
                reopened.dismiss()
            }
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }

    }

    @Test fun failedCommitIsVisibleAndDialogRemainsOpenForRetry() {
        val controller = Robolectric.buildActivity(FailedWriteActivity::class.java).setup()
        val activity = controller.get()
        val preferences = activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        activity.failWrites = true
        var saves = 0
        show(activity, Settings(), { saves++ }, {})
        val dialog = latestDialog()
        try {
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertTrue(dialog.isShowing)
            assertEquals(0, saves)
            assertFalse(preferences.contains(SharingSettings.REQUEST))
            assertTrue(field<TextView>(dialog, "settingsError").text.contains(activity.getString(R.string.rc_settings_write_error)))
            activity.failWrites = false
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(dialog.isShowing)
            assertEquals(1, saves)
            assertTrue(preferences.contains(SharingSettings.REQUEST))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun advancedCropAndAudioRoundTripWithoutManualEncoderFieldsOrHardwareConfiguration() {
        val controller = activity()
        val activity = controller.get()
        var saved: Settings? = null
        show(activity, Settings(), { saved = it }, {})
        val dialog = latestDialog()
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            field<CheckBox>(dialog, "fullscreen").isChecked = false
            field<EditText>(dialog, "cropWidth").setText("80")
            field<EditText>(dialog, "cropHeight").setText("60")
            field<EditText>(dialog, "cropX").setText("10")
            field<EditText>(dialog, "cropY").setText("20")
            assertNull(dialog.window!!.decorView.findViewWithTag<View>("videoEncoder"))
            choose(dialog, "audioCodec", 1)
            choose(dialog, "audioSource", 1)
            field<EditText>(dialog, "audioBitrate").setText("128")
            assertNull(dialog.window!!.decorView.findViewWithTag<View>("audioEncoder"))
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(dialog.isShowing)
            val result = checkNotNull(saved)
            assertEquals(80, result.cropWidth)
            assertEquals(60, result.cropHeight)
            assertEquals(10, result.cropX)
            assertEquals(20, result.cropY)
            assertEquals("", result.videoEncoder)
            assertEquals("opus", result.audioCodec)
            assertEquals("mic", result.audioSource)
            assertEquals(128_000, result.audioBitrate)
            assertEquals("", result.audioEncoder)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun fullscreenPreservesCropAndRawBitrateAcrossSaveAndReopen() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply {
            cropWidth = 100
            cropHeight = 100
            cropX = 10
            cropY = 20
            audioBitrate = 123456
        }
        var saved: Settings? = null
        show(activity, current, { saved = it }, {})
        val dialog = latestDialog()
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            assertNull(dialog.window!!.decorView.findViewWithTag<View>("clearCrop"))
            val fullscreen = field<CheckBox>(dialog, "fullscreen")
            field<EditText>(dialog, "cropWidth").setText("120")
            fullscreen.isChecked = true
            fullscreen.isChecked = false
            assertEquals("120", field<EditText>(dialog, "cropWidth").text.toString())
            fullscreen.isChecked = true
            assertFalse(field<EditText>(dialog, "audioBitrate").isEnabled)
            assertEquals(View.GONE, field<View>(dialog, "audioBitrate").visibility)
            assertEquals(View.GONE, field<View>(dialog, "audioBitrateLabel").visibility)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            val result = checkNotNull(saved)
            assertEquals(listOf(120, 100, 10, 20), listOf(result.cropWidth, result.cropHeight, result.cropX, result.cropY))
            assertTrue(result.request().getJSONObject("video").isNull("crop"))
            assertEquals(123456, result.audioBitrate)
            val reopened = show(activity, SharingSettings.load(activity) { throw it }, {}, {})
            field<Button>(reopened, "advancedToggle").performClick()
            assertTrue(field<CheckBox>(reopened, "fullscreen").isChecked)
            field<CheckBox>(reopened, "fullscreen").isChecked = false
            assertEquals("120", field<EditText>(reopened, "cropWidth").text.toString())
            click(reopened, AlertDialog.BUTTON_POSITIVE)
            val restored = SharingSettings.load(activity) { throw it }
            assertFalse(restored.fullscreen)
            assertEquals(120, restored.request().getJSONObject("video").getJSONObject("crop").getInt("width"))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun audioChoiceDialogsPreserveScrollAndDoNotSendFocusToATextInput() {
        val controller = activity()
        show(controller.get(), Settings(), {}, {})
        val dialog = latestDialog()
        try {
            field<Button>(dialog, "advancedToggle").performClick()
            val bitrate = field<EditText>(dialog, "audioBitrate")
            val label = field<TextView>(dialog, "audioBitrateLabel")
            val body = field<TextView>(dialog, "settingsError").parent as LinearLayout
            val scroll = body.parent as ScrollView
            scroll.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 400, 500)
            scroll.scrollTo(0, 200)
            assertEquals(View.GONE, bitrate.visibility)
            assertEquals(View.GONE, label.visibility)
            choose(dialog, "audioCodec", 1)
            assertEquals(controller.get().getString(R.string.rc_audio_opus), field<Button>(dialog, "audioCodec").text)
            assertFalse(dialog.currentFocus is EditText)
            assertEquals(200, scroll.scrollY)
            assertEquals(View.VISIBLE, bitrate.visibility)
            assertEquals(View.VISIBLE, label.visibility)
            choose(dialog, "audioCodec", 2)
            assertFalse(dialog.currentFocus is EditText)
            bitrate.requestFocus()
            assertTrue(bitrate.hasFocus())
            val previousScroll = scroll.scrollY
            choose(dialog, "audioCodec", 0)
            assertFalse(dialog.currentFocus is EditText)
            assertEquals(previousScroll, scroll.scrollY)
            assertEquals(View.GONE, bitrate.visibility)
            assertEquals(View.GONE, label.visibility)
            choose(dialog, "audioSource", 1)
            assertFalse(dialog.currentFocus is EditText)
            assertEquals(previousScroll, scroll.scrollY)
            val picker = openChoice(dialog, "audioSource")
            click(picker, AlertDialog.BUTTON_NEGATIVE)
            assertFalse(dialog.currentFocus is EditText)
            assertEquals(previousScroll, scroll.scrollY)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun unavailableSavedAv1IsNotAnOptionOrSilentlyReplacedAndCannotBeSaved() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply { videoCodec = "av1" }
        var saved: Settings? = null
        show(activity, current, { saved = it }, {}, inventory(av1Hardware = false))
        val dialog = latestDialog()
        try {
            val codec = field<Button>(dialog, "videoCodec")
            assertEquals(activity.getString(R.string.rc_select_hardware_codec), codec.text)
            val picker = openChoice(dialog, "videoCodec")
            assertEquals(2, picker.listView.count)
            assertFalse((0 until picker.listView.count).any { picker.listView.getItemAtPosition(it).toString() == "AV1" })
            assertEquals(-1, picker.listView.checkedItemPosition)
            click(picker, AlertDialog.BUTTON_NEGATIVE)
            assertEquals(View.VISIBLE, field<TextView>(dialog, "settingsError").visibility)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertTrue(dialog.isShowing)
            assertNull(saved)
            assertEquals("av1", current.videoCodec)
            assertFalse(activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).contains(SharingSettings.REQUEST))
            choose(dialog, "videoCodec", 0)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertEquals("h264", checkNotNull(saved).videoCodec)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun realHardwareAv1RemainsSelectableAndNoHardwareVideoDisablesSaving() {
        val controller = activity()
        val activity = controller.get()
        var saved: Settings? = null
        show(activity, Settings(), { saved = it }, {}, inventory())
        val supported = latestDialog()
        try {
            val picker = openChoice(supported, "videoCodec")
            assertEquals("AV1", picker.listView.getItemAtPosition(2))
            click(picker, AlertDialog.BUTTON_NEGATIVE)
            choose(supported, "videoCodec", 2)
            click(supported, AlertDialog.BUTTON_POSITIVE)
            assertEquals("av1", checkNotNull(saved).videoCodec)
            val unavailable = HardwareEncoders(listOf(
                EncoderMetadata("software", "software", listOf("video/avc"), true, false, true)))
            show(activity, Settings(), { fail("No hardware encoder must not save") }, {}, unavailable)
            val blocked = latestDialog()
            try {
                assertFalse(blocked.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertFalse(field<Button>(blocked, "videoCodec").isEnabled)
                assertTrue(field<TextView>(blocked, "settingsError").text.contains("No eligible hardware"))
            } finally { blocked.dismiss() }
        } finally {
            supported.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun validationErrorIsFirstAndReturnsToTopWithoutChangingSettings() {
        val controller = activity()
        show(controller.get(), Settings(), {}, {})
        val dialog = latestDialog()
        try {
            val error = field<TextView>(dialog, "settingsError")
            val body = error.parent as LinearLayout
            assertSame(error, body.getChildAt(0))
            val scroll = body.parent as ScrollView
            scroll.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 400, 500)
            scroll.scrollTo(0, 200)
            assertTrue(scroll.scrollY > 0)
            field<Button>(dialog, "advancedToggle").performClick()
            field<CheckBox>(dialog, "fullscreen").isChecked = false
            field<EditText>(dialog, "cropWidth").setText("-1")
            field<EditText>(dialog, "cropHeight").setText("100")
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertNotNull(field<EditText>(dialog, "cropWidth").error)
            assertEquals(View.GONE, error.visibility)
            assertTrue(scroll.scrollY > 0)
            assertTrue(dialog.isShowing)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun persistedManualEncoderNamesAreRetiredWithoutChangingTheSelectedCodecsOrQuality() {
        val context = RuntimeEnvironment.getApplication()
        val stored = Settings().apply {
            videoEncoder = "retired.video.encoder"
            audioEncoder = "retired.audio.encoder"
            videoCodec = "h265"
            audioCodec = "opus"
            videoBitrate = 12_000_000
            audioBitrate = 128_000
        }
        context.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(SharingSettings.REQUEST, stored.request().toString()).commit()
        val loaded = SharingSettings.load(context) { fail(it.message) }
        assertEquals("", loaded.videoEncoder)
        assertEquals("", loaded.audioEncoder)
        assertEquals("h265", loaded.videoCodec)
        assertEquals("opus", loaded.audioCodec)
        assertEquals(12_000_000, loaded.videoBitrate)
        assertEquals(128_000, loaded.audioBitrate)
    }

    @Test fun invalidPersistedDataWarnsExplicitlyAndOnlyThenReturnsDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
        for (invalid in listOf("{broken", Settings().apply { maxFps = -1 }.request().toString())) {
            preferences.edit().putString(SharingSettings.REQUEST, invalid).commit()
            val errors = mutableListOf<Exception>()
            val result = SharingSettings.load(context, errors::add)
            assertEquals(1, errors.size)
            assertNotNull(errors.single().cause)
            assertEquals(context.getString(R.string.rc_settings_read_error), errors.single().message)
            assertEquals(0, result.maxFps)
            assertEquals(invalid, preferences.getString(SharingSettings.REQUEST, null))
        }
        preferences.edit().clear().commit()
        val result = SharingSettings.load(context) { fail("New installation must not warn") }
        assertEquals(0, result.maxFps)
    }

    @Test fun unreadablePersistedSettingsBlockCastingUntilExplicitReplacement() {
        val controller = activity()
        val context = controller.get()
        val preferences = context.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE)
        val corrupt = "{broken"
        preferences.edit().putString(SharingSettings.REQUEST, corrupt).commit()
        val current = SharingSettings.load(context) {}
        assertThrows(IllegalArgumentException::class.java) { current.validate() }
        var saved: Settings? = null
        val dialog = show(context, current, { saved = it }, {})
        try {
            assertTrue(field<TextView>(dialog, "settingsError").isShown)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertNull(saved)
            assertTrue(dialog.isShowing)
            val confirmation = latestDialog()
            click(confirmation, AlertDialog.BUTTON_POSITIVE)
            assertNotNull(saved)
            assertFalse(dialog.isShowing)
            assertNotEquals(corrupt, preferences.getString(SharingSettings.REQUEST, null))
        } finally {
            preferences.edit().clear().commit()
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
