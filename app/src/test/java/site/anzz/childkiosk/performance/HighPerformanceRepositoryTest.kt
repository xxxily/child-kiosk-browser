package site.anzz.childkiosk.performance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import site.anzz.childkiosk.data.AppDatabase

@RunWith(RobolectricTestRunner::class)
class HighPerformanceRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var publisher: RecordingPublisher
    private lateinit var repository: HighPerformanceConfigRepository
    private var now = 100L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        publisher = RecordingPublisher()
        repository = HighPerformanceConfigRepository(
            context = context,
            database = database,
            publisher = publisher,
            now = { now++ },
            newRuleId = { "rule-${now}" }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun effectiveMutationsIncrementVersionAndPublishAfterCommit() = runBlocking {
        val initial = repository.getPersistedState()
        assertEquals(0L, initial.configVersion)
        assertFalse(initial.enabled)

        val enabled = repository.acknowledgeRiskAndEnable()
        assertTrue(enabled.changed)
        assertEquals(1L, enabled.state.configVersion)
        assertTrue(enabled.state.enabled)

        val added = repository.addOrUpdateManualRule("https://Example.com:443/")
        assertTrue(added.changed)
        assertEquals(2L, added.state.configVersion)
        assertEquals("https://example.com", added.state.rules.single().origin)

        val duplicate = repository.addOrUpdateManualRule("https://example.com")
        assertFalse(duplicate.changed)
        assertEquals(2L, duplicate.state.configVersion)
        assertEquals(listOf(1L, 2L), publisher.snapshots.map { it.configVersion })

        val disabled = repository.setEnabled(false)
        assertEquals(3L, disabled.state.configVersion)
        assertFalse(disabled.snapshot.enabled)
        assertEquals(HighPerformanceStopReason.CONFIG_DISABLED, publisher.stopReasons.last())
    }

    @Test
    fun enableNeedsRiskAcknowledgementAndHttpNeedsExplicitConfirmation() = runBlocking {
        assertFails<IllegalArgumentException> { repository.setEnabled(true) }
        assertFails<IllegalArgumentException> {
            repository.addOrUpdateManualRule("http://192.168.1.10")
        }

        val confirmed = repository.addOrUpdateManualRule(
            rawOrigin = "http://192.168.1.10",
            insecureHttpConfirmed = true
        )
        assertEquals("http://192.168.1.10", confirmed.state.rules.single().origin)
        assertFails<IllegalArgumentException> {
            repository.setRuleIncludeSubdomains(confirmed.state.rules.single().id, true)
        }
        Unit
    }

    @Test
    fun publicationFailureIsSurfacedAfterDatabaseCommit() = runBlocking {
        publisher.nextPublication = failure("snapshot_write:IOException")

        val error = assertFails<HighPerformancePublicationException> {
            repository.acknowledgeRiskAndEnable()
        }

        assertEquals(listOf("snapshot_write:IOException"), error.publication.errors)
        val committed = repository.getPersistedState()
        assertTrue(committed.enabled)
        assertEquals(1L, committed.configVersion)
    }

    @Test
    fun publishCurrentAndStopFailuresAreVisibleAndRetryable() = runBlocking {
        publisher.nextPublication = failure("config_broadcast:failed")
        assertFails<HighPerformancePublicationException> { repository.publishCurrent() }

        publisher.nextPublication = publisher.success(snapshotWriteAttempted = true)
        assertTrue(repository.publishCurrent().succeeded)

        publisher.nextStop = failure("stop_broadcast:failed")
        assertFails<HighPerformancePublicationException> { repository.requestStopAll() }
        Unit
    }

    private suspend inline fun <reified T : Throwable> assertFails(crossinline block: suspend () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
            return error
        }
    }

    private class RecordingPublisher : HighPerformanceSnapshotPublisher {
        val snapshots = mutableListOf<HighPerformanceRuntimeSnapshot>()
        val stopReasons = mutableListOf<String?>()
        var nextPublication: HighPerformancePublicationResult? = null
        var nextStop: HighPerformancePublicationResult? = null

        override suspend fun publish(
            snapshot: HighPerformanceRuntimeSnapshot,
            stopReason: String?
        ): HighPerformancePublicationResult {
            snapshots += snapshot
            stopReasons += stopReason
            return nextPublication.also { nextPublication = null }
                ?: success(snapshotWriteAttempted = true)
        }

        override suspend fun requestStop(
            configVersion: Long,
            reason: String
        ): HighPerformancePublicationResult {
            stopReasons += reason
            return nextStop.also { nextStop = null }
                ?: success(snapshotWriteAttempted = false)
        }

        fun success(snapshotWriteAttempted: Boolean): HighPerformancePublicationResult {
            return HighPerformancePublicationResult(
                snapshotWriteAttempted = snapshotWriteAttempted,
                snapshotWritten = snapshotWriteAttempted,
                configUpdateBroadcastSent = snapshotWriteAttempted,
                stopBroadcastSent = false,
                errors = emptyList()
            )
        }

        fun failure(error: String): HighPerformancePublicationResult {
            return HighPerformancePublicationResult(
                snapshotWriteAttempted = true,
                snapshotWritten = false,
                configUpdateBroadcastSent = false,
                stopBroadcastSent = false,
                errors = listOf(error)
            )
        }
    }

    private fun failure(error: String): HighPerformancePublicationResult = publisher.failure(error)
}
