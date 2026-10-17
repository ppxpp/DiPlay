package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class CarPlayQueuedRecoveryTest {
    private class Fixture {
        val controller = mock(CarPlayController::class.java)
        val session = mock(AirPlaySession::class.java)
        val revision = AtomicLong()
        val executor = mock(ExecutorService::class.java)
        lateinit var queued: Runnable
        init {
            ReflectionHelpers.setField(controller, "activeSession", session)
            ReflectionHelpers.setField(controller, "mediaIntentRevision", revision)
            ReflectionHelpers.setField(controller, "touchExecutor", executor)
            doReturn(CarPlayNowPlaying(playing = true)).`when`(controller).nowPlayingSnapshot()
            doAnswer { queued = it.arguments[0] as Runnable; null }.`when`(executor).execute(any(Runnable::class.java))
        }
        fun queue(allowed: () -> Boolean) {
            doCallRealMethod().`when`(controller).recoverMediaPlayback(session, 0, allowed)
            assertTrue(controller.recoverMediaPlayback(session, 0, allowed))
        }
    }

    @Test fun pauseDuringSourceReadCancelsQueuedPlay() {
        val f = Fixture()
        f.queue { f.revision.incrementAndGet(); true }
        f.queued.run()
        verify(f.session, never()).sendMedia(CarPlayMediaButton.PLAY)
    }

    @Test fun replacedSessionCannotReceiveOldRecoveryPlay() {
        val f = Fixture()
        f.queue { true }
        ReflectionHelpers.setField(f.controller, "activeSession", mock(AirPlaySession::class.java))
        f.queued.run()
        verify(f.session, never()).sendMedia(CarPlayMediaButton.PLAY)
    }

    @Test fun rejectedSourceOrCallConditionCannotSendPlay() {
        val f = Fixture()
        f.queue { false }
        f.queued.run()
        verify(f.session, never()).sendMedia(CarPlayMediaButton.PLAY)
    }

    @Test fun stillCurrentPermittedRecoverySendsExplicitPlay() {
        val f = Fixture()
        f.queue { true }
        f.queued.run()
        verify(f.session).sendMedia(CarPlayMediaButton.PLAY)
    }
}
