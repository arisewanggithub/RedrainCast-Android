package org.screencopy.receiver.media

import android.media.MediaFormat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito

class VideoRasterTest {
    private fun format(vararg fields: Pair<String, Int>): MediaFormat {
        val values = fields.toMap()
        return Mockito.mock(MediaFormat::class.java).also { format ->
            Mockito.`when`(format.containsKey(Mockito.anyString())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
            Mockito.`when`(format.getInteger(Mockito.anyString())).thenAnswer { checkNotNull(values[it.getArgument<String>(0)]) }
        }
    }

    @Test fun actualOutputMustMatchDeclaredRasterIncludingCodecAlignmentCrop() {
        AndroidVideoDecoder.validateRaster(format("width" to 888, "height" to 1920), 888, 1920)
        AndroidVideoDecoder.validateRaster(format("width" to 896, "height" to 1920,
            "crop-left" to 0, "crop-right" to 887), 888, 1920)
        assertThrows(PlaybackException::class.java) {
            AndroidVideoDecoder.validateRaster(format("width" to 1920, "height" to 888), 888, 1920)
        }
        for (actual in listOf(format("width" to 896, "height" to 1920, "crop-left" to 0),
            format("width" to 896, "height" to 1920, "crop-left" to 0, "crop-right" to 896),
            format("width" to 0, "height" to 1920), format("width" to 888))) {
            assertThrows(PlaybackException::class.java) { AndroidVideoDecoder.validateRaster(actual, 888, 1920) }
        }
    }
}
