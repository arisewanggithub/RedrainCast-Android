package dev.screencopy.sender

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ProjectionCaptureAudioOnlyTest {
    @Test fun audioOnlyStartupCreatesNoVideoEncoderRendererDisplayListenerOrWorker() {
        val context = Mockito.mock(Context::class.java)
        val owner = Mockito.mock(ProjectionOwner::class.java)
        val sink = Mockito.mock(ScrcpyAdapter.Sink::class.java)
        var audioStarted = false
        val capture = ProjectionCapture(context, owner, { throw AssertionError(it) },
            audioStartForTests = { settings, session, actualSink ->
                assertFalse(settings.video)
                assertEquals(51L, session)
                assertSame(sink, actualSink)
                audioStarted = true
            })
        try {
            capture.start(Settings().apply { video = false; audio = true }, 51, sink)
            assertTrue(audioStarted)
            fun value(name: String): Any? = ProjectionCapture::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(capture)
            assertNull(value("video"))
            assertNull(value("renderer"))
            assertNull(value("input"))
            assertNull(value("displayListener"))
            assertTrue((value("workers") as List<*>).isEmpty())
        } finally {
            capture.close()
        }
    }
}
