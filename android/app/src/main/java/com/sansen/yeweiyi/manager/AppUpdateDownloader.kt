package com.sansen.yeweiyi.manager

import com.sansen.yeweiyi.logic.update.AppUpdateCandidate
import com.sansen.yeweiyi.logic.update.AppUpdateStatus
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.security.MessageDigest

private data class AppUpdateCheckpoint(val key: String, val committedBytes: Long)

/** Caller provides a private per-device directory and runs this outside UI and MQTT threads. */
class AppUpdateDownloader(private val directory: File) {
    private val chunkSize = 1024 * 1024

    @Synchronized
    fun download(client: AppUpdateHttpClient, target: AppUpdateCandidate, currentCode: Int, progress: (Long, Long) -> Unit): File {
        require(target.sizeBytes in 1..com.sansen.yeweiyi.logic.update.AppUpdateContract.MAX_APK_BYTES)
        verifyTarget(client, target, currentCode)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("UPDATE_DIRECTORY_UNAVAILABLE")
        val key = AppUpdateHttpClient.digest((client.scope + "|" + target.releaseId + "|" + target.tbPackageId + "|" +
            target.assignmentRevision + "|" + target.versionCode + "|" + target.sha256 + "|" + target.sizeBytes + "|" + chunkSize).toByteArray(Charsets.UTF_8))
        val state = AtomicJsonFile(File(directory, "checkpoint.json"), AppUpdateCheckpoint::class.java, "AppUpdate")
        val previous: AppUpdateCheckpoint? = state.read()
        val partial = File(directory, "download.part")
        var committed = previous?.takeIf { it.key == key && it.committedBytes in 0..target.sizeBytes &&
            (it.committedBytes % chunkSize == 0L || it.committedBytes == target.sizeBytes) }?.committedBytes ?: 0L
        if (partial.length() < committed) committed = partial.length() / chunkSize * chunkSize
        RandomAccessFile(partial, "rw").use { file ->
            // Reclaim invalid or uncommitted bytes before checking available space for the retry.
            file.setLength(committed)
            file.fd.sync()
            val needed = target.sizeBytes - committed + target.sizeBytes + 64L * 1024 * 1024
            if (directory.usableSpace < needed) throw IOException("INSUFFICIENT_UPDATE_SPACE")
            if (!state.write(AppUpdateCheckpoint(key, committed))) throw IOException("UPDATE_CHECKPOINT_WRITE_FAILED")
            file.seek(committed)
            progress(committed, target.sizeBytes)
            while (committed < target.sizeBytes) {
                if (client.isCancelled()) throw InterruptedIOException("UPDATE_CANCELLED")
                val bytes = readChunk(client, target, (committed / chunkSize).toInt())
                file.write(bytes)
                file.fd.sync()
                committed += bytes.size
                if (!state.write(AppUpdateCheckpoint(key, committed))) throw IOException("UPDATE_CHECKPOINT_WRITE_FAILED")
                progress(committed, target.sizeBytes)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
        FileInputStream(partial).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (client.isCancelled()) throw InterruptedIOException("UPDATE_CANCELLED")
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (partial.length() != target.sizeBytes || actual != target.sha256) {
            RandomAccessFile(partial, "rw").use { file ->
                file.setLength(0)
                file.fd.sync()
            }
            if (!state.write(AppUpdateCheckpoint(key, 0))) throw IOException("UPDATE_CHECKPOINT_WRITE_FAILED")
            throw IOException("UPDATE_CHECKSUM_MISMATCH")
        }
        verifyTarget(client, target, currentCode)
        val ready = File(directory, "verified.apk")
        if (ready.exists() && !ready.delete()) throw IOException("UPDATE_CLEANUP_FAILED")
        if (!partial.renameTo(ready)) throw IOException("UPDATE_RENAME_FAILED")
        return ready
    }

    private fun verifyTarget(client: AppUpdateHttpClient, target: AppUpdateCandidate, currentCode: Int) {
        val latest = client.check(currentCode)
        if (latest.status != AppUpdateStatus.AVAILABLE || latest.candidate?.sameTarget(target) != true) {
            throw IOException("UPDATE_TARGET_CHANGED")
        }
    }

    private fun readChunk(client: AppUpdateHttpClient, target: AppUpdateCandidate, index: Int): ByteArray {
        repeat(3) { attempt ->
            try { return client.chunk(target, chunkSize, index) }
            catch (error: AppUpdateNetworkException) {
                val retryable = error.code == "TRANSPORT_ERROR" || error.httpStatus == 429 || error.httpStatus in 500..599
                if (!retryable || attempt == 2) throw error
                repeat((attempt + 1) * 5) {
                    if (client.isCancelled()) throw InterruptedIOException("UPDATE_CANCELLED")
                    Thread.sleep(100)
                }
            }
        }
        throw IOException("UPDATE_DOWNLOAD_FAILED")
    }
}
