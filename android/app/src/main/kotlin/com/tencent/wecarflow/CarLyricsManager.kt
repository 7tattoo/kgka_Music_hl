package com.tencent.wecarflow

import android.os.Bundle
import android.util.Log

object CarLyricsManager {
    private const val TAG = "CarLyricsManager"

    const val METADATA_KEY_LYRICS_LINE = "ucar.media.metadata.LYRICS_LINE"
    const val METADATA_KEY_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE"
    const val METADATA_KEY_LYRICS_STATUS = "ucar.media.metadata.LYRICS_STATUS"
    const val METADATA_KEY_TITLE = "android.media.metadata.TITLE"
    const val METADATA_KEY_ARTIST = "android.media.metadata.ARTIST"
    const val METADATA_KEY_UCAR_TITLE = "UCAR_TITLE"
    const val METADATA_KEY_UCAR_ARTIST = "UCAR_ARTIST"

    const val EXTRAS_KEY_LYRIC = "music.media.extras.LYRIC"
    const val EXTRAS_KEY_LYRIC_ALLOWED = "music.media.extras.LYRIC_IS_ALLOWED"
    const val EXTRAS_KEY_NOTICE_CAR = "music.media.extras.NOTICE_CAR"

    const val VIVO_ACTION_KEY = "vivomusicmix.meida.extra.key.action"
    const val VIVO_ACTION_LRC_CHANGE = "vivomusicmix.extra.lrc_change"
    const val VIVO_MEDIA_ID_KEY = "vivomusicmix.extra.key.meidia_id"
    const val VIVO_LYRIC_KEY = "vivomusicmix.extra.key.lyric"

    const val LYRICS_STATUS_SUCCESS = 0L
    const val LYRICS_STATUS_NO_LYRICS = 1L
    const val LYRICS_STATUS_LOADING = 2L

    private var cachedTitle: String = ""
    private var cachedArtist: String = ""
    private var lastMediaId: String = ""

    private fun getSession(): Any? {
        return try {
            val audioServiceClass = Class.forName("com.ryanheise.audioservice.AudioService")
            val instanceField = audioServiceClass.getDeclaredField("instance")
            instanceField.isAccessible = true
            val instance = instanceField.get(null) ?: return null
            val sessionField = audioServiceClass.getDeclaredField("mediaSession")
            sessionField.isAccessible = true
            sessionField.get(instance)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get MediaSession: ${e.message}")
            null
        }
    }

    private fun getMetadataBuilderClass(): Class<*>? {
        // audio_service 使用 android.support.v4.media.MediaMetadataCompat（Google 在 AndroidX 下保留该包名）
        for (name in listOf(
            "android.support.v4.media.MediaMetadataCompat\$Builder",
            "androidx.media.common.MediaMetadataCompat\$Builder"
        )) {
            try {
                return Class.forName(name)
            } catch (_: Exception) {}
        }
        Log.w(TAG, "MediaMetadataCompat.Builder not found")
        return null
    }

    fun updateLyrics(
        currentLine: String,
        wholeLrc: String,
        hasLyrics: Boolean,
        title: String = "",
        artist: String = "",
        mediaId: String = ""
    ) {
        val session = getSession()
        if (session == null) {
            Log.w(TAG, "MediaSession is null, cannot update lyrics")
            return
        }
        if (title.isNotEmpty()) cachedTitle = title
        if (artist.isNotEmpty()) cachedArtist = artist
        if (mediaId.isNotEmpty()) lastMediaId = mediaId

        // ---- Channel A: Metadata ----
        try {
            val builderClass = getMetadataBuilderClass()
            if (builderClass != null) {
                val builder = builderClass.getDeclaredConstructor().newInstance()
                val putString = builderClass.getMethod("putString", String::class.java, String::class.java)
                val putLong = builderClass.getMethod("putLong", String::class.java, Long::class.java)

                // 关键：TITLE 用于腾讯爱趣听的单行歌词读取；空歌词时恢复歌名
                val titleValue = if (hasLyrics && currentLine.isNotEmpty()) currentLine else cachedTitle
                putString.invoke(builder, METADATA_KEY_TITLE, titleValue)
                putString.invoke(builder, METADATA_KEY_ARTIST, cachedArtist)
                putString.invoke(builder, METADATA_KEY_UCAR_TITLE, cachedTitle)
                putString.invoke(builder, METADATA_KEY_UCAR_ARTIST, cachedArtist)

                putString.invoke(builder, METADATA_KEY_LYRICS_LINE, currentLine.ifEmpty { "" })
                if (hasLyrics && wholeLrc.isNotEmpty()) {
                    putString.invoke(builder, METADATA_KEY_LYRICS_WHOLE, wholeLrc)
                    putLong.invoke(builder, METADATA_KEY_LYRICS_STATUS, LYRICS_STATUS_SUCCESS)
                } else {
                    putString.invoke(builder, METADATA_KEY_LYRICS_WHOLE, "-1")
                    putLong.invoke(builder, METADATA_KEY_LYRICS_STATUS, LYRICS_STATUS_NO_LYRICS)
                }

                val metadata = builderClass.getMethod("build").invoke(builder)
                val setMetadata = session.javaClass.getMethod("setMetadata", metadata.javaClass)
                setMetadata.invoke(session, metadata)
                Log.d(TAG, "metadata updated: title=${titleValue}, line=${currentLine}, hasLyrics=$hasLyrics")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update metadata: ${e.message}", e)
        }

        // ---- Channel B: Extras ----
        try {
            val extras = Bundle().apply {
                putBoolean(EXTRAS_KEY_LYRIC_ALLOWED, true)
                if (currentLine.isNotEmpty()) {
                    putString(EXTRAS_KEY_LYRIC, currentLine)
                }
                putBoolean(EXTRAS_KEY_NOTICE_CAR, true)
                putString(VIVO_ACTION_KEY, VIVO_ACTION_LRC_CHANGE)
                putString(VIVO_MEDIA_ID_KEY, lastMediaId)
                if (hasLyrics && wholeLrc.isNotEmpty()) {
                    putString(VIVO_LYRIC_KEY, wholeLrc)
                }
            }
            val setExtras = session.javaClass.getMethod("setExtras", Bundle::class.java)
            setExtras.invoke(session, extras)
            Log.d(TAG, "extras updated: line=${currentLine}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update extras: ${e.message}", e)
        }
    }

    fun clearLyrics() {
        updateLyrics("", "", false)
    }
}
