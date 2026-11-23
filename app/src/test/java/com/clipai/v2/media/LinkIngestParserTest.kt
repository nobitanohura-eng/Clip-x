package com.clipai.v2.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class LinkIngestParserTest {

    @Test
    fun parsesYouTubeWatchLiveAndShorts() {
        val watch = LinkUrlParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ").getOrThrow()
        assertEquals(LinkSourceKind.YOUTUBE_VIDEO, watch.kind)
        assertEquals("dQw4w9WgXcQ", watch.youtubeVideoId)

        val live = LinkUrlParser.parse("https://youtube.com/live/jfKfPfyJRdk?feature=share").getOrThrow()
        assertEquals(LinkSourceKind.YOUTUBE_LIVE, live.kind)
        assertEquals("jfKfPfyJRdk", live.youtubeVideoId)

        val shortUrl = LinkUrlParser.parse("https://youtu.be/dQw4w9WgXcQ").getOrThrow()
        assertEquals(LinkSourceKind.YOUTUBE_VIDEO, shortUrl.kind)
        assertEquals("dQw4w9WgXcQ", shortUrl.youtubeVideoId)
    }

    @Test
    fun parsesHlsAndDirectVideoLinks() {
        val hls = LinkUrlParser.parse("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8").getOrThrow()
        assertEquals(LinkSourceKind.HLS_STREAM, hls.kind)

        val mp4 = LinkUrlParser.parse("https://cdn.example.com/clips/podcast.mp4").getOrThrow()
        assertEquals(LinkSourceKind.DIRECT_VIDEO, mp4.kind)
    }

    @Test
    fun rejectsUnsafeSchemesAndPrivateHosts() {
        assertTrue(LinkUrlParser.parse("javascript:alert(1)").isFailure)
        assertTrue(LinkUrlParser.parse("http://localhost:3000/video.mp4").isFailure)
        assertTrue(LinkUrlParser.isPrivateOrBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(LinkUrlParser.isPrivateOrBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(LinkUrlParser.isPrivateOrBlockedAddress(InetAddress.getByName("10.0.0.5")))
        assertTrue(LinkUrlParser.isPrivateOrBlockedAddress(InetAddress.getByName("192.168.1.10")))
    }
}
