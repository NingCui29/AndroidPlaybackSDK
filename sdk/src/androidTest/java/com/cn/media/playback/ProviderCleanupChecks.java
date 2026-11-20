package com.cn.media.playback;

import android.content.Context;
import android.os.Bundle;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class ProviderCleanupChecks {
    static void run(Context context, Bundle evidence) throws Exception {
        for (boolean releasing : new boolean[]{false, true}) {
            PlayerSnapshot.State waiting = releasing ? PlayerSnapshot.State.RELEASING : PlayerSnapshot.State.STOPPING;
            PlayerSnapshot.State completed = releasing ? PlayerSnapshot.State.RELEASED : PlayerSnapshot.State.IDLE;
            CountDownLatch done = new CountDownLatch(1);
            LinkedBlockingQueue<CommandResult> commands = new LinkedBlockingQueue<>();
            Player player = new Player(context, new PlayerConfig.Builder().build(), new PlayerListener() {
                @Override public void onSnapshot(PlayerSnapshot snapshot) {
                    if (snapshot.state == completed) done.countDown();
                }

                @Override public void onCommandResult(CommandResult result) {
                    commands.add(result);
                }
            });
            try (FixtureProvider.CleanupGate gate = new FixtureProvider.CleanupGate()) {
                long open = player.open(MediaSource.fromUri(FixtureProvider.uri("cleanup", "A04.wav")));
                check(gate.entered.await(3, TimeUnit.SECONDS), "Provider did not enter open");
                long cleanup = releasing ? player.release() : player.stop();
                CommandResult cancelled = commands.poll(1, TimeUnit.SECONDS);
                check(cancelled != null && cancelled.requestId == open &&
                        cancelled.status == CommandResult.Status.CANCELLED, "Open did not cancel");
                CommandResult timeout = commands.poll(4, TimeUnit.SECONDS);
                check(timeout != null && timeout.requestId == cleanup &&
                        timeout.status == CommandResult.Status.FAILED && timeout.error != null &&
                        timeout.error.code == PlayerError.Code.INPUT_TIMEOUT,
                        "Cleanup did not report one input timeout");
                check(timeout.snapshot.state == waiting && player.getSnapshot().state == waiting,
                        "Cleanup completed while the provider remained blocked");
                gate.allowReturn.countDown();
                check(done.await(3, TimeUnit.SECONDS) && player.getSnapshot().state == completed,
                        "Late provider return did not finish cleanup");
                StructPollfd pipe = new StructPollfd();
                pipe.fd = gate.write.getFileDescriptor();
                pipe.events = (short) OsConstants.POLLOUT;
                Os.poll(new StructPollfd[]{pipe}, 0);
                check((pipe.revents & OsConstants.POLLERR) != 0, "Late provider descriptor is still open");
                check(commands.poll(200, TimeUnit.MILLISECONDS) == null, "Duplicate cleanup result");
                evidence.putString(releasing ? "release" : "stop", "INPUT_TIMEOUT, eventual " + completed + ", late FD closed");
            } finally { player.release(); }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
