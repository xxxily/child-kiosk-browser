package site.anzz.childkiosk.util.filter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FilterRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearRepositoryState()
    }

    @After
    fun tearDown() {
        clearRepositoryState()
    }

    @Test
    fun presetRoundTripsPreserveCustomSubscriptionsMetadataAndChoices() {
        FilterRepository.setPreset(context, FilterPreset.STANDARD_CHILD)
        val custom = FilterRepository.addCustomSubscription(
            context,
            "家长订阅",
            "https://filters.example.test/parent.txt"
        )
        FilterRepository.setSubscriptionEnabled(context, custom.id, false)
        val localId = "local-child-supplemental"
        val updatedLocal = FilterRepository.updateSubscription(context, localId)

        FilterRepository.setPreset(context, FilterPreset.LIGHT)
        val light = FilterRepository.getSettings(context)
        assertFalse(light.subscriptions.single { it.id == custom.id }.enabled)
        assertEquals(
            updatedLocal.lastUpdatedAt,
            light.subscriptions.single { it.id == localId }.lastUpdatedAt
        )
        val lightChoices = light.subscriptions.associate { it.id to it.enabled }

        FilterRepository.setPreset(context, FilterPreset.CUSTOM)
        val customPreset = FilterRepository.getSettings(context)
        assertEquals(lightChoices, customPreset.subscriptions.associate { it.id to it.enabled })

        FilterRepository.setPreset(context, FilterPreset.STRONG)
        val strong = FilterRepository.getSettings(context)
        val preserved = strong.subscriptions.single { it.id == custom.id }
        assertEquals("家长订阅", preserved.title)
        assertEquals("https://filters.example.test/parent.txt", preserved.subscriptionUrl)
        assertFalse(preserved.enabled)
    }

    @Test
    fun concurrentPresetAndAddMutationsDoNotLoseCustomEntries() {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val urls = (0 until 30).map { "https://filters.example.test/$it.txt" }
        val addFuture = executor.submit {
            start.await()
            urls.forEachIndexed { index, url ->
                FilterRepository.addCustomSubscription(context, "custom-$index", url)
            }
        }
        val presetFuture = executor.submit {
            start.await()
            repeat(30) { index ->
                FilterRepository.setPreset(
                    context,
                    if (index % 2 == 0) FilterPreset.LIGHT else FilterPreset.STRONG
                )
            }
        }
        start.countDown()
        addFuture.get(10, TimeUnit.SECONDS)
        presetFuture.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        val customUrls = FilterRepository.getSettings(context).subscriptions
            .filter { it.id.startsWith("custom-") }
            .map { it.subscriptionUrl }
            .toSet()
        assertEquals(urls.toSet(), customUrls)
    }

    @Test
    fun generationPublicationIsImmutableAndRetainsPreviousGeneration() {
        val root = File(context.cacheDir, "filter-store-${UUID.randomUUID()}")
        val store = FilterSubscriptionStore.forDirectory(root)
        val subscription = FilterCatalog.customSubscription(
            "test",
            "https://filters.example.test/list.txt"
        )

        val first = store.stage(subscription.id, "||first.example^\n".toByteArray())
        store.publish(first)
        val firstPointer = subscription.copy(contentGeneration = first.generation)
        assertEquals("||first.example^\n", store.readRules(firstPointer))

        val second = store.stage(subscription.id, "||second.example^\n".toByteArray())
        store.publish(second)
        assertEquals("||first.example^\n", store.readRules(firstPointer))
        assertEquals(
            "||second.example^\n",
            store.readRules(subscription.copy(contentGeneration = second.generation))
        )

        val third = store.stage(subscription.id, "||third.example^\n".toByteArray())
        store.publish(third)
        store.cleanupAfterCommit(subscription.id, third.generation, second.generation)

        assertFalse(store.generationFileForTest(subscription.id, first.generation).exists())
        assertTrue(store.generationFileForTest(subscription.id, second.generation).isFile)
        assertTrue(store.generationFileForTest(subscription.id, third.generation).isFile)
        store.cleanupAfterCommit(subscription.id, third.generation, third.generation)
        assertTrue(store.generationFileForTest(subscription.id, second.generation).isFile)
        root.deleteRecursively()
    }

    @Test
    fun downloaderValidationRejectsEmptyHtmlInvalidUtf8AndStreamingOverflow() {
        assertFails<IllegalStateException> {
            FilterSubscriptionDownloader.decodeAndValidate(ByteArray(0))
        }
        assertFails<IllegalStateException> {
            FilterSubscriptionDownloader.decodeAndValidate("<html><body>error</body></html>")
        }
        assertFails<IllegalStateException> {
            FilterSubscriptionDownloader.decodeAndValidate("||ads.example^", "text/html; charset=utf-8")
        }
        assertFails<IllegalStateException> {
            FilterSubscriptionDownloader.decodeAndValidate(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFails<IllegalStateException> {
            FilterSubscriptionDownloader.readLimited(
                ByteArrayInputStream("12345".toByteArray()),
                maxBytes = 4L
            )
        }
        assertEquals(
            "||ads.example^",
            FilterSubscriptionDownloader.readLimitedForTest("||ads.example^", 64L)
        )
    }

    @Test
    fun customRulesUseUtf8ByteLimit() {
        FilterRepository.setCustomRules(context, "a".repeat(FilterRepository.MAX_CUSTOM_RULE_BYTES))
        assertEquals(
            FilterRepository.MAX_CUSTOM_RULE_BYTES,
            FilterRepository.getSettings(context).customRules.toByteArray().size
        )

        assertFails<IllegalArgumentException> {
            FilterRepository.setCustomRules(
                context,
                "中".repeat(FilterRepository.MAX_CUSTOM_RULE_BYTES / 3 + 1)
            )
        }
    }

    @Test
    fun longestSiteOverrideWinsWithoutChangingCompiledEngine() {
        val broad = SiteFilterOverride(host = "example.com", networkDisabled = true)
        val specific = SiteFilterOverride(host = "child.example.com", cosmeticDisabled = true)
        val base = FilterRuntimeSnapshot(
            enabled = true,
            preset = FilterPreset.CUSTOM.storageValue,
            customRules = "||ads.example^",
            enabledSubscriptionIds = emptyList(),
            siteOverrides = listOf(broad, specific)
        )
        val changedPolicy = base.copy(
            siteOverrides = listOf(broad.copy(networkDisabled = false), specific)
        )

        assertEquals(specific, FilterRepository.siteOverrideFor(base, "www.child.example.com"))
        FilterRepository.invalidate()
        val first = FilterRepository.getEngine(base)
        val second = FilterRepository.getEngine(changedPolicy)
        assertSame(first, second)
    }

    @Test
    fun contentGenerationChangesEngineKeyAndConcurrentBuildsAreSingleFlight() {
        val firstRules = (0 until 4_000).joinToString("\n") { "||ads$it.example^" }
        val secondRules = "$firstRules\n||new-generation.example^"
        val subscription = FilterCatalog.customSubscription(
            "test",
            "https://filters.example.test/list.txt"
        ).copy(
            enabled = true,
        )
        val store = FilterSubscriptionStore(context)
        val firstContent = store.stage(subscription.id, firstRules.toByteArray())
        store.publish(firstContent)
        val secondContent = store.stage(subscription.id, secondRules.toByteArray())
        store.publish(secondContent)
        val firstSubscription = subscription.copy(contentGeneration = firstContent.generation)
        val firstSnapshot = FilterSettings(
            enabled = true,
            preset = FilterPreset.CUSTOM,
            customRules = "",
            subscriptions = listOf(firstSubscription),
            siteOverrides = emptyList()
        ).toRuntimeSnapshot()
        val secondSnapshot = firstSnapshot.copy(
            subscriptions = listOf(subscription.copy(contentGeneration = secondContent.generation))
        )

        FilterRepository.invalidate()
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val futures = (0 until 6).map {
            executor.submit<FilterEngine> {
                start.await()
                FilterRepository.getEngine(context, firstSnapshot)
            }
        }
        start.countDown()
        val engines = futures.map { it.get(10, TimeUnit.SECONDS) }
        executor.shutdownNow()
        engines.forEach { assertSame(engines.first(), it) }

        val nextGeneration = FilterRepository.getEngine(context, secondSnapshot)
        assertNotSame(engines.first(), nextGeneration)

        val fallbackSnapshot = FilterRepository.bundledFallbackSnapshot(firstSnapshot)
        assertTrue(fallbackSnapshot.customRules.isEmpty())
        assertTrue(fallbackSnapshot.subscriptions.all { it.contentGeneration.isEmpty() })
        val primaryNamespaceEngine = FilterRepository.getEngine(fallbackSnapshot)
        val fallbackNamespaceEngine = FilterRepository.getBundledFallbackEngine(firstSnapshot)
        assertNotSame(primaryNamespaceEngine, fallbackNamespaceEngine)
    }

    private fun clearRepositoryState() {
        context.getSharedPreferences("kiosk_filter_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, "filter_subscriptions").deleteRecursively()
        FilterRepository.invalidate()
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
            return error
        }
    }
}
