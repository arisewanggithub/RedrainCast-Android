package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class VideoPathStatisticsTest {
    private val hdr = VideoPathStatistics(
        DecodedVideoStatistics(VideoStreamFormat.Hdr10, VideoTransfer.Pq, "Main 10", 10),
        VideoPresentationStatistics(VideoPresentationOutput.Hdr))
    private val sdr = VideoPathStatistics(
        DecodedVideoStatistics(VideoStreamFormat.Sdr, VideoTransfer.Bt709, "Main", 8),
        VideoPresentationStatistics(VideoPresentationOutput.Sdr))

    @Test fun decodeAndScheduledOutputAreNotPresentationAndCallbacksUseTheSubmittedFormat() {
        val cache = VideoPathStatisticsCache()
        cache.decoded(hdr)
        assertEquals(hdr.video, cache.snapshot().video)
        assertNull(cache.snapshot().presentation)
        cache.scheduled(100, hdr)
        cache.decoded(sdr)
        cache.scheduled(200, sdr)
        assertNull(cache.snapshot().presentation)
        cache.rendered(100, 1000)
        assertEquals(hdr.presentation, cache.snapshot().presentation)
        assertEquals(sdr.video, cache.snapshot().video)
        cache.rendered(200, 2000)
        assertEquals(sdr.presentation, cache.snapshot().presentation)
        cache.rendered(100, 3000)
        assertEquals(sdr.presentation, cache.snapshot().presentation)
    }

    @Test fun missingOutputEvidenceClearsOldClassificationOnlyWhenSuccessfullyRendered() {
        val cache = VideoPathStatisticsCache()
        cache.scheduled(1, hdr)
        cache.rendered(1, 1)
        cache.scheduled(2, VideoPathStatistics(sdr.video, null))
        assertEquals(hdr.presentation, cache.snapshot().presentation)
        cache.rendered(2, 2)
        assertNull(cache.snapshot().presentation)
    }

    @Test fun staleUnmatchedEvictedAndRetiredCallbacksCannotPublishOutput() {
        val cache = VideoPathStatisticsCache()
        cache.scheduled(1, hdr)
        cache.scheduled(2, sdr)
        cache.rendered(2, 200)
        cache.rendered(1, 100)
        assertEquals(sdr.presentation, cache.snapshot().presentation)
        repeat(65) { cache.scheduled(100L + it, hdr) }
        cache.rendered(100, 300)
        assertNull(cache.snapshot().presentation)
        cache.rendered(999, 400)
        assertNull(cache.snapshot().presentation)
        cache.close()
        cache.rendered(164, 500)
        cache.decoded(hdr)
        cache.scheduled(1000, hdr)
        cache.rendered(1000, 600)
        assertEquals(VideoPathStatistics(null, null), cache.snapshot())
        assertEquals(VideoPathStatistics(null, null), VideoPathStatisticsCache().snapshot())
    }

    @Test fun duplicatePtsCannotAttributeAnOldCallbackToANewerFormat() {
        val cache = VideoPathStatisticsCache()
        cache.scheduled(1, hdr)
        cache.scheduled(1, sdr)
        cache.rendered(1, 100)
        assertNull(cache.snapshot().presentation)
        cache.rendered(1, 200)
        assertNull(cache.snapshot().presentation)
        cache.scheduled(2, hdr)
        cache.rendered(2, 300)
        assertEquals(hdr.presentation, cache.snapshot().presentation)
        cache.scheduled(2, sdr)
        cache.rendered(2, 400)
        assertNull(cache.snapshot().presentation)
    }

    @Test fun formatterSeparatesUnknownComponentsAndIncomingRangeFromOutput() {
        assertEquals("HDR10 · PQ", PlaybackStatisticsText.streamRange(hdr.video))
        assertEquals("SDR · BT.709", PlaybackStatisticsText.streamRange(sdr.video))
        assertEquals("Main 10 · 10-bit", PlaybackStatisticsText.decodeFormat(hdr.video))
        assertEquals("Main · 8-bit", PlaybackStatisticsText.decodeFormat(sdr.video))
        assertEquals("HDR output", PlaybackStatisticsText.presentationOutput(hdr.presentation))
        assertEquals("SDR output", PlaybackStatisticsText.presentationOutput(sdr.presentation))
        assertEquals("SDR (tone-mapped)", PlaybackStatisticsText.presentationOutput(
            VideoPresentationStatistics(VideoPresentationOutput.SdrToneMapped)))
        assertEquals("N/A", PlaybackStatisticsText.presentationOutput(null))
        assertEquals("N/A", PlaybackStatisticsText.streamRange(null))
        assertEquals("N/A", PlaybackStatisticsText.decodeFormat(null))
        assertEquals("HDR10", PlaybackStatisticsText.streamRange(hdr.video!!.copy(transfer = null)))
        assertEquals("N/A · 10-bit", PlaybackStatisticsText.decodeFormat(hdr.video.copy(codecProfile = null)))
        assertEquals("Main 10 · N/A", PlaybackStatisticsText.decodeFormat(hdr.video.copy(bitDepth = null)))
        assertEquals("Main 10 · depth=10", PlaybackStatisticsText.decodeFormat(hdr.video) { "depth=$it" })
    }

    @Test fun samplerCarriesCachesWithoutInventingValuesAcrossGenerations() {
        val input = PlaybackStatisticsInput(1, 1, 1, "Playing", "h265", null, 640, 360,
            60, 8000000, null, 0, "auto", 0, 0, 0, 0, null, null, null, null, null, 0,
            decodedVideo = hdr.video, presentation = hdr.presentation)
        val sampler = PlaybackStatisticsSampler()
        val first = sampler.sample(1000, input)
        assertSame(hdr.video, first.input.decodedVideo)
        assertSame(hdr.presentation, first.input.presentation)
        repeat(100) {
            val model = PlaybackStatisticsText.display(first, 640, 360)
            assertEquals(listOf("videoCodec", "videoDynamicRange", "videoDecodeFormat", "presentationOutput"),
                model.video.take(4).map { it.id })
            assertEquals(listOf("Codec", "Stream range", "Profile / depth", "Output"),
                model.video.take(4).map { it.label })
        }
        val next = sampler.sample(2000, input.copy(generation = 2, decodedVideo = null, presentation = null))
        assertTrue(PlaybackStatisticsText.display(next, 640, 360).video.drop(1).take(3).all { it.value == "N/A" })
    }

    @Test fun sharedWindowsV2FixtureAndAndroidNewFieldsHaveExactIdsLabelsAndNoNumericUnits() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/$name")!!.use {
            org.screencopy.receiver.protocol.StrictJson.decode(it.readBytes())
        }
        val shared = fixture("playback-statistics-display-contract.json")
        val android = fixture("playback-statistics-display.json")
        assertEquals(2, shared.getInt("displayContractVersion"))
        assertFalse(shared.getBoolean("wireContractChanged"))
        val groups = shared.getJSONArray("groups")
        val video = groups.getJSONObject(0)
        assertEquals("video", video.getString("id"))
        assertEquals("VIDEO STREAM", video.getString("title"))
        val fields = video.getJSONArray("fields")
        for (index in 0..3) {
            val common = fields.getJSONObject(index)
            val local = android.getJSONArray("video").getJSONObject(index)
            assertEquals(common.getString("id"), local.getString("id"))
            assertEquals(common.getString("label"), local.getString("label"))
            if (index > 0) {
                assertFalse(common.has("unit"))
                assertFalse(local.has("unit"))
            }
        }
    }
}
