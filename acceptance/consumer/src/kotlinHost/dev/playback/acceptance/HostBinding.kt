package dev.playback.acceptance

import android.content.Context
import android.view.Surface
import com.cn.media.playback.*
import java.io.File

internal class HostBinding(context: Context, listener: PlayerListener) : HostCommands {
    private val player = Player(context, PlayerConfig.Builder().setRequireAllSelectedTracks(true).build(), listener)
    override fun surface(value: Surface?) = player.setVideoSurface(value)
    override fun open(file: File) = player.open(MediaSource.fromFile(file))
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seek(targetMs: Long) = player.seekTo(targetMs)
    override fun selectAudioTrack(track: TrackInfo) = player.selectAudioTrack(track)
    override fun stop() = player.stop()
    override fun release() = player.release()
    override fun snapshot(): PlayerSnapshot = player.snapshot
}
