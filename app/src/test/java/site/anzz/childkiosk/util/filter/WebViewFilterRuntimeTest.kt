package site.anzz.childkiosk.util.filter

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewFilterRuntimeTest {

    @Test
    fun prepareReturnsImmediatelyAndKeepsBundledHandleUntilReady() {
        withExecutors { buildExecutor, scheduler ->
            val releaseBuild = CountDownLatch(1)
            val ready = CountDownLatch(1)
            val bundledEngine = engine("||bundled.example^")
            val loadedEngine = engine("||loaded.example^")
            val bundledSnapshot = snapshot("BUNDLED")
            val requestedSnapshot = snapshot("REQUESTED").copy(
                siteOverrides = listOf(SiteFilterOverride("requested.example", temporaryAllowUntil = Long.MAX_VALUE))
            )
            val runtime = WebViewFilterRuntime(
                engineLoader = WebViewFilterEngineLoader {
                    assertTrue(releaseBuild.await(2, TimeUnit.SECONDS))
                    loadedEngine
                },
                bundledSnapshot = bundledSnapshot,
                bundledEngine = bundledEngine,
                buildExecutor = buildExecutor,
                scheduler = scheduler,
                timeoutMs = 1_000L
            )

            val startedAt = System.nanoTime()
            runtime.prepare(requestedSnapshot) { state ->
                if (state.status == WebViewFilterRuntimeStatus.READY) ready.countDown()
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue("prepare blocked caller for ${'$'}elapsedMs ms", elapsedMs < 250L)
            assertEquals(WebViewFilterRuntimeStatus.PREPARING, runtime.currentHandle().status)
            assertSame(bundledEngine, runtime.currentHandle().engine)
            assertEquals("BUNDLED", runtime.currentHandle().snapshot.preset)
            assertEquals(requestedSnapshot.siteOverrides, runtime.currentHandle().snapshot.siteOverrides)
            assertEquals("REQUESTED", runtime.currentHandle().requestedSnapshot.preset)

            releaseBuild.countDown()
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertEquals(WebViewFilterRuntimeStatus.READY, runtime.currentHandle().status)
            assertSame(loadedEngine, runtime.currentHandle().engine)
            assertEquals(requestedSnapshot, runtime.currentHandle().snapshot)
        }
    }

    @Test
    fun failedReplacementFallsBackToLastKnownGood() {
        withExecutors { buildExecutor, scheduler ->
            val firstEngine = engine("||first.example^")
            val bundledEngine = engine("||bundled.example^")
            val firstReady = CountDownLatch(1)
            val degraded = CountDownLatch(1)
            val runtime = WebViewFilterRuntime(
                engineLoader = WebViewFilterEngineLoader { requested ->
                    if (requested.preset == "FIRST") firstEngine else error("replacement failed\nwith details")
                },
                bundledSnapshot = snapshot("BUNDLED"),
                bundledEngine = bundledEngine,
                buildExecutor = buildExecutor,
                scheduler = scheduler,
                timeoutMs = 1_000L
            )

            runtime.prepare(snapshot("FIRST")) { state ->
                if (state.status == WebViewFilterRuntimeStatus.READY) firstReady.countDown()
            }
            assertTrue(firstReady.await(2, TimeUnit.SECONDS))

            val secondSnapshot = snapshot("SECOND").copy(
                siteOverrides = listOf(SiteFilterOverride("second.example", networkDisabled = true))
            )
            runtime.prepare(secondSnapshot) { state ->
                if (state.status == WebViewFilterRuntimeStatus.DEGRADED_LKG) degraded.countDown()
            }
            assertTrue(degraded.await(2, TimeUnit.SECONDS))

            val handle = runtime.currentHandle()
            assertEquals(WebViewFilterRuntimeStatus.DEGRADED_LKG, handle.status)
            assertSame(firstEngine, handle.engine)
            assertEquals("FIRST", handle.snapshot.preset)
            assertEquals(secondSnapshot.siteOverrides, handle.snapshot.siteOverrides)
            assertEquals("SECOND", handle.requestedSnapshot.preset)
            assertEquals("replacement failed with details", handle.reason)
        }
    }

    @Test
    fun timeoutPublishesBundledFallbackWithinBound() {
        withExecutors { buildExecutor, scheduler ->
            val releaseBuild = CountDownLatch(1)
            val degraded = CountDownLatch(1)
            val bundledEngine = engine("||bundled.example^")
            val runtime = WebViewFilterRuntime(
                engineLoader = WebViewFilterEngineLoader {
                    releaseBuild.await(2, TimeUnit.SECONDS)
                    engine("||late.example^")
                },
                bundledSnapshot = snapshot("BUNDLED"),
                bundledEngine = bundledEngine,
                buildExecutor = buildExecutor,
                scheduler = scheduler,
                timeoutMs = 40L
            )

            val requestedSnapshot = snapshot("REQUESTED").copy(
                siteOverrides = listOf(SiteFilterOverride("temporary.example", temporaryAllowUntil = Long.MAX_VALUE))
            )
            runtime.prepare(requestedSnapshot) { state ->
                if (state.status == WebViewFilterRuntimeStatus.DEGRADED_BUNDLED) degraded.countDown()
            }

            assertTrue(degraded.await(1, TimeUnit.SECONDS))
            val handle = runtime.currentHandle()
            assertEquals(WebViewFilterRuntimeStatus.DEGRADED_BUNDLED, handle.status)
            assertSame(bundledEngine, handle.engine)
            assertEquals("BUNDLED", handle.snapshot.preset)
            assertEquals(requestedSnapshot.siteOverrides, handle.snapshot.siteOverrides)
            assertEquals("REQUESTED", handle.requestedSnapshot.preset)
            assertTrue(handle.reason.contains("timed out"))
            releaseBuild.countDown()
        }
    }

    @Test
    fun staleBuildCannotReplaceNewerGeneration() {
        withExecutors(threads = 2) { buildExecutor, scheduler ->
            val oldStarted = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val oldFinished = CountDownLatch(1)
            val newReady = CountDownLatch(1)
            val oldEngine = engine("||old.example^")
            val newEngine = engine("||new.example^")
            val runtime = WebViewFilterRuntime(
                engineLoader = WebViewFilterEngineLoader { requested ->
                    if (requested.preset == "OLD") {
                        oldStarted.countDown()
                        releaseOld.await(2, TimeUnit.SECONDS)
                        oldFinished.countDown()
                        oldEngine
                    } else {
                        newEngine
                    }
                },
                bundledSnapshot = snapshot("BUNDLED"),
                bundledEngine = engine("||bundled.example^"),
                buildExecutor = buildExecutor,
                scheduler = scheduler,
                timeoutMs = 1_000L
            )

            runtime.prepare(snapshot("OLD"))
            assertTrue(oldStarted.await(1, TimeUnit.SECONDS))
            val newGeneration = runtime.prepare(snapshot("NEW")) { state ->
                if (state.status == WebViewFilterRuntimeStatus.READY && state.snapshot.preset == "NEW") {
                    newReady.countDown()
                }
            }
            assertTrue(newReady.await(1, TimeUnit.SECONDS))

            releaseOld.countDown()
            assertTrue(oldFinished.await(1, TimeUnit.SECONDS))
            Thread.sleep(30L)

            val handle = runtime.currentHandle()
            assertEquals(newGeneration, handle.generation)
            assertEquals("NEW", handle.snapshot.preset)
            assertSame(newEngine, handle.engine)
        }
    }

    private fun snapshot(name: String): FilterRuntimeSnapshot {
        return FilterRuntimeSnapshot.default().copy(
            enabled = true,
            preset = name,
            enabledSubscriptionIds = emptyList(),
            subscriptions = emptyList()
        )
    }

    private fun engine(rule: String): FilterEngine {
        return FilterEngine.build(listOf(FilterRuleSource(rule, rule, rule)))
    }

    private fun withExecutors(
        threads: Int = 1,
        block: (java.util.concurrent.ExecutorService, java.util.concurrent.ScheduledExecutorService) -> Unit
    ) {
        val buildExecutor = Executors.newFixedThreadPool(threads)
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            block(buildExecutor, scheduler)
        } finally {
            buildExecutor.shutdownNow()
            scheduler.shutdownNow()
            buildExecutor.awaitTermination(1, TimeUnit.SECONDS)
            scheduler.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
