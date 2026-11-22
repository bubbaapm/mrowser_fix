package net.mrowser.handoff

import android.content.Context
import android.content.Intent
import android.widget.Toast
import net.mrowser.player.PlayerActivity
import net.mrowser.stream.PlaybackRequest
import net.mrowser.stream.StreamSniffer

/** Turns a chip activation, or a Play on a downloaded video, into a launched PlayerActivity. */
class HandoffController(
    private val context: Context,
    private val sniffer: StreamSniffer,
    // Runs on the UI thread before launching the native player, giving the
    // browser a chance to pause any embedded HTML5 playback.
    private val beforePlayerLaunch: () -> Unit = {}
) {
    fun play() {
        sniffer.bestRequest()?.let(::play)
            ?: Toast.makeText(context, "No stream detected yet", Toast.LENGTH_SHORT).show()
    }

    fun play(request: PlaybackRequest) {
        beforePlayerLaunch()
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_REQUEST, request.toJson())
        )
    }
}
