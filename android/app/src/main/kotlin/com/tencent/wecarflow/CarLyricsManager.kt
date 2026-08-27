package com.tencent.wecarflow

import android.os.Bundle
import android.util.Log

/**
 * 车载歌词管理器 - 用于 vivo 智能车载投屏歌词显示
 * 
 * 使用纯反射方式访问 audio_service 的 MediaSessionCompat，
 * 避免编译时依赖 android.support.v4.media 包。
 * 
 * 实现双通道歌词传输：
 * - Channel A: ucar.media.metadata.* (车机端 Launcher 直读)
 * - Channel B: music.media.extras.* + vivomusicmix.* (手机端智能车载转发)
 */
object CarLyricsManager {
    private const val TAG = "CarLyricsManager"

    // Channel A: Metadata 字段（车机端 Launcher 直读）
    const val METADATA_KEY_LYRICS_LINE = "ucar.media.metadata.LYRICS_LINE"
    const val METADATA_KEY_LYRICS_WHOLE = "ucar.media.metadata.LYRICS_WHOLE"
    const val METADATA_KEY_LYRICS_STATUS = "ucar.media.metadata.LYRICS_STATUS"

    // Channel B: Extras 字段（手机端智能车载转发）
    const val EXTRAS_KEY_LYRIC = "music.media.extras.LYRIC"
    const val EXTRAS_KEY_LYRIC_ALLOWED = "music.media.extras.LYRIC_IS_ALLOWED"
    const val EXTRAS_KEY_NOTICE_CAR = "music.media.extras.NOTICE_CAR"

    // vivomusicmix 协议键（vivo 手机端专用）
    const val VIVO_ACTION_KEY = "vivomusicmix.meida.extra.key.action"
    const val VIVO_ACTION_LRC_CHANGE = "vivomusicmix.extra.lrc_change"
    const val VIVO_MEDIA_ID_KEY = "vivomusicmix.extra.key.meidia_id"
    const val VIVO_LYRIC_KEY = "vivomusicmix.extra.key.lyric"

    // 歌词状态枚举
    const val LYRICS_STATUS_SUCCESS = 0L    // 有歌词
    const val LYRICS_STATUS_NO_LYRICS = 1L  // 无歌词
    const val LYRICS_STATUS_LOADING = 2L    // 加载中

    /**
     * 通过反射获取 audio_service 的 MediaSessionCompat 实例
     */
    private fun getSession(): Any? {
        return try {
            // 获取 AudioService 类
            val audioServiceClass = Class.forName("com.ryanheise.audioservice.AudioService")
            // 获取 instance 字段
            val instanceField = audioServiceClass.getDeclaredField("instance")
            instanceField.isAccessible = true
            val audioServiceInstance = instanceField.get(null) ?: return null
            // 获取 mediaSession 字段
            val sessionField = audioServiceClass.getDeclaredField("mediaSession")
            sessionField.isAccessible = true
            sessionField.get(audioServiceInstance)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get MediaSession via reflection: ${e.message}")
            null
        }
    }

    /**
     * 通过反射获取 MediaMetadataCompat.Builder 类
     */
    private fun getMetadataBuilderClass(): Class<*>? {
        return try {
            Class.forName("androidx.media.common.MediaMetadataCompat\$Builder")
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataCompat.Builder not found: ${e.message}")
            null
        }
    }

    /**
     * 更新车载歌词
     * @param currentLine 当前行歌词文本
     * @param wholeLrc 完整 LRC 歌词内容
     * @param hasLyrics 是否有歌词
     */
    fun updateLyrics(currentLine: String, wholeLrc: String, hasLyrics: Boolean) {
        val session = getSession()
        if (session == null) {
            Log.w(TAG, "MediaSession is null, cannot update lyrics")
            return
        }

        // ---- Channel A: Metadata 通道（车机直连）----
        try {
            val builderClass = getMetadataBuilderClass()
            if (builderClass != null) {
                val builder = builderClass.getDeclaredConstructor().newInstance()
                
                // putString 方法
                val putString = builderClass.getMethod("putString", String::class.java, String::class.java)
                // putLong 方法
                val putLong = builderClass.getMethod("putLong", String::class.java, Long::class.java)
                
                // 当前行歌词：有内容才设置，不传 "-1"
                putString.invoke(builder, METADATA_KEY_LYRICS_LINE, currentLine.ifEmpty { "" })

                // 完整歌词 + 状态
                if (hasLyrics && wholeLrc.isNotEmpty()) {
                    putString.invoke(builder, METADATA_KEY_LYRICS_WHOLE, wholeLrc)
                    putLong.invoke(builder, METADATA_KEY_LYRICS_STATUS, LYRICS_STATUS_SUCCESS)
                } else {
                    putString.invoke(builder, METADATA_KEY_LYRICS_WHOLE, "-1")
                    putLong.invoke(builder, METADATA_KEY_LYRICS_STATUS, LYRICS_STATUS_NO_LYRICS)
                }

                // 构建 metadata
                val buildMethod = builderClass.getMethod("build")
                val metadata = buildMethod.invoke(builder)
                
                // 设置到 MediaSession
                val setMetadataMethod = session.javaClass.getMethod("setMetadata", metadata.javaClass)
                setMetadataMethod.invoke(session, metadata)
                
                Log.d(TAG, "Updated metadata: line=$currentLine, hasLyrics=$hasLyrics")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update metadata: ${e.message}", e)
        }

        // ---- Channel B: Extras 通道（手机端转发）----
        try {
            val extras = Bundle().apply {
                // 标准 uCar 协议键
                putBoolean(EXTRAS_KEY_LYRIC_ALLOWED, true)
                if (currentLine.isNotEmpty()) {
                    putString(EXTRAS_KEY_LYRIC, currentLine)
                }
                putBoolean(EXTRAS_KEY_NOTICE_CAR, true)

                // vivomusicmix 协议键（vivo 手机端专用）
                putString(VIVO_ACTION_KEY, VIVO_ACTION_LRC_CHANGE)
                putString(VIVO_MEDIA_ID_KEY, "")
                if (hasLyrics && wholeLrc.isNotEmpty()) {
                    putString(VIVO_LYRIC_KEY, wholeLrc)
                }
            }
            
            // 调用 setExtras 方法
            val setExtrasMethod = session.javaClass.getMethod("setExtras", Bundle::class.java)
            setExtrasMethod.invoke(session, extras)
            
            Log.d(TAG, "Updated extras: line=$currentLine, hasLyrics=$hasLyrics")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update extras: ${e.message}", e)
        }
    }

    /**
     * 清空车载歌词
     */
    fun clearLyrics() {
        updateLyrics("", "", false)
    }
}
