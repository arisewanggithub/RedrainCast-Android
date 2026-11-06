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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SharingSettingsTest {
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
            assertEquals("landscape", result.receiverOrientation)
            assertEquals("opus", result.audioCodec)
            assertEquals("playback", result.audioSource)
            assertEquals(result.request().toString(), preferences.getString(SharingSettings.REQUEST, null))
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
            field<EditText>(dialog, "maxSize").setText("0")
            field<EditText>(dialog, "maxFps").setText("90")
            field<EditText>(dialog, "videoBitrate").setText("12.5")
            choose(dialog, "videoCodec", 1)
            choose(dialog, "receiverOrientation", 2)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertFalse(dialog.isShowing)
            val result = checkNotNull(saved)
            assertEquals("h265", result.videoCodec)
            assertEquals(0, result.maxSize)
            assertEquals(90, result.maxFps)
            assertEquals(12_500_000, result.videoBitrate)
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
            assertTrue(SharingSettings.summary(loaded).contains("12.5"))
        } finally {
            dialog.dismiss()
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
            field<EditText>(dialog, "maxFps").setText("0")
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertTrue(dialog.isShowing)
            assertEquals(0, saves)
            assertEquals(View.VISIBLE, field<TextView>(dialog, "settingsError").visibility)
            assertFalse(activity.getSharedPreferences(SharingSettings.PREFERENCES, Context.MODE_PRIVATE).contains(SharingSettings.REQUEST))
            field<EditText>(dialog, "maxFps").setText("60")
            field<EditText>(dialog, "videoBitrate").setText("not a number")
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertTrue(dialog.isShowing)
            assertNotNull(field<EditText>(dialog, "videoBitrate").error)
            field<EditText>(dialog, "videoBitrate").setText("8")
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
            field<EditText>(dialog, "maxSize").setText("720")
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
            field<CheckBox>(dialog, "clearCrop").isChecked = false
            field<EditText>(dialog, "cropWidth").setText("800")
            field<EditText>(dialog, "cropHeight").setText("600")
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
            assertEquals(800, result.cropWidth)
            assertEquals(600, result.cropHeight)
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

    @Test fun clearCropExplicitlyClearsAllFourCoordinatesAndRawBitrateIsPreserved() {
        val controller = activity()
        val activity = controller.get()
        val current = Settings().apply {
            cropWidth = 400
            cropHeight = 300
            cropX = 10
            cropY = 20
            audioBitrate = 123456
        }
        var saved: Settings? = null
        show(activity, current, { saved = it }, {})
        val dialog = latestDialog()
        try {
            field<CheckBox>(dialog, "clearCrop").isChecked = true
            assertFalse(field<EditText>(dialog, "cropWidth").isEnabled)
            assertFalse(field<EditText>(dialog, "audioBitrate").isEnabled)
            assertEquals(View.GONE, field<View>(dialog, "audioBitrate").visibility)
            assertEquals(View.GONE, field<View>(dialog, "audioBitrateLabel").visibility)
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            val result = checkNotNull(saved)
            assertEquals(listOf(0, 0, 0, 0), listOf(result.cropWidth, result.cropHeight, result.cropX, result.cropY))
            assertEquals(123456, result.audioBitrate)
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
            field<EditText>(dialog, "maxFps").setText("0")
            click(dialog, AlertDialog.BUTTON_POSITIVE)
            assertEquals(View.VISIBLE, error.visibility)
            assertEquals(0, scroll.scrollY)
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
        for (invalid in listOf("{broken", Settings().apply { maxFps = 0 }.request().toString())) {
            preferences.edit().putString(SharingSettings.REQUEST, invalid).commit()
            val errors = mutableListOf<Exception>()
            val result = SharingSettings.load(context, errors::add)
            assertEquals(1, errors.size)
            assertNotNull(errors.single().cause)
            assertEquals(context.getString(R.string.rc_settings_read_error), errors.single().message)
            assertEquals(60, result.maxFps)
            assertEquals(invalid, preferences.getString(SharingSettings.REQUEST, null))
        }
        preferences.edit().clear().commit()
        val result = SharingSettings.load(context) { fail("New installation must not warn") }
        assertEquals(60, result.maxFps)
    }
}
