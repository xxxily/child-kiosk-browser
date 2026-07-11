package site.anzz.childkiosk.util.filter

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class StagedFilterSubscription(
    val subscriptionId: String,
    val generation: String,
    val byteCount: Long,
    val file: File
)

internal class FilterSubscriptionStore private constructor(
    private val rootDirectory: File
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, RULE_DIRECTORY)
    )

    fun createStagingFile(subscriptionId: String): File {
        val stagingDirectory = File(rootDirectory, STAGING_DIRECTORY).apply { mkdirs() }
        return File(
            stagingDirectory,
            "${safeName(subscriptionId)}-${UUID.randomUUID()}$STAGING_SUFFIX"
        )
    }

    fun stage(subscriptionId: String, bytes: ByteArray): StagedFilterSubscription {
        require(bytes.isNotEmpty()) { "订阅内容为空" }
        require(bytes.size.toLong() <= FilterSubscriptionDownloader.MAX_SOURCE_BYTES) {
            "订阅超过 15MB 限制"
        }
        val file = createStagingFile(subscriptionId)
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        return inspectStaging(subscriptionId, file)
    }

    fun inspectStaging(subscriptionId: String, file: File): StagedFilterSubscription {
        require(file.isFile && file.length() > 0L) { "订阅内容为空" }
        require(file.length() <= FilterSubscriptionDownloader.MAX_SOURCE_BYTES) {
            "订阅超过 15MB 限制"
        }
        return StagedFilterSubscription(
            subscriptionId = subscriptionId,
            generation = sha256(file),
            byteCount = file.length(),
            file = file
        )
    }

    fun publish(staged: StagedFilterSubscription): File {
        require(staged.file.isFile) { "订阅候选文件不存在" }
        require(staged.file.length() == staged.byteCount) { "订阅候选文件长度不一致" }
        require(sha256(staged.file) == staged.generation) { "订阅候选文件校验失败" }
        return synchronized(FILE_MUTATION_LOCK) {
            val target = generationFile(staged.subscriptionId, staged.generation)
            target.parentFile?.mkdirs()
            if (target.isFile) {
                require(target.length() == staged.byteCount && sha256(target) == staged.generation) {
                    "订阅 generation 文件校验失败"
                }
                staged.file.delete()
                return@synchronized target
            }

            val atomicFile = AtomicFile(target)
            val output = atomicFile.startWrite()
            try {
                FileInputStream(staged.file).use { input -> input.copyTo(output) }
                atomicFile.finishWrite(output)
            } catch (error: Exception) {
                atomicFile.failWrite(output)
                throw error
            }
            staged.file.delete()
            require(target.isFile && target.length() == staged.byteCount && sha256(target) == staged.generation) {
                target.delete()
                "订阅 generation 发布校验失败"
            }
            target
        }
    }

    fun discard(staged: StagedFilterSubscription?) {
        staged?.file?.delete()
    }

    fun readRules(subscription: FilterSubscription): String? {
        val file = contentFile(subscription) ?: return null
        return runCatching {
            val bytes = file.readBytes()
            if (
                subscription.contentGeneration.isNotBlank() &&
                sha256(bytes) != subscription.contentGeneration
            ) {
                return@runCatching null
            }
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }.getOrNull()
    }

    fun contentSize(subscription: FilterSubscription): Long {
        return contentFile(subscription)?.takeIf { it.isFile }?.length()
            ?: subscription.bundledRules.toByteArray(Charsets.UTF_8).size.toLong()
    }

    fun generationFileSize(subscriptionId: String, generation: String): Long? {
        return generationFileOrNull(subscriptionId, generation)
            ?.takeIf { it.isFile }
            ?.length()
    }

    fun cleanupAfterCommit(
        subscriptionId: String,
        currentGeneration: String,
        previousGeneration: String
    ) {
        synchronized(FILE_MUTATION_LOCK) {
            val directory = subscriptionDirectory(subscriptionId)
            if (!directory.isDirectory) return
            if (currentGeneration == previousGeneration) {
                cleanupStaging()
                return@synchronized
            }
            val keep = setOf(currentGeneration, previousGeneration).filter { isGeneration(it) }.toSet()
            directory.listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension == GENERATION_EXTENSION }
                .forEach { file ->
                    val generation = file.nameWithoutExtension
                    if (generation !in keep) file.delete()
                }
            if (directory.listFiles().isNullOrEmpty()) directory.delete()
            cleanupStaging()
        }
    }

    fun deleteGeneration(subscriptionId: String, generation: String) {
        synchronized(FILE_MUTATION_LOCK) {
            generationFileOrNull(subscriptionId, generation)?.delete()
            val directory = subscriptionDirectory(subscriptionId)
            if (directory.listFiles().isNullOrEmpty()) directory.delete()
        }
    }

    fun deleteSubscription(subscriptionId: String) {
        synchronized(FILE_MUTATION_LOCK) {
            legacyFile(subscriptionId).delete()
            subscriptionDirectory(subscriptionId).deleteRecursively()
        }
    }

    internal fun generationFileForTest(subscriptionId: String, generation: String): File {
        return generationFile(subscriptionId, generation)
    }

    private fun contentFile(subscription: FilterSubscription): File? {
        if (subscription.contentGeneration.isNotBlank()) {
            return generationFileOrNull(subscription.id, subscription.contentGeneration)
                ?.takeIf { it.isFile }
        }
        return legacyFile(subscription.id).takeIf { it.isFile }
    }

    private fun cleanupStaging() {
        val threshold = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        val directory = File(rootDirectory, STAGING_DIRECTORY)
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < threshold) file.delete()
        }
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
    }

    private fun generationFile(subscriptionId: String, generation: String): File {
        require(isGeneration(generation)) { "订阅 generation 无效" }
        return File(subscriptionDirectory(subscriptionId), "$generation.$GENERATION_EXTENSION")
    }

    private fun generationFileOrNull(subscriptionId: String, generation: String): File? {
        if (!isGeneration(generation)) return null
        return File(subscriptionDirectory(subscriptionId), "$generation.$GENERATION_EXTENSION")
    }

    private fun subscriptionDirectory(subscriptionId: String): File {
        return File(rootDirectory, safeName(subscriptionId))
    }

    private fun legacyFile(subscriptionId: String): File {
        return File(rootDirectory, "${safeName(subscriptionId)}.txt")
    }

    companion object {
        private const val RULE_DIRECTORY = "filter_subscriptions"
        private const val STAGING_DIRECTORY = ".staging"
        private const val STAGING_SUFFIX = ".tmp"
        private const val GENERATION_EXTENSION = "rules"
        private const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private val GENERATION_PATTERN = Regex("^[a-f0-9]{64}$")
        private val FILE_MUTATION_LOCK = Any()

        internal fun forDirectory(directory: File): FilterSubscriptionStore {
            return FilterSubscriptionStore(directory)
        }

        internal fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun isGeneration(value: String): Boolean = GENERATION_PATTERN.matches(value)

        private fun safeName(id: String): String {
            return id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        }
    }
}
