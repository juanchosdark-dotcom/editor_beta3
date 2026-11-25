package com.example.core.media.probe

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.example.core.model.MediaAsset
import com.example.core.model.MediaFingerprint
import com.example.core.model.MediaType
import com.example.core.model.RelinkState
import com.example.core.model.RelinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Extracts technical media metadata ([MediaFingerprint]), including rotation, HDR color
 * transfer/standard, and safely imports Photo Picker / SAF URIs into the portable project's `media/`.
 */
class MediaProbe(private val context: Context) {

  suspend fun importIntoPortableProject(
    sourceUri: Uri,
    projectRootDir: File,
    mediaDirName: String = "media",
  ): Result<MediaAsset> =
    withContext(Dispatchers.IO) {
      runCatching {
        val mediaDir = File(projectRootDir, mediaDirName).apply { mkdirs() }
        val assetId = "asset_${UUID.randomUUID().toString().take(8)}"
        val displayName = resolveDisplayName(sourceUri) ?: "Clip_importado.mp4"
        val sanitizedName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destFile = File(mediaDir, "${assetId}_$sanitizedName")

        try {
          val stream =
            try {
              context.contentResolver.openInputStream(sourceUri)
            } catch (_: SecurityException) {
              error("Sin permiso para leer el video seleccionado")
            } ?: error("No se pudo abrir el archivo seleccionado")

          stream.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
          }

          if (!destFile.exists() || destFile.length() <= 0L) {
            error("El archivo importado está vacío o no es accesible")
          }

          val mimeType =
            runCatching { context.contentResolver.getType(sourceUri) }.getOrNull() ?: "video/mp4"
          val mediaType =
            when {
              mimeType.startsWith("audio/") -> MediaType.AUDIO
              mimeType.startsWith("image/") -> MediaType.IMAGE
              else -> MediaType.VIDEO
            }

          val fingerprint = probeFile(destFile, mimeType)

          MediaAsset(
            assetId = assetId,
            type = mediaType,
            displayName = displayName,
            relativePath = "$mediaDirName/${destFile.name}",
            externalSourceUri = sourceUri.toString(),
            proxyRelativePath = null,
            fingerprint = fingerprint,
            relinkState =
              RelinkState(
                status = RelinkStatus.ONLINE,
                lastVerifiedAtIso = Instant.now().toString(),
              ),
          )
        } catch (t: Throwable) {
          runCatching { if (destFile.exists()) destFile.delete() }
          throw t
        }
      }
    }

  /**
   * Probes an existing local media file safely with fallback defaults if the codec/container
   * exposes partial or malformed metadata (including HDR / high-res streams).
   */
  fun probeFile(file: File, fallbackMimeType: String = "video/mp4"): MediaFingerprint {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val durationMs =
        runCatching {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.getOrNull() ?: 0L
      val retrieverWidth =
        runCatching {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        }.getOrNull()
      val retrieverHeight =
        runCatching {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        }.getOrNull()
      val retrieverRotation =
        runCatching {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        }.getOrNull()
      val retrieverHasAudio =
        runCatching {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        }.getOrDefault(false)
      val retrieverMime =
        runCatching {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        }.getOrNull()

      val retrieverColorStandard =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull()
          }.getOrNull()
        } else {
          null
        }
      val retrieverColorTransfer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
          }.getOrNull()
        } else {
          null
        }

      val extractorMeta = extractTrackDetailsFallback(file)
      val width = retrieverWidth ?: extractorMeta.width
      val height = retrieverHeight ?: extractorMeta.height
      val rotation = retrieverRotation ?: extractorMeta.rotationDegrees ?: 0
      val hasAudio = retrieverHasAudio || extractorMeta.hasAudio
      val mimeType = retrieverMime ?: extractorMeta.mimeType ?: fallbackMimeType
      val colorStandard = retrieverColorStandard ?: extractorMeta.colorStandard
      val colorTransfer = retrieverColorTransfer ?: extractorMeta.colorTransfer
      val resolvedDuration =
        if (durationMs > 0L) durationMs else (extractorMeta.durationMs ?: 1L)

      MediaFingerprint(
        byteSize = file.length(),
        durationMs = resolvedDuration.coerceAtLeast(1L),
        partialSha256 = runCatching { computePartialSha256(file) }.getOrDefault("unavailable"),
        mimeType = mimeType,
        width = width,
        height = height,
        fps = extractorMeta.fps,
        rotationDegrees = normalizeRotation(rotation),
        hasAudio = hasAudio,
        colorStandard = colorStandard,
        colorTransfer = colorTransfer,
        isHdr = isHdrColorMetadata(colorTransfer = colorTransfer, colorStandard = colorStandard),
        isHighResolution = isHighResolutionVideo(width = width, height = height),
      )
    } catch (_: Throwable) {
      val extractorMeta = extractTrackDetailsFallback(file)
      val width = extractorMeta.width
      val height = extractorMeta.height
      val colorStandard = extractorMeta.colorStandard
      val colorTransfer = extractorMeta.colorTransfer
      MediaFingerprint(
        byteSize = file.length(),
        durationMs = (extractorMeta.durationMs ?: 1L).coerceAtLeast(1L),
        partialSha256 = runCatching { computePartialSha256(file) }.getOrDefault("unavailable"),
        mimeType = extractorMeta.mimeType ?: fallbackMimeType,
        width = width,
        height = height,
        fps = extractorMeta.fps,
        rotationDegrees = normalizeRotation(extractorMeta.rotationDegrees ?: 0),
        hasAudio = extractorMeta.hasAudio,
        colorStandard = colorStandard,
        colorTransfer = colorTransfer,
        isHdr = isHdrColorMetadata(colorTransfer = colorTransfer, colorStandard = colorStandard),
        isHighResolution = isHighResolutionVideo(width = width, height = height),
      )
    } finally {
      runCatching { retriever.release() }
    }
  }

  private data class ExtractorTrackDetails(
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val fps: Float? = null,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    val colorStandard: Int? = null,
    val colorTransfer: Int? = null,
    val hasAudio: Boolean = false,
  )

  private fun extractTrackDetailsFallback(file: File): ExtractorTrackDetails {
    if (!file.exists() || file.length() <= 0L) return ExtractorTrackDetails()
    val extractor = MediaExtractor()
    return try {
      extractor.setDataSource(file.absolutePath)
      var width: Int? = null
      var height: Int? = null
      var rotation: Int? = null
      var fps: Float? = null
      var durationMs: Long? = null
      var mimeType: String? = null
      var colorStandard: Int? = null
      var colorTransfer: Int? = null
      var hasAudio = false

      for (i in 0 until extractor.trackCount) {
        val format = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
        val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull().orEmpty()
        if (mime.startsWith("audio/")) {
          hasAudio = true
        } else if (mime.startsWith("video/") && width == null) {
          mimeType = mime
          if (format.containsKey(MediaFormat.KEY_WIDTH)) {
            width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
          }
          if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
            height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
          }
          if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrNull()
          }
          if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull()
              ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
          }
          if (format.containsKey(MediaFormat.KEY_DURATION)) {
            val us = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrNull() ?: 0L
            if (us > 0L) durationMs = us / 1_000L
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
              colorStandard = runCatching { format.getInteger(MediaFormat.KEY_COLOR_STANDARD) }.getOrNull()
            }
            if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
              colorTransfer = runCatching { format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) }.getOrNull()
            }
          }
        }
      }
      ExtractorTrackDetails(
        width = width,
        height = height,
        rotationDegrees = rotation,
        fps = fps,
        durationMs = durationMs,
        mimeType = mimeType,
        colorStandard = colorStandard,
        colorTransfer = colorTransfer,
        hasAudio = hasAudio,
      )
    } catch (_: Throwable) {
      ExtractorTrackDetails()
    } finally {
      runCatching { extractor.release() }
    }
  }

  private fun resolveDisplayName(uri: Uri): String? {
    runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
              val candidate = cursor.getString(index)?.trim()
              if (!candidate.isNullOrBlank()) return candidate
            }
          }
        }
    }
    return uri.lastPathSegment
      ?.substringAfterLast('/')
      ?.substringAfterLast(':')
      ?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
  }

  private fun computePartialSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val chunkSize = 64 * 1024
    val length = file.length()
    FileInputStream(file).use { stream ->
      val buffer = ByteArray(chunkSize)
      val headRead = stream.read(buffer)
      if (headRead > 0) digest.update(buffer, 0, headRead)
      if (length > chunkSize * 2L) {
        stream.channel.position(length - chunkSize)
        val tailRead = stream.read(buffer)
        if (tailRead > 0) digest.update(buffer, 0, tailRead)
      }
    }
    digest.update(length.toString().toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
  }

  companion object {
    fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    fun isHdrColorMetadata(colorTransfer: Int?, colorStandard: Int?): Boolean {
      val hdrTransfer =
        colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
          colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
      val bt2020Standard = colorStandard == MediaFormat.COLOR_STANDARD_BT2020
      return hdrTransfer || bt2020Standard
    }

    fun isHighResolutionVideo(width: Int?, height: Int?): Boolean {
      val w = width ?: return false
      val h = height ?: return false
      return maxOf(w, h) > 1920 || (w.toLong() * h.toLong()) > (1920L * 1080L)
    }
  }
}
