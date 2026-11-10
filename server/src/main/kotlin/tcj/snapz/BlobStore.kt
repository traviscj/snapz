package tcj.snapz

import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.inject.Inject
import javax.inject.Singleton
import misk.exceptions.BadRequestException
import misk.logging.getLogger

data class BlobInfo(val sha256: String, val contentType: String, val bytes: Long, val width: Int?, val height: Int?)

/**
 * Content-addressed originals on local disk: <blob_dir>/orig/ab/<sha256>, plus derived JPEG
 * thumbnails at <blob_dir>/thumb/ab/<sha256>.jpg. Photo rows reference blobs by sha256, so copy is
 * a row insert and identical uploads dedupe.
 */
@Singleton
class BlobStore @Inject constructor(config: SnapzConfig) {
  private val root = File(config.snapz.blob_dir)
  private val thumbPx = config.snapz.thumb_px

  fun original(sha: String) = File(root, "orig/${sha.substring(0, 2)}/$sha")
  private fun thumb(sha: String) = File(root, "thumb/${sha.substring(0, 2)}/$sha.jpg")

  fun put(data: ByteArray): BlobInfo {
    val contentType = sniff(data) ?: throw BadRequestException("not a recognized image (jpeg/png/heic/webp/gif)")
    val sha = sha256Hex(data)
    val f = original(sha)
    if (!f.exists()) {
      f.parentFile.mkdirs()
      val tmp = File(f.parentFile, "$sha.tmp-${randomToken(6)}")
      tmp.writeBytes(data)
      Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    val dims = try {
      makeThumb(sha, data)
    } catch (e: Exception) {
      logger.warn { "no thumbnail for $sha ($contentType): $e" }
      null
    }
    return BlobInfo(sha, contentType, data.size.toLong(), dims?.first, dims?.second)
  }

  /** Thumbnail if one exists or can be made; null for formats ImageIO can't decode (HEIC). */
  fun thumbOrNull(sha: String): File? {
    val t = thumb(sha)
    if (t.exists()) return t
    val o = original(sha)
    if (!o.exists()) return null
    return try {
      makeThumb(sha, o.readBytes())
      t.takeIf { it.exists() }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * The image as displayed (EXIF orientation applied), decoded so its longest side is at most
   * roughly [maxSide]. For transforms that need pixels; null if ImageIO can't decode the format.
   */
  fun readUpright(sha: String, maxSide: Int): BufferedImage? {
    val data = original(sha).takeIf { it.exists() }?.readBytes() ?: return null
    val orientation = exifOrientation(data)
    return ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
      val readers = ImageIO.getImageReaders(input)
      if (!readers.hasNext()) return null
      val reader = readers.next()
      try {
        reader.input = input
        val param = reader.defaultReadParam
        val sub = maxOf(1, maxOf(reader.getWidth(0), reader.getHeight(0)) / maxSide)
        param.setSourceSubsampling(sub, sub, 0, 0)
        val img = orient(reader.read(0, param), orientation)
        if (img.type == BufferedImage.TYPE_INT_RGB) img else BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { out ->
          out.createGraphics().apply { drawImage(img, 0, 0, java.awt.Color.WHITE, null); dispose() }
        }
      } finally {
        reader.dispose()
      }
    }
  }

  fun encodeJpeg(img: BufferedImage, quality: Float = 0.88f): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    ImageIO.createImageOutputStream(out).use { ios ->
      writer.output = ios
      val wp = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
      }
      writer.write(null, IIOImage(img, null, null), wp)
      writer.dispose()
    }
    return out.toByteArray()
  }

  fun delete(sha: String) {
    original(sha).delete()
    thumb(sha).delete()
  }

  /** Writes the thumbnail; returns display (orientation-corrected) width x height of the original. */
  private fun makeThumb(sha: String, data: ByteArray): Pair<Int, Int>? {
    val orientation = exifOrientation(data)
    ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { input ->
      val readers = ImageIO.getImageReaders(input)
      if (!readers.hasNext()) return null
      val reader = readers.next()
      try {
        reader.input = input
        val w = reader.getWidth(0)
        val h = reader.getHeight(0)
        val param = reader.defaultReadParam
        // Decode at reduced resolution: a 12MP original never needs to be fully in memory.
        val sub = maxOf(1, minOf(w, h) / (thumbPx * 2))
        param.setSourceSubsampling(sub, sub, 0, 0)
        val img = orient(reader.read(0, param), orientation)
        val scale = minOf(1.0, thumbPx.toDouble() / maxOf(img.width, img.height))
        val tw = maxOf(1, (img.width * scale).toInt())
        val th = maxOf(1, (img.height * scale).toInt())
        val out = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
          setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
          drawImage(img, 0, 0, tw, th, java.awt.Color.WHITE, null)
          dispose()
        }
        val t = thumb(sha)
        t.parentFile.mkdirs()
        val tmp = File(t.parentFile, "$sha.tmp-${randomToken(6)}")
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        ImageIO.createImageOutputStream(tmp).use { ios ->
          writer.output = ios
          val wp = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = 0.8f
          }
          writer.write(null, IIOImage(out, null, null), wp)
          writer.dispose()
        }
        Files.move(tmp.toPath(), t.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return if (orientation >= 5) h to w else w to h
      } finally {
        reader.dispose()
      }
    }
  }

  companion object {
    private val logger = getLogger<BlobStore>()

    fun sniff(d: ByteArray): String? {
      fun at(i: Int) = if (i < d.size) d[i].toInt() and 0xff else -1
      fun ascii(off: Int, len: Int) = if (d.size >= off + len) String(d, off, len, Charsets.ISO_8859_1) else ""
      return when {
        at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
        at(0) == 0x89 && ascii(1, 3) == "PNG" -> "image/png"
        ascii(0, 4) == "GIF8" -> "image/gif"
        ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> "image/webp"
        ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("heic", "heix", "mif1", "msf1", "hevc", "heim", "heis") -> "image/heic"
        ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("avif", "avis") -> "image/avif"
        else -> null
      }
    }

    /**
     * EXIF Orientation (1..8) from a JPEG's APP1 segment, 1 if absent. iPhone JPEGs store
     * sensor-oriented pixels plus this tag; ImageIO ignores it, so thumbnails apply it by hand.
     */
    fun exifOrientation(d: ByteArray): Int {
      fun u8(i: Int) = d[i].toInt() and 0xff
      fun u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
      try {
        if (d.size < 4 || u8(0) != 0xff || u8(1) != 0xd8) return 1
        var p = 2
        while (p + 4 <= d.size) {
          if (u8(p) != 0xff) return 1
          val marker = u8(p + 1)
          if (marker == 0xda || marker == 0xd9) return 1
          val len = u16be(p + 2)
          if (marker == 0xe1 && p + 10 <= d.size && String(d, p + 4, 4, Charsets.ISO_8859_1) == "Exif") {
            val tiff = p + 10
            val le = u8(tiff) == 0x49
            fun u16(i: Int) = if (le) (u8(i + 1) shl 8) or u8(i) else u16be(i)
            fun u32(i: Int) = if (le) (u16(i + 2) shl 16) or u16(i) else (u16(i) shl 16) or u16(i + 2)
            val ifd = tiff + u32(tiff + 4)
            val n = u16(ifd)
            for (e in 0 until n) {
              val entry = ifd + 2 + e * 12
              if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
            }
            return 1
          }
          p += 2 + len
        }
      } catch (e: IndexOutOfBoundsException) {
        // truncated/garbled EXIF: treat as unrotated
      }
      return 1
    }

    fun orient(img: BufferedImage, orientation: Int): BufferedImage {
      if (orientation <= 1) return img
      val w = img.width.toDouble()
      val h = img.height.toDouble()
      val t = AffineTransform()
      when (orientation) {
        2 -> { t.scale(-1.0, 1.0); t.translate(-w, 0.0) }
        3 -> { t.translate(w, h); t.rotate(Math.PI) }
        4 -> { t.scale(1.0, -1.0); t.translate(0.0, -h) }
        5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
        6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
        7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
        8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
      }
      val swap = orientation >= 5
      val out = BufferedImage(if (swap) img.height else img.width, if (swap) img.width else img.height, BufferedImage.TYPE_INT_RGB)
      out.createGraphics().apply {
        drawImage(img, t, null)
        dispose()
      }
      return out
    }
  }
}
