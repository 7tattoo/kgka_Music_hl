package com.tencent.wecarflow

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.DynamicsProcessing
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.ryanheise.audioservice.AudioServiceActivity
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord

class MainActivity : AudioServiceActivity() {
    private var lyricsStateReceiverRegistered = false
    private var desktopLyricsChannel: MethodChannel? = null
    private var superLyricChannel: MethodChannel? = null
    private var superLyricRegistered = false
    private var bassBoost: BassBoost? = null
    private var bassBoostSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var equalizerSessionId: Int? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var dynamicsProcessingSessionId: Int? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    companion object {
        private const val REQUEST_READ_AUDIO = 1001
        private const val TAG_SUPER_LYRIC = "SuperLyricPublisher"
        private const val TAG_BLUETOOTH_LYRICS = "BluetoothLyrics"
        private const val TAG_CAR_LYRICS = "CarAtomicLyrics"
    }

    private val lyricsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != LyricsOverlayService.ACTION_VISIBILITY_CHANGED) {
                return
            }
            desktopLyricsChannel?.invokeMethod(
                "onVisibilityChanged",
                mapOf(
                    "visible" to intent.getBooleanExtra(
                        LyricsOverlayService.EXTRA_VISIBLE,
                        false
                    ),
                    "userClosed" to intent.getBooleanExtra(
                        LyricsOverlayService.EXTRA_USER_CLOSED,
                        false
                    )
                )
            )
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/screen")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "setKeepScreenOn" -> {
                        val enabled = call.arguments as? Boolean ?: false
                        if (enabled) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        // 车机检测：isAutomotive 判别车机，isCarProjection 判别车载投屏环境。
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/device")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isAutomotive" -> result.success(isAutomotiveDevice())
                    "isCarProjection" -> result.success(isCarProjection())
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/audio_effects")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getEqualizerConfig" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        runCatching {
                            equalizerConfig(audioSessionId)
                        }.onSuccess { config ->
                            result.success(config)
                        }.onFailure { error ->
                            result.error("equalizer_config_failed", error.message, null)
                        }
                    }
                    "configureEqualizer" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val levels = call.argument<List<Int>>("levels") ?: emptyList()

                        runCatching {
                            configureEqualizer(audioSessionId, enabled, levels)
                        }.onSuccess { supported ->
                            result.success(supported)
                        }.onFailure { error ->
                            releaseEqualizer()
                            result.error("equalizer_failed", error.message, null)
                        }
                    }
                    "configureBassBoost" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val strength = call.argument<Int>("strength") ?: 0

                        runCatching {
                            configureBassBoost(audioSessionId, enabled, strength)
                        }.onSuccess { supported ->
                            result.success(supported)
                        }.onFailure { error ->
                            releaseBassBoost()
                            result.error("bass_boost_failed", error.message, null)
                        }
                    }
                    "configureVolumeNormalization" -> {
                        val audioSessionId = call.argument<Int>("audioSessionId")
                        val enabled = call.argument<Boolean>("enabled") ?: false

                        runCatching {
                            configureVolumeNormalization(audioSessionId, enabled)
                        }.onSuccess { supported ->
                            result.success(supported)
                        }.onFailure { error ->
                            releaseDynamicsProcessing()
                            result.error("volume_normalization_failed", error.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "kgka_music_hl/local_music")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasPermission" -> {
                        result.success(hasReadAudioPermission())
                    }
                    "requestPermission" -> {
                        if (hasReadAudioPermission()) {
                            result.success(true)
                        } else {
                            pendingPermissionResult = result
                            requestAudioPermission()
                        }
                    }
                    "getLocalSongs" -> {
                        if (!hasReadAudioPermission()) {
                            result.error("no_permission", "READ_MEDIA_AUDIO permission not granted", null)
                            return@setMethodCallHandler
                        }
                        runCatching {
                            queryLocalSongs()
                        }.onSuccess { songs ->
                            result.success(songs)
                        }.onFailure { error ->
                            result.error("query_failed", error.message, null)
                        }
                    }
                    "getAlbumArt" -> {
                        val albumId = call.argument<Number>("albumId")?.toLong()
                        if (albumId == null || albumId <= 0) {
                            result.success(null)
                            return@setMethodCallHandler
                        }
                        runCatching {
                            getAlbumArtBytes(albumId)
                        }.onSuccess { bytes ->
                            result.success(bytes)
                        }.onFailure { error ->
                            result.error("album_art_failed", error.message, null)
                        }
                    }
                    "getEmbeddedLyrics" -> {
                        val filePath = call.argument<String>("filePath")
                        if (filePath.isNullOrEmpty()) {
                            result.success(null)
                            return@setMethodCallHandler
                        }
                        runCatching {
                            getEmbeddedLyrics(filePath)
                        }.onSuccess { lyrics ->
                            result.success(lyrics)
                        }.onFailure { error ->
                            result.error("lyrics_failed", error.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        desktopLyricsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/desktop_lyrics"
        )
        registerLyricsStateReceiver()
        desktopLyricsChannel?.setMethodCallHandler { call, result ->
                when (call.method) {
                    "checkPermission" -> {
                        result.success(Settings.canDrawOverlays(this))
                    }
                    "requestPermission" -> {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        result.success(null)
                    }
                    "show" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            result.error("no_permission", "No overlay permission", null)
                            return@setMethodCallHandler
                        }
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_LYRICS
                            putExtra(LyricsOverlayService.EXTRA_TITLE, title)
                            putExtra(LyricsOverlayService.EXTRA_ARTIST, artist)
                            putExtra(LyricsOverlayService.EXTRA_CURRENT_LYRIC, "")
                            putExtra(LyricsOverlayService.EXTRA_NEXT_LYRIC, "")
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "hide" -> {
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_HIDE
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "updateLyrics" -> {
                        val current = call.argument<String>("current") ?: ""
                        val next = call.argument<String>("next") ?: ""
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_LYRICS
                            putExtra(LyricsOverlayService.EXTRA_CURRENT_LYRIC, current)
                            putExtra(LyricsOverlayService.EXTRA_NEXT_LYRIC, next)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "updatePlayState" -> {
                        val isPlaying = call.argument<Boolean>("isPlaying") ?: false
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_PLAY_STATE
                            putExtra(LyricsOverlayService.EXTRA_IS_PLAYING, isPlaying)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "isVisible" -> {
                        result.success(LyricsOverlayService.isRunning(this))
                    }
                    "updateKaraokeProgress" -> {
                        val progress = call.argument<Double>("progress")?.toFloat() ?: 0f
                        val lineDurationMs = call.argument<Int>("lineDurationMs") ?: 0
                        val isPlaying = call.argument<Boolean>("isPlaying") ?: false
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_KARAOKE
                            putExtra(LyricsOverlayService.EXTRA_PROGRESS, progress)
                            putExtra(LyricsOverlayService.EXTRA_LINE_DURATION_MS, lineDurationMs)
                            putExtra(LyricsOverlayService.EXTRA_IS_PLAYING, isPlaying)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "updateSettings" -> {
                        val opacity = call.argument<Double>("opacity")?.toFloat() ?: 0.8f
                        val locked = call.argument<Boolean>("locked") ?: false
                        val passthrough = call.argument<Boolean>("passthrough") ?: false
                        // 颜色值小于 2^31 时经 MethodChannel 到达为 Integer，需经 Number 转换
                        val textColorLong =
                            (call.argument<Number>("textColor"))?.toLong() ?: 0xFFFFFFFFL
                        val backgroundColorLong =
                            (call.argument<Number>("backgroundColor"))?.toLong() ?: 0xFF1A1A2EL
                        val fontSize = call.argument<Double>("fontSize")?.toFloat() ?: 16f
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_UPDATE_SETTINGS
                            putExtra(LyricsOverlayService.EXTRA_OPACITY, opacity)
                            putExtra(LyricsOverlayService.EXTRA_LOCKED, locked)
                            putExtra(LyricsOverlayService.EXTRA_PASSTHROUGH, passthrough)
                            putExtra(LyricsOverlayService.EXTRA_TEXT_COLOR, textColorLong.toInt())
                            putExtra(LyricsOverlayService.EXTRA_BACKGROUND_COLOR, backgroundColorLong.toInt())
                            putExtra(LyricsOverlayService.EXTRA_FONT_SIZE, fontSize)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    "setAppForeground" -> {
                        val isForeground = call.argument<Boolean>("isForeground") ?: false
                        val intent = Intent(this, LyricsOverlayService::class.java).apply {
                            action = LyricsOverlayService.ACTION_SET_APP_FOREGROUND
                            putExtra(LyricsOverlayService.EXTRA_IS_FOREGROUND, isForeground)
                        }
                        startService(intent)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        // SuperLyricApi 歌词发布到系统服务
        superLyricChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/super_lyric"
        )
        superLyricChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "isAvailable" -> {
                    runCatching {
                        SuperLyricHelper.isAvailable()
                    }.onSuccess { available ->
                        result.success(available)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "isAvailable failed: ${error.message}")
                        result.success(false)
                    }
                }
                "registerPublisher" -> {
                    runCatching {
                        if (!superLyricRegistered) {
                            SuperLyricHelper.registerPublisher()
                            superLyricRegistered = true
                        }
                        // 以系统服务里的真实注册状态为准（本地标志位可能过期）
                        val registered = runCatching {
                            SuperLyricHelper.isPublisherRegistered()
                        }.getOrNull() ?: superLyricRegistered
                        Log.d(TAG_SUPER_LYRIC, "registerPublisher: registered=$registered")
                        result.success(registered)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "registerPublisher failed: ${error.message}")
                        result.success(false)
                    }
                }
                "unregisterPublisher" -> {
                    runCatching {
                        if (superLyricRegistered) {
                            SuperLyricHelper.unregisterPublisher()
                            superLyricRegistered = false
                        }
                        result.success(null)
                    }.onFailure {
                        result.success(null)
                    }
                }
                "sendLyric" -> {
                    runCatching {
                        // 自愈：启动时注册可能因系统服务未就绪而失败，
                        // 这里在首次发送前补注册一次（幂等，服务端按 UID 去重）
                        if (!superLyricRegistered) {
                            SuperLyricHelper.registerPublisher()
                            superLyricRegistered = true
                        }
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val album = call.argument<String>("album") ?: ""
                        val lyricText = call.argument<String>("lyricText") ?: ""
                        // ⚠️ Dart int 经 MethodChannel 传输后，能放进 32 位时是 Integer，
                        // 直接 cast Long 会抛 ClassCastException，必须经 Number.toLong() 转换
                        val lyricStartTime =
                            (call.argument<Number>("lyricStartTime"))?.toLong() ?: 0L
                        val lyricEndTime =
                            (call.argument<Number>("lyricEndTime"))?.toLong() ?: 0L
                        val secondaryText = call.argument<String>("secondaryText")
                        val translationText = call.argument<String>("translationText")
                        val words = call.argument<List<Map<String, Any>>>("words")

                        val lyricData = SuperLyricData()
                            .setTitle(title)
                            .setArtist(artist)
                            .setAlbum(album)

                        // 主歌词行（含逐字）
                        val lyricWords: Array<SuperLyricWord>? = words?.mapNotNull { w ->
                            val wordText = w["word"] as? String
                            val wordStart = (w["startTime"] as? Number)?.toLong()
                            val wordEnd = (w["endTime"] as? Number)?.toLong()
                            if (wordText != null && wordStart != null && wordEnd != null) {
                                SuperLyricWord(wordText, wordStart, wordEnd)
                            } else null
                        }?.takeIf { it.isNotEmpty() }?.toTypedArray()

                        val mainLyric = if (lyricWords != null) {
                            SuperLyricLine(lyricText, lyricWords, lyricStartTime, lyricEndTime)
                        } else {
                            SuperLyricLine(lyricText, lyricStartTime, lyricEndTime)
                        }
                        lyricData.setLyric(mainLyric)

                        // 副歌词行（音译/罗马音）
                        if (!secondaryText.isNullOrBlank()) {
                            lyricData.setSecondary(
                                SuperLyricLine(secondaryText, lyricStartTime, lyricEndTime)
                            )
                        }
                        // 翻译行
                        if (!translationText.isNullOrBlank()) {
                            lyricData.setTranslation(
                                SuperLyricLine(translationText, lyricStartTime, lyricEndTime)
                            )
                        }
                        SuperLyricHelper.sendLyric(lyricData)
                        Log.d(
                            TAG_SUPER_LYRIC,
                            "sendLyric ok: \"$lyricText\" ($lyricStartTime-$lyricEndTime ms), " +
                                "words=${lyricWords?.size ?: 0}, " +
                                "secondary=${!secondaryText.isNullOrBlank()}, " +
                                "translation=${!translationText.isNullOrBlank()}"
                        )
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "sendLyric failed: ${error.message}")
                        result.error("send_lyric_failed", error.message, null)
                    }
                }
                "sendStop" -> {
                    runCatching {
                        if (!superLyricRegistered) {
                            SuperLyricHelper.registerPublisher()
                            superLyricRegistered = true
                        }
                        SuperLyricHelper.sendStop(SuperLyricData())
                        Log.d(TAG_SUPER_LYRIC, "sendStop ok")
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_SUPER_LYRIC, "sendStop failed: ${error.message}")
                        result.error("send_stop_failed", error.message, null)
                    }
                }
                "debugState" -> {
                    result.success(
                        mapOf(
                            "serviceAvailable" to
                                (runCatching { SuperLyricHelper.isAvailable() }.getOrNull() ?: false),
                            "localRegisteredFlag" to superLyricRegistered,
                            "publisherRegistered" to
                                (runCatching { SuperLyricHelper.isPublisherRegistered() }
                                    .getOrNull() ?: false),
                        )
                    )
                }
                else -> result.notImplemented()
            }
        }

        // 车载蓝牙歌词广播
        val btLyricChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/bluetooth_lyrics"
        )
        btLyricChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "broadcastMetaChanged" -> {
                    runCatching {
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val album = call.argument<String>("album") ?: ""
                        val lyric = call.argument<String>("lyric") ?: ""
                        val positionMs = (call.argument<Number>("positionMs") ?: 0).toLong()
                        val durationMs = (call.argument<Number>("durationMs") ?: 0).toLong()
                        val playing = call.argument<Boolean>("playing") ?: false
                        val track = (call.argument<Number>("track") ?: 0).toInt()
                        val listSize = (call.argument<Number>("listSize") ?: 0).toInt()

                        val extras = Bundle().apply {
                            putString("track", title)
                            putString("artist", artist)
                            putString("album", album)
                            putString("id", "")
                            putLong("position", positionMs)
                            putLong("duration", durationMs)
                            putBoolean("playing", playing)
                            putInt("ListSize", listSize)
                            putInt("trackPos", track)
                            putString("lyric", lyric)
                            // 网易云/部分车机额外字段
                            putString("currentLyric", lyric)
                            putString("DISPLAY_NAME", title)
                        }

                        // 标准 Android 音乐元数据变化广播
                        sendOrderedBroadcast(Intent("com.android.music.metachanged").apply {
                            putExtras(extras)
                            setPackage(null)
                        }, null)

                        // 注意：per-line 元数据广播只发 metachanged 系。
                        // 严禁在此发送 playbackcomplete / queuechanged /
                        // playstatechanged——这些是"播放完成/队列变化/状态切换"
                        // 语义信号，随歌词逐句误发会导致车机、仪表盘误判切歌
                        // 或清空状态；真实状态变化由 broadcastPlayStateChanged
                        // 专用通道发送。

                        // QQMusic / Netease 自定义广播（很多车机 App 监听）
                        sendBroadcast(Intent("com.netease.cloudmusic.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        sendBroadcast(Intent("com.tencent.qqmusic.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        // KuGou/KuWo 广播
                        sendBroadcast(Intent("com.kugou.android.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        sendBroadcast(Intent("cn.kuwo.player.metachanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })

                        Log.d(
                            TAG_BLUETOOTH_LYRICS,
                            "metaChanged ok: \"$title\"/\"$artist\", lyric=\"${lyric.take(24)}\", " +
                                "pos=${positionMs}ms, playing=$playing, track=$track/$listSize, 5 actions sent"
                        )
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_BLUETOOTH_LYRICS, "broadcastMetaChanged failed: ${error.message}")
                        result.success(false)
                    }
                }
                "broadcastPlayStateChanged" -> {
                    runCatching {
                        val title = call.argument<String>("title") ?: ""
                        val artist = call.argument<String>("artist") ?: ""
                        val album = call.argument<String>("album") ?: ""
                        val positionMs = (call.argument<Number>("positionMs") ?: 0).toLong()
                        val durationMs = (call.argument<Number>("durationMs") ?: 0).toLong()
                        val playing = call.argument<Boolean>("playing") ?: false

                        val extras = Bundle().apply {
                            putString("track", title)
                            putString("artist", artist)
                            putString("album", album)
                            putLong("position", positionMs)
                            putLong("duration", durationMs)
                            putBoolean("playing", playing)
                        }
                        sendBroadcast(Intent("com.android.music.playstatechanged").apply {
                            putExtras(extras)
                            setPackage(null)
                        })
                        sendBroadcast(Intent("com.netease.cloudmusic.playstatechanged").apply {
                            putExtras(Bundle(extras))
                            setPackage(null)
                        })
                        sendBroadcast(Intent("com.tencent.qqmusic.playstatechanged").apply {
                            putExtras(extras)
                            setPackage(null)
                        })
                        Log.d(
                            TAG_BLUETOOTH_LYRICS,
                            "playStateChanged ok: \"$title\", playing=$playing, pos=${positionMs}ms"
                        )
                        result.success(true)
                    }.onFailure { error ->
                        Log.w(TAG_BLUETOOTH_LYRICS, "broadcastPlayStateChanged failed: ${error.message}")
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // vivo 原子随身听（vivomusicmix）lrc_change 事件
        val carLyricChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "kgka_music_hl/car_lyrics"
        )
        carLyricChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "pushAtomicLyrics" -> {
                    runCatching {
                        val lyric = call.argument<String>("lyric") ?: ""
                        val mediaId = call.argument<String>("mediaId") ?: ""
                        if (lyric.isEmpty()) {
                            result.success(false)
                        } else {
                            val ok = pushVivoAtomicLyrics(lyric, mediaId)
                            result.success(ok)
                        }
                    }.onFailure { error ->
                        Log.w(TAG_CAR_LYRICS, "pushAtomicLyrics failed: ${error.message}")
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    /// 通过反射取 audio_service 的 MediaSessionCompat，发送 vivomusicmix lrc_change 事件。
    /// audio_service 0.18.x 未通过 Dart 暴露 setExtras，故此处反射
    /// com.ryanheise.audioservice.AudioService.instance.mediaSession 后调用其 public setExtras。
    private fun pushVivoAtomicLyrics(wholeLrc: String, mediaId: String): Boolean {
        return try {
            val serviceClass = Class.forName("com.ryanheise.audioservice.AudioService")
            val instanceField = serviceClass.getDeclaredField("instance")
            instanceField.isAccessible = true
            val serviceInstance = instanceField.get(null)
            if (serviceInstance == null) {
                Log.w(TAG_CAR_LYRICS, "AudioService.instance is null")
                return false
            }
            val sessionField = serviceClass.getDeclaredField("mediaSession")
            sessionField.isAccessible = true
            val mediaSession = sessionField.get(serviceInstance)
            if (mediaSession == null) {
                Log.w(TAG_CAR_LYRICS, "mediaSession is null")
                return false
            }
            // vivo 官方拼写错误（meida / meidia_id）必须照抄，写成正确拼写反而收不到
            val extras = Bundle().apply {
                putString(
                    "vivomusicmix.meida.extra.key.action",
                    "vivomusicmix.extra.lrc_change"
                )
                putString("vivomusicmix.extra.key.lyric", wholeLrc)
                putString("vivomusicmix.extra.key.meidia_id", mediaId)
            }
            val setExtras = mediaSession.javaClass.getMethod("setExtras", Bundle::class.java)
            setExtras.invoke(mediaSession, extras)
            Log.d(TAG_CAR_LYRICS, "lrc_change sent: mediaId=$mediaId, lrc.len=${wholeLrc.length}")
            true
        } catch (t: Throwable) {
            Log.w(TAG_CAR_LYRICS, "pushVivoAtomicLyrics error: ${t.message}")
            false
        }
    }

    private fun readAudioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasReadAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, readAudioPermission()) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(readAudioPermission()),
            REQUEST_READ_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
        }
    }

    private fun queryLocalSongs(): List<Map<String, Any?>> {
        val songs = mutableListOf<Map<String, Any?>>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.IS_MUSIC,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.let {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val title = it.getString(titleColumn) ?: "未知歌曲"
                    val artist = it.getString(artistColumn) ?: "未知艺人"
                    val album = it.getString(albumColumn) ?: ""
                    val duration = it.getLong(durationColumn)
                    val filePath = it.getString(dataColumn) ?: ""
                    val albumId = it.getLong(albumIdColumn)

                    // 构建专辑封面 URI
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )

                    if (filePath.isNotEmpty()) {
                        songs.add(
                            mapOf(
                                "id" to filePath,
                                "title" to title,
                                "artist" to artist,
                                "album" to album,
                                "duration" to duration,
                                "filePath" to filePath,
                                "albumArtUri" to albumArtUri.toString(),
                            )
                        )
                    }
                }
            }
        } finally {
            cursor?.close()
        }

        return songs
    }

    private fun registerLyricsStateReceiver() {
        if (lyricsStateReceiverRegistered) {
            return
        }
        val filter = IntentFilter(LyricsOverlayService.ACTION_VISIBILITY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lyricsStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(lyricsStateReceiver, filter)
        }
        lyricsStateReceiverRegistered = true
    }

    private fun equalizerConfig(audioSessionId: Int?): Map<String, Any>? {
        if (audioSessionId == null || audioSessionId <= 0) {
            return null
        }
        val effect = ensureEqualizer(audioSessionId)
        val range = effect.bandLevelRange
        val bands = (0 until effect.numberOfBands).map { index ->
            val band = index.toShort()
            mapOf(
                "centerHz" to effect.getCenterFreq(band) / 1000,
                "level" to effect.getBandLevel(band).toInt()
            )
        }
        return mapOf(
            "range" to listOf(range[0].toInt(), range[1].toInt()),
            "bands" to bands
        )
    }

    private fun configureEqualizer(
        audioSessionId: Int?,
        enabled: Boolean,
        levels: List<Int>
    ): Boolean {
        if (!enabled) {
            releaseEqualizer()
            return true
        }
        if (audioSessionId == null || audioSessionId <= 0) {
            return false
        }

        val effect = ensureEqualizer(audioSessionId)
        val range = effect.bandLevelRange
        val bandCount = minOf(effect.numberOfBands.toInt(), levels.size)
        for (index in 0 until bandCount) {
            val level = levels[index].coerceIn(range[0].toInt(), range[1].toInt())
            effect.setBandLevel(index.toShort(), level.toShort())
        }
        effect.enabled = true
        return true
    }

    private fun ensureEqualizer(audioSessionId: Int): Equalizer {
        if (equalizerSessionId == audioSessionId && equalizer != null) {
            return equalizer!!
        }
        releaseEqualizer()
        return Equalizer(0, audioSessionId).also {
            equalizer = it
            equalizerSessionId = audioSessionId
        }
    }

    private fun releaseEqualizer() {
        equalizer?.runCatching {
            enabled = false
            release()
        }
        equalizer = null
        equalizerSessionId = null
    }

    private fun configureBassBoost(
        audioSessionId: Int?,
        enabled: Boolean,
        strength: Int
    ): Boolean {
        if (!enabled) {
            releaseBassBoost()
            return true
        }
        if (audioSessionId == null || audioSessionId <= 0) {
            return false
        }

        val effect = if (bassBoostSessionId == audioSessionId && bassBoost != null) {
            bassBoost!!
        } else {
            releaseBassBoost()
            BassBoost(0, audioSessionId).also {
                bassBoost = it
                bassBoostSessionId = audioSessionId
            }
        }

        val clampedStrength = strength.coerceIn(0, 1000).toShort()
        if (effect.strengthSupported) {
            effect.setStrength(clampedStrength)
        } else {
            effect.setStrength(if (clampedStrength > 0) 1000 else 0)
        }
        effect.enabled = true
        return true
    }

    private fun releaseBassBoost() {
        bassBoost?.runCatching {
            enabled = false
            release()
        }
        bassBoost = null
        bassBoostSessionId = null
    }

    private fun configureVolumeNormalization(audioSessionId: Int?, enabled: Boolean): Boolean {
        if (!enabled) {
            releaseDynamicsProcessing()
            return true
        }
        if (audioSessionId == null || audioSessionId <= 0) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }

        try {
            val effect = if (dynamicsProcessingSessionId == audioSessionId && dynamicsProcessing != null) {
                dynamicsProcessing!!
            } else {
                releaseDynamicsProcessing()

                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, // channels
                    false, // preEq
                    0,
                    true, // mbc
                    1, // 1 band compressor
                    false, // postEq
                    0,
                    true // limiter
                ).build()

                DynamicsProcessing(0, audioSessionId, config).also {
                    dynamicsProcessing = it
                    dynamicsProcessingSessionId = audioSessionId
                }
            }

            val mbcBand = DynamicsProcessing.MbcBand(
                true, // enabled
                20000.0f, // cutoffFrequency
                50.0f, // attackTime
                300.0f, // releaseTime
                4.0f, // ratio
                -15.0f, // threshold
                2.0f, // kneeWidth
                -60.0f, // noiseGateThreshold
                1.0f, // expanderRatio
                2.0f, // preGain
                0.0f // postGain
            )

            val limiter = DynamicsProcessing.Limiter(
                true, // inUse
                true, // enabled
                0, // linkGroup
                1.0f, // attackTime
                100.0f, // releaseTime
                10.0f, // ratio
                -1.0f, // threshold
                0.0f // postGain
            )

            for (c in 0 until 2) {
                effect.setMbcBandByChannelIndex(c, 0, mbcBand)
                effect.setLimiterByChannelIndex(c, limiter)
            }

            effect.enabled = true
            return true
        } catch (e: Exception) {
            releaseDynamicsProcessing()
            return false
        }
    }

    private fun releaseDynamicsProcessing() {
        dynamicsProcessing?.runCatching {
            enabled = false
            release()
        }
        dynamicsProcessing = null
        dynamicsProcessingSessionId = null
    }

    /// 是否为 Android Automotive 车机设备。
    /// 仅依赖官方 FEATURE_AUTOMOTIVE 标记：国产定制 AOSP 车机通常未声明，
    /// 会判为 false，需用户在设置→个性化手动开启车机模式。
    private fun isAutomotiveDevice(): Boolean {
        // 1. Android Automotive 车机（内置车机）
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return true
        // 2. 车载 UI 模式（vivo 车联/Android Auto/亿连等投屏会设置）
        if (isCarUiMode()) return true
        return false
    }

    /// 是否为车载投屏 UI 模式。
    private fun isCarUiMode(): Boolean {
        return try {
            val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
            uiMode == android.content.res.Configuration.UI_MODE_TYPE_CAR
        } catch (_: Exception) {
            false
        }
    }

    /// 是否为车载投屏环境（独立于 isAutomotiveDevice 的额外检测）。
    /// 用于播放页等场景在投屏时自动启用分栏布局。
    private fun isCarProjection(): Boolean {
        // 1. 车载 UI 模式
        if (isCarUiMode()) return true
        // 2. 检测外接显示（无线投屏到车机等场景）
        try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val displays = displayManager.displays
            if (displays.any { it.displayId != android.view.Display.DEFAULT_DISPLAY }) {
                return true
            }
        } catch (_: Exception) {}
        // 3. 检测 vivo 车联特征
        try {
            if (packageManager.hasSystemFeature("com.vivo.feature.car")) return true
        } catch (_: Exception) {}
        return false
    }

    private fun getAlbumArtBytes(albumId: Long): ByteArray? {
        val uri = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun getEmbeddedLyrics(filePath: String): String? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            // METADATA_KEY_LYRICS = 26，API 29+ 才有常量名，直接用数字 key 兼容旧版本
            val lyrics = retriever.extractMetadata(26)
            retriever.release()
            lyrics
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        releaseEqualizer()
        releaseBassBoost()
        if (superLyricRegistered) {
            runCatching { SuperLyricHelper.unregisterPublisher() }
            superLyricRegistered = false
        }
        if (lyricsStateReceiverRegistered) {
            unregisterReceiver(lyricsStateReceiver)
            lyricsStateReceiverRegistered = false
        }
        super.onDestroy()
    }
}
