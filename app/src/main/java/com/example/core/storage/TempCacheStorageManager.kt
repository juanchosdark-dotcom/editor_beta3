package com.example.core.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class CacheStorageStats(
  val totalCacheBytes: Long = 0L,
  val proxiesBytes: Long = 0L,
  val tempMediaBytes: Long = 0L,
  val exportScratchBytes: Long = 0L,
  val waveformsBytes: Long = 0L,
  val otherCacheBytes: Long = 0L,
  val cacheFileCount: Int = 0,
  val savedProjectCount: Int = 0,
  val savedProjectsBytes: Long = 0L,
)

/**
 * Manages temporary storage strictly inside Android's `cacheDir` (proxies, temp media copies,
 * thumbnails, audio waveforms, and export staging) while keeping persistent `.json` projects in
 * `filesDir/projects/` untouched during cache cleanups.
 */
class TempCacheStorageManager(
  val cacheRootDir: File,
  val projectsRootDir: File,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

  val proxiesDir: File
    get() = File(cacheRootDir, DIR_PROXIES)

  val tempMediaDir: File
    get() = File(cacheRootDir, DIR_TEMP_MEDIA)

  val thumbnailsDir: File
    get() = File(cacheRootDir, DIR_THUMBNAILS)

  val waveformsDir: File
    get() = File(cacheRootDir, DIR_WAVEFORMS)

  val exportStagingDir: File
    get() = File(cacheRootDir, DIR_EXPORT_STAGING)

  suspend fun measureStorage(): CacheStorageStats =
    withContext(ioDispatcher) {
      val (proxiesBytes, proxiesCount) = sumDirectory(proxiesDir)
      val (tempMediaBytes, tempMediaCount) =
        sumDirectory(tempMediaDir) plus sumDirectory(File(cacheRootDir, "media"))
      val (exportBytes, exportCount) =
        sumDirectory(exportStagingDir) plus sumDirectory(File(cacheRootDir, "exports"))
      val (waveformsBytes, waveformsCount) = sumDirectory(waveformsDir)
      val (totalCacheBytes, totalCacheCount) = sumDirectory(cacheRootDir)

      val categorized = proxiesBytes + tempMediaBytes + exportBytes + waveformsBytes
      val otherBytes = (totalCacheBytes - categorized).coerceAtLeast(0L)
      val projectFiles =
        if (projectsRootDir.exists()) {
          projectsRootDir
            .listFiles { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .orEmpty()
        } else {
          emptyArray()
        }

      CacheStorageStats(
        totalCacheBytes = totalCacheBytes,
        proxiesBytes = proxiesBytes,
        tempMediaBytes = tempMediaBytes,
        exportScratchBytes = exportBytes,
        waveformsBytes = waveformsBytes,
        otherCacheBytes = otherBytes,
        cacheFileCount =
          totalCacheCount.coerceAtLeast(proxiesCount + tempMediaCount + exportCount + waveformsCount),
        savedProjectCount = projectFiles.size,
        savedProjectsBytes = projectFiles.sumOf { it.length() },
      )
    }

  /**
   * Clears temporary files inside [cacheRootDir] without modifying or deleting any `.json`
   * files inside [projectsRootDir]. Optionally preserves specified subdirectories (e.g. active export staging).
   */
  suspend fun clearTemporaryCache(preserveDirectories: Set<String> = emptySet()): CacheStorageStats =
    withContext(ioDispatcher) {
      if (cacheRootDir.exists()) {
        cacheRootDir.listFiles().orEmpty().forEach { child ->
          if (child.name !in preserveDirectories) {
            child.deleteRecursively()
          }
        }
      }
      cacheRootDir.mkdirs()
      measureStorage()
    }

  private fun sumDirectory(dir: File): Pair<Long, Int> {
    if (!dir.exists()) return 0L to 0
    var bytes = 0L
    var count = 0
    dir.walkTopDown().forEach { file ->
      if (file.isFile) {
        bytes += file.length()
        count++
      }
    }
    return bytes to count
  }

  private infix fun Pair<Long, Int>.plus(other: Pair<Long, Int>): Pair<Long, Int> =
    (first + other.first) to (second + other.second)

  companion object {
    const val DIR_PROXIES = "proxies"
    const val DIR_TEMP_MEDIA = "temp_media"
    const val DIR_THUMBNAILS = "thumbnails"
    const val DIR_WAVEFORMS = "waveforms"
    const val DIR_EXPORT_STAGING = "export_staging"

    fun formatBytes(bytes: Long): String {
      val safe = bytes.coerceAtLeast(0L)
      return when {
        safe < 1024L -> "$safe B"
        safe < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", safe / 1024.0)
        safe < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
      }
    }
  }
}
