package com.android.player.exo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.android.player.BuildConfig
import com.android.player.logger.PlayerEventLogger
import com.android.player.model.ASong
import com.android.player.service.PlayerService
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.C.CONTENT_TYPE_MUSIC
import com.google.android.exoplayer2.C.USAGE_MEDIA
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.source.ExtractorMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util

/**
 * This class is responsible for managing the player(actions, state, ...) using [ExoPlayer]
 *
 *
 * @author ZARA
 * */
class ExoPlayerManager(val context: Context) : OnExoPlayerManagerCallback {


    val TAG = ExoPlayerManager::class.java.name
    private val BANDWIDTH_METER = DefaultBandwidthMeter()
    // The volume we set the media player to when we lose audio focus, but are
    // allowed to reduce the volume instead of stopping playback.
    private val VOLUME_DUCK = 0.2f
    // The volume we set the media player when we have audio focus.
    private val VOLUME_NORMAL = 1.0f
    //private val TAG = LogHelper.makeLogTag(LocalPlayback::class.java)
    // we don't have audio focus, and can't duck (play at a low volume)
    private val AUDIO_NO_FOCUS_NO_DUCK = 0
    // we don't have focus, but can duck (play at a low volume)
    private val AUDIO_NO_FOCUS_CAN_DUCK = 1
    // we have full audio focus
    private val AUDIO_FOCUSED = 2
    private var mWifiLock: WifiManager.WifiLock? = null
    private var mAudioManager: AudioManager? = null
    private val mEventListener = ExoPlayerEventListener()
    private val mAudioNoisyIntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    private var mPlayOnFocusGain: Boolean = false
    private var mExoSongStateCallback: OnExoPlayerManagerCallback.OnSongStateCallback? = null
    private var mAudioNoisyReceiverRegistered: Boolean = false
    private var mCurrentSong: ASong? = null
    private var mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
    private var mExoPlayer: SimpleExoPlayer? = null


    private val mAudioNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                Log.d(TAG, "Headphones disconnected.")
                if (isPlaying()) {
                    val i = Intent(context, PlayerService::class.java)
                    i.action = PlayerService.ACTION_CMD
                    i.putExtra(PlayerService.CMD_NAME, PlayerService.CMD_PAUSE)
                    context.applicationContext.startService(i)
                }
            }
        }
    }

    private val mUpdateProgressHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val duration = mExoPlayer?.duration ?: 0
            val position = mExoPlayer?.currentPosition ?: 0
            onUpdateProgress(position, duration)
            sendEmptyMessageDelayed(0, UPDATE_PROGRESS_DELAY)
        }
    }

    // Whether to return STATE_NONE or STATE_STOPPED when mExoPlayer is null;
    private var mExoPlayerIsStopped = false
    private val mOnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "onAudioFocusChange. focusChange= $focusChange")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> mCurrentAudioFocusState = AUDIO_FOCUSED
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    // Audio focus was lost, but it's possible to duck (i.e.: play quietly)
                    mCurrentAudioFocusState = AUDIO_NO_FOCUS_CAN_DUCK
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Lost audio focus, but will gain it back (shortly), so note whether
                    // playback should resume
                    mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
                    mPlayOnFocusGain = mExoPlayer != null && mExoPlayer?.playWhenReady ?: false
                }
                AudioManager.AUDIOFOCUS_LOSS ->
                    // Lost audio focus, probably "permanently"
                    mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
            }
            // Update the player state based on the change
            configurePlayerState()
        }

    init {
        this.mAudioManager =
            context.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        this.mWifiLock =
            (context.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "app_lock")
    }

    private fun onUpdateProgress(position: Long, duration: Long) {
        Log.i(TAG, "onUpdateProgress: position: $position duration: $duration")
        mExoSongStateCallback?.setCurrentPosition(position, duration)
    }

    override fun start() {
        // Nothing to do
    }

    override fun stop() {
        giveUpAudioFocus()
        unregisterAudioNoisyReceiver()
        releaseResources(true)
    }

    override fun getCurrentSongState(): Int {
        if (mExoPlayer == null) {
            return if (mExoPlayerIsStopped) {
                mCurrentSong?.setPlay(false)
                PlaybackState.STATE_STOPPED
            } else {
                mCurrentSong?.setPlay(false)
                PlaybackState.STATE_NONE
            }
        }
        when (mExoPlayer?.playbackState) {
            Player.STATE_IDLE -> {
                mCurrentSong?.setPlay(false)
                return PlaybackState.STATE_PAUSED

            }
            Player.STATE_BUFFERING -> {
                mCurrentSong?.setPlay(true)
                return PlaybackState.STATE_BUFFERING
            }
            Player.STATE_READY -> {
                return if (mExoPlayer?.playWhenReady == true) {
                    mCurrentSong?.setPlay(true)
                    PlaybackState.STATE_PLAYING
                } else {
                    mCurrentSong?.setPlay(false)
                    PlaybackState.STATE_PAUSED
                }
            }
            Player.STATE_ENDED -> {
                mCurrentSong?.setPlay(false)
                return PlaybackState.STATE_PAUSED
            }
            else -> {
                mCurrentSong?.setPlay(false)
                return PlaybackState.STATE_NONE
            }
        }
    }

    override fun isPlaying(): Boolean {
        return mPlayOnFocusGain || mExoPlayer != null && mExoPlayer?.playWhenReady == true
    }

    override fun getCurrentStreamPosition(): Long {
        return mExoPlayer?.currentPosition ?: 0
    }

    override fun updateLastKnownStreamPosition() {
        // Nothing to do. Position maintained by ExoPlayer.
    }

    override fun play(aSong: ASong) {
        mPlayOnFocusGain = true
        tryToGetAudioFocus()
        registerAudioNoisyReceiver()
        val songId = aSong.getSongId()
        val songHasChanged: Boolean
        songHasChanged = songId != mCurrentSong?.getSongId()
        //if (songHasChanged) {
        mCurrentSong = aSong
        // }

        if (songHasChanged || mExoPlayer == null) {
            releaseResources(false) // release everything except the player
            val source = mCurrentSong?.getSource()
            if (mExoPlayer == null) {
                mExoPlayer = ExoPlayerFactory.newSimpleInstance(
                    context.applicationContext,
                    DefaultTrackSelector(),
                    DefaultLoadControl()
                )
                mExoPlayer?.addListener(mEventListener)
            }

            // Android "O" makes much greater use of AudioAttributes, especially
            // with regards to AudioFocus. All of tracks are music, but
            // if your content includes spoken word such as audio books or pod casts
            // then the content type should be set to CONTENT_TYPE_SPEECH for those
            // tracks.
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(CONTENT_TYPE_MUSIC)
                .setUsage(USAGE_MEDIA)
                .build()
            mExoPlayer?.audioAttributes = audioAttributes

            // Produces DataSource instances through which media data is loaded.
            val dataSourceFactory = buildDataSourceFactory(context)
            // Produces Extractor instances for parsing the media data.
            val extractorsFactory = DefaultExtractorsFactory()
            // The MediaSource represents the media to be played.
            val extractorMediaFactory = ExtractorMediaSource.Factory(dataSourceFactory)
            extractorMediaFactory.setExtractorsFactory(extractorsFactory)
            //MediaSource mediaSource = extractorMediaFactory.createMediaSource(Uri.parse(source));

            val mediaSource: MediaSource
            when (mCurrentSong?.getSongType()) {
                C.TYPE_HLS -> mediaSource = HlsMediaSource(
                    Uri.parse(source),
                    dataSourceFactory,
                    Handler(),
                    PlayerEventLogger(DefaultTrackSelector(AdaptiveTrackSelection.Factory()))
                )
                C.TYPE_OTHER -> mediaSource = ExtractorMediaSource(
                    Uri.parse(mCurrentSong?.getSource()),
                    dataSourceFactory,
                    DefaultExtractorsFactory(),
                    Handler(),
                    PlayerEventLogger(DefaultTrackSelector(AdaptiveTrackSelection.Factory()))
                )
                else -> mediaSource = HlsMediaSource(
                    Uri.parse(source), dataSourceFactory, Handler(), PlayerEventLogger(
                        DefaultTrackSelector(AdaptiveTrackSelection.Factory())
                    )
                )
            }

            // Prepares media to play (happens on background thread) and triggers
            // {@code onPlayerStateChanged} callback when the stream is ready to play.
            mExoPlayer?.prepare(mediaSource)

            // If we are streaming from the internet, we want to hold a
            // Wifi lock, which prevents the Wifi radio from going to
            // sleep while the song is playing.
            mWifiLock?.acquire()
        }
        configurePlayerState()
    }


    private fun buildDataSourceFactory(context: Context): DataSource.Factory {
        val dataSourceFactory = DefaultDataSourceFactory(
            context,
            Util.getUserAgent(context, BuildConfig.APPLICATION_ID),
            BANDWIDTH_METER
        )
        return DefaultDataSourceFactory(context, BANDWIDTH_METER, dataSourceFactory)
    }

    override fun pause() {
        // Pause player and cancel the 'foreground service' state.
        mExoPlayer?.playWhenReady = false
        // While paused, retain the player instance, but give up audio focus.
        releaseResources(false)
        unregisterAudioNoisyReceiver()
    }

    override fun seekTo(position: Long) {
        Log.d(TAG, "seekTo called with: $position")
        registerAudioNoisyReceiver()
        mExoPlayer?.seekTo(position)
    }

    override fun setCallback(callback: OnExoPlayerManagerCallback.OnSongStateCallback) {
        this.mExoSongStateCallback = callback
    }

    override fun getCurrentSong(): ASong? {
        return mCurrentSong
    }

    private fun tryToGetAudioFocus() {
        Log.d(TAG, "tryToGetAudioFocus")
        val result = mAudioManager?.requestAudioFocus(
            mOnAudioFocusChangeListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        mCurrentAudioFocusState = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AUDIO_FOCUSED
        } else {
            AUDIO_NO_FOCUS_NO_DUCK
        }
    }

    private fun giveUpAudioFocus() {
        Log.d(TAG, "giveUpAudioFocus")
        if (mAudioManager?.abandonAudioFocus(mOnAudioFocusChangeListener) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK
        }
    }

    /**
     * Reconfigures the player according to audio focus settings and starts/restarts it. This method
     * starts/restarts the ExoPlayer instance respecting the current audio focus state. So if we
     * have focus, it will play normally; if we don't have focus, it will either leave the player
     * paused or set it to a low volume, depending on what is permitted by the current focus
     * settings.
     */
    private fun configurePlayerState() {
        Log.d(TAG, "configurePlayerState. mCurrentAudioFocusState= $mCurrentAudioFocusState")
        if (mCurrentAudioFocusState == AUDIO_NO_FOCUS_NO_DUCK) {
            // We don't have audio focus and can't duck, so we have to pause
            pause()
        } else {
            registerAudioNoisyReceiver()

            if (mCurrentAudioFocusState == AUDIO_NO_FOCUS_CAN_DUCK)
            // We're permitted to play, but only if we 'duck', ie: play softly
                mExoPlayer?.volume = VOLUME_DUCK
            else
                mExoPlayer?.volume = VOLUME_NORMAL


            // If we were playing when we lost focus, we need to resume playing.
            if (mPlayOnFocusGain) {
                mExoPlayer?.playWhenReady = true
                mPlayOnFocusGain = false
            }
        }
    }

    /**
     * Releases resources used by the service for playback, which is mostly just the WiFi lock for
     * local playback. If requested, the ExoPlayer instance is also released.
     *
     * @param releasePlayer Indicates whether the player should also be released
     */
    private fun releaseResources(releasePlayer: Boolean) {
        Log.d(TAG, "releaseResources. releasePlayer= $releasePlayer")

        // Stops and releases player (if requested and available).
        if (releasePlayer) {
            mExoPlayer?.release()
            mExoPlayer?.removeListener(mEventListener)
            mExoPlayer = null
            mExoPlayerIsStopped = true
            mPlayOnFocusGain = false
        }

        if (mWifiLock?.isHeld == true) {
            mWifiLock?.release()
        }
    }

    private fun registerAudioNoisyReceiver() {
        if (!mAudioNoisyReceiverRegistered) {
            context.applicationContext.registerReceiver(
                mAudioNoisyReceiver,
                mAudioNoisyIntentFilter
            )
            mAudioNoisyReceiverRegistered = true
        }
    }

    private fun unregisterAudioNoisyReceiver() {
        if (mAudioNoisyReceiverRegistered) {
            context.applicationContext.unregisterReceiver(mAudioNoisyReceiver)
            mAudioNoisyReceiverRegistered = false
        }
    }

    private inner class ExoPlayerEventListener : Player.EventListener {
        override fun onTimelineChanged(timeline: Timeline?, manifest: Any?, reason: Int) {
            // Nothing to do.
        }

        override fun onTracksChanged(
            trackGroups: TrackGroupArray?, trackSelections: TrackSelectionArray?
        ) {
            // Nothing to do.
        }

        override fun onLoadingChanged(isLoading: Boolean) {
            // Nothing to do.
        }

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY -> {
                    //mUpdateProgressHandler.removeMessages(0)
                    mUpdateProgressHandler.sendEmptyMessage(0)
                    mExoSongStateCallback?.onPlaybackStatusChanged(getCurrentSongState())
                }
                Player.STATE_ENDED -> {
                    // The media player finished playing the current song.
                    mUpdateProgressHandler.removeMessages(0)
                    mExoSongStateCallback?.onCompletion()
                }
            }
        }

        override fun onPlayerError(error: ExoPlaybackException) {
            val what: String = when (error.type) {
                ExoPlaybackException.TYPE_SOURCE -> error.sourceException.message ?: ""
                ExoPlaybackException.TYPE_RENDERER -> error.rendererException.message ?: ""
                ExoPlaybackException.TYPE_UNEXPECTED -> error.unexpectedException.message ?: ""
                else -> "onPlayerError: $error"
            }
            Log.e(TAG, "ExoPlayer error: what=$what")
            mExoSongStateCallback?.onError("ExoPlayer error $what")
        }

        override fun onPositionDiscontinuity(reason: Int) {
            // Nothing to do.
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters?) {
            // Nothing to do.
        }

        override fun onSeekProcessed() {
            // Nothing to do.
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            // Nothing to do.
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Nothing to do.
        }
    }

    companion object {
        const val AUDIO_TYPE = 3
        const val UPDATE_PROGRESS_DELAY = 500L
    }
}