package site.anzz.childkiosk.performance

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HighPerformanceRuntimePublisherTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HighPerformanceRuntimePublisher.clearCachedSnapshotForTests()
        HighPerformanceRuntimeStore(context).invalidate()
    }

    @After
    fun tearDown() {
        HighPerformanceRuntimePublisher.clearCachedSnapshotForTests()
        HighPerformanceRuntimeStore(context).invalidate()
        File(context.filesDir, HIGH_PERFORMANCE_RUNTIME_STATUS_FILE_NAME).delete()
    }

    @Test
    fun writeFailureInvalidatesOldSnapshotCachesDisabledAndSignalsStop() = runBlocking {
        val oldSnapshot = enabledSnapshot(configVersion = 1L)
        HighPerformanceRuntimeStore(context).write(oldSnapshot)
        HighPerformanceRuntimePublisher.readPublishedSnapshot(context)
        val store = FailingStore()
        val broadcasts = mutableListOf<Intent>()
        val publisher = HighPerformanceRuntimePublisher(
            context = context,
            store = store,
            broadcastSender = { intent -> broadcasts += Intent(intent); true }
        )

        val result = publisher.publish(enabledSnapshot(configVersion = 2L))

        assertFalse(result.succeeded)
        assertTrue(store.invalidated)
        val cached = HighPerformanceRuntimePublisher.readPublishedSnapshot(context, 2L)
        assertEquals(2L, cached.configVersion)
        assertFalse(cached.enabled)
        assertTrue(cached.rules.isEmpty())
        val stop = broadcasts.single { it.action == ACTION_HIGH_PERFORMANCE_STOP_ALL }
        assertEquals(HIGH_PERFORMANCE_PUBLICATION_FAILED_REASON, stop.getStringExtra(EXTRA_HIGH_PERFORMANCE_STOP_REASON))
        assertEquals(2L, stop.getLongExtra(EXTRA_HIGH_PERFORMANCE_CONFIG_VERSION, -1L))
    }

    @Test
    fun updateBroadcastFailurePersistsAndCachesDisabledTombstoneThenStops() = runBlocking {
        val broadcasts = mutableListOf<Intent>()
        val publisher = HighPerformanceRuntimePublisher(
            context = context,
            broadcastSender = { intent ->
                broadcasts += Intent(intent)
                intent.action != ACTION_HIGH_PERFORMANCE_CONFIG_UPDATED
            }
        )

        val result = publisher.publish(enabledSnapshot(configVersion = 3L))

        assertFalse(result.succeeded)
        assertTrue(result.snapshotWritten)
        assertFalse(result.configUpdateBroadcastSent)
        assertTrue(result.stopBroadcastSent)
        val disk = HighPerformanceRuntimePublisher.readPublishedSnapshotFromDisk(context, 3L)
        assertEquals(3L, disk.configVersion)
        assertFalse(disk.enabled)
        assertTrue(disk.rules.isEmpty())
        val cache = HighPerformanceRuntimePublisher.readPublishedSnapshot(context, 3L)
        assertEquals(disk, cache)
        assertEquals(
            HIGH_PERFORMANCE_PUBLICATION_FAILED_REASON,
            broadcasts.last().getStringExtra(EXTRA_HIGH_PERFORMANCE_STOP_REASON)
        )
    }

    @Test
    fun tombstoneWriteFailureInvalidatesDiskButKeepsCacheDisabled() = runBlocking {
        val store = TombstoneFailingStore(context)
        val publisher = HighPerformanceRuntimePublisher(
            context = context,
            store = store,
            broadcastSender = { intent -> intent.action != ACTION_HIGH_PERFORMANCE_CONFIG_UPDATED }
        )

        val result = publisher.publish(enabledSnapshot(configVersion = 6L))

        assertFalse(result.succeeded)
        assertTrue(result.errors.any { it.startsWith("tombstone_write:") })
        assertTrue(store.invalidated)
        assertFalse(File(context.filesDir, HighPerformanceRuntimeStore.RUNTIME_CONFIG_FILE_NAME).exists())
        val cached = HighPerformanceRuntimePublisher.readPublishedSnapshot(context, 6L)
        assertEquals(6L, cached.configVersion)
        assertFalse(cached.enabled)
        HighPerformanceRuntimePublisher.clearCachedSnapshotForTests()
        val diskFallback = HighPerformanceRuntimePublisher.readPublishedSnapshotFromDisk(context, 6L)
        assertEquals(6L, diskFallback.configVersion)
        assertFalse(diskFallback.enabled)
    }

    @Test
    fun clearDiagnosticsSynchronouslyDeletesPersistedStatusWhenWebViewIsAbsent() {
        val statusFile = File(context.filesDir, HIGH_PERFORMANCE_RUNTIME_STATUS_FILE_NAME)
        statusFile.writeText("stale runtime status")

        val sent = HighPerformanceRuntimePublisher.requestClearDiagnostics(context)

        assertTrue(sent)
        assertFalse(statusFile.exists())
        assertEquals(null, HighPerformanceRuntimeStatusStore.read(context).status)
    }

    @Test
    fun storeReadRejectsOlderAtomicSnapshotAtRequestedVersion() {
        val store = HighPerformanceRuntimeStore(context)
        store.write(enabledSnapshot(configVersion = 4L))

        val parsed = store.read(minimumConfigVersion = 5L)

        assertEquals(5L, parsed.configVersion)
        assertFalse(parsed.enabled)
        assertTrue(parsed.rules.isEmpty())
    }

    private fun enabledSnapshot(configVersion: Long): HighPerformanceRuntimeSnapshot {
        return HighPerformanceRuntimeSnapshot(
            configVersion = configVersion,
            enabled = true,
            generatedAt = 10L,
            rules = listOf(
                HighPerformanceRuntimeRule(
                    id = "one",
                    origin = "https://example.com",
                    enabled = true,
                    includeSubdomains = false,
                    displayName = null,
                    updatedAt = 10L
                )
            )
        )
    }

    private class FailingStore : HighPerformanceSnapshotStore {
        var invalidated = false

        override fun write(snapshot: HighPerformanceRuntimeSnapshot) {
            throw IOException("test failure")
        }

        override fun invalidate() {
            invalidated = true
            HighPerformanceRuntimeStore(
                ApplicationProvider.getApplicationContext<Context>()
            ).invalidate()
        }
    }

    private class TombstoneFailingStore(context: Context) : HighPerformanceSnapshotStore {
        private val delegate = HighPerformanceRuntimeStore(context)
        var invalidated = false

        override fun write(snapshot: HighPerformanceRuntimeSnapshot) {
            if (!snapshot.enabled) throw IOException("test tombstone failure")
            delegate.write(snapshot)
        }

        override fun invalidate() {
            invalidated = true
            delegate.invalidate()
        }
    }
}
