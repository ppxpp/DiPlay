package com.shilapi.xcertplay.pioneer

import android.os.Looper
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PioneerDebugUpdateGuardTest {
    @get:Rule val files = TemporaryFolder()
    private val tokens = mutableListOf<Any>()
    private var previousHostState: (() -> JSONObject)? = null

    @Before fun idleHost() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        android.os.SystemClock.setCurrentTimeMillis(1_800_000_000_000L)
        previousHostState = PioneerDebugRuntime.hostState
        PioneerDebugRuntime.hostState = ::idleState
        PioneerLwipRuntime.active = false
    }

    @After fun releaseLeases() {
        tokens.forEach(PioneerDebugUpdateGuard::release)
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)
        PioneerLwipRuntime.active = false
        PioneerDebugRuntime.hostState = previousHostState
    }

    private fun idleState() = JSONObject().put("hostAttached", false)
        .put("controllerRunning", false).put("backgroundSession", false)

    private fun begin(): Any = PioneerDebugUpdateGuard.begin().also(tokens::add)

    private fun reject(action: () -> Unit) {
        try { action(); fail("Unsafe operation was accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun idleRequiresExplicitFalseFieldsAndRejectsUnknownOrActiveState() {
        PioneerDebugUpdateGuard.requireIdle(idleState())
        for (field in listOf("hostAttached", "controllerRunning", "backgroundSession")) {
            reject { PioneerDebugUpdateGuard.requireIdle(idleState().apply { remove(field) }) }
            for (value in listOf<Any>("false", 0, JSONObject.NULL, true)) {
                reject { PioneerDebugUpdateGuard.requireIdle(idleState().put(field, value)) }
            }
        }
        reject { PioneerDebugUpdateGuard.requireIdle(idleState().put("hostStateTimeout", true)) }
        reject { PioneerDebugUpdateGuard.requireIdle(idleState().put("hostStateError", "host unavailable")) }
        PioneerLwipRuntime.active = true
        reject { PioneerDebugUpdateGuard.requireIdle(idleState()) }
    }

    @Test fun uploadLeaseRejectsAnotherUploadAndCacheCleanup() {
        begin()
        reject { begin() }
        reject { PioneerDebugUpdateGuard.requireStartAllowed() }
        var cleaned = false
        reject { PioneerDebugUpdateGuard.withCacheMaintenance { cleaned = true } }
        assertFalse(cleaned)
        assertTrue(PioneerDebugUpdateGuard.isBusy())
    }

    @Test fun cacheMaintenanceHoldsTheSameLeaseUntilItsWorkFinishes() {
        PioneerDebugUpdateGuard.withCacheMaintenance {
            assertTrue(PioneerDebugUpdateGuard.isBusy())
            reject { begin() }
            reject { PioneerDebugUpdateGuard.requireStartAllowed() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(PioneerDebugUpdateGuard.isBusy())
        begin()
        assertTrue(PioneerDebugUpdateGuard.isBusy())
    }

    @Test fun installerMarkerExistsBeforeHandoffAndKeepsApkThroughCacheMaintenance() {
        val apk = files.newFile("update.apk").apply { writeText("validated APK") }
        val marker = File(apk.path + ".installing")
        val token = begin()
        PioneerDebugUpdateGuard.handoff(token, apk) { }
        assertFalse(marker.exists())
        PioneerDebugUpdateGuard.install(apk) {
            assertTrue(marker.isFile)
            // File timestamps use the host clock; align the new marker with Robolectric time.
            assertTrue(marker.setLastModified(System.currentTimeMillis()))
            assertFalse(PioneerDebugUpdateGuard.canDelete(apk))
        }
        assertFalse(PioneerDebugUpdateGuard.isBusy())
        PioneerDebugUpdateGuard.withCacheMaintenance {
            if (PioneerDebugUpdateGuard.canDelete(apk)) apk.delete()
        }
        assertTrue(apk.isFile)
        assertTrue(marker.isFile)
        assertTrue(marker.setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25)))
        assertTrue(PioneerDebugUpdateGuard.canDelete(apk))
    }

    @Test fun staleReleaseAndOldHandoffTimeoutCannotUnlockNewUpload() {
        val old = begin()
        val apk = files.newFile("previous.apk")
        PioneerDebugUpdateGuard.handoff(old, apk) { }
        PioneerDebugUpdateGuard.install(apk) { }
        val current = begin()
        assertNotSame(old, current)
        PioneerDebugUpdateGuard.release(old)
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)
        assertTrue(PioneerDebugUpdateGuard.isBusy())
        reject { begin() }
        PioneerDebugUpdateGuard.release(current)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(PioneerDebugUpdateGuard.isBusy())
    }
}
