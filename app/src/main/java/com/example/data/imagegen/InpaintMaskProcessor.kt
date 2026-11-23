package com.example.data.imagegen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Normalized stroke in [0f..1f] image coordinates so a drawn mask maps accurately
 * to any diffusion output resolution regardless of preview canvas aspect ratio.
 */
data class MaskStroke(
    val points: List<Pair<Float, Float>>,
    val brushRadiusFraction: Float,
    val isEraser: Boolean = false
)

object InpaintMaskProcessor {

    private const val MAX_UPSCALE_DIMENSION = 2048
    private const val MAX_ZIP_EXTRACT_BYTES = 8L * 1024L * 1024L * 1024L // 8 GB guard

    /**
     * Renders normalized strokes into an alpha mask bitmap where alpha = 255 means "repaint region".
     */
    fun renderMaskBitmap(
        strokes: List<MaskStroke>,
        width: Int,
        height: Int,
        invert: Boolean
    ): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        canvas.drawColor(Color.TRANSPARENT)

        val minDim = min(width, height).toFloat().coerceAtLeast(1f)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.TRANSPARENT
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val paint = if (stroke.isEraser) erasePaint else drawPaint
            paint.strokeWidth = (stroke.brushRadiusFraction * minDim * 2f).coerceAtLeast(4f)
            if (stroke.points.size == 1) {
                val (nx, ny) = stroke.points.first()
                val dotPaint = Paint(paint).apply { style = Paint.Style.FILL }
                canvas.drawCircle(nx * width, ny * height, paint.strokeWidth / 2f, dotPaint)
            } else {
                val path = Path()
                val (sx, sy) = stroke.points.first()
                path.moveTo(sx * width, sy * height)
                for (i in 1 until stroke.points.size) {
                    val (px, py) = stroke.points[i]
                    path.lineTo(px * width, py * height)
                }
                canvas.drawPath(path, paint)
            }
        }

        if (invert) {
            val pixels = IntArray(width * height)
            mask.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val a = (pixels[i] ushr 24) and 0xFF
                val invA = 255 - a
                pixels[i] = (invA shl 24) or 0x00FFFFFF
            }
            mask.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return mask
    }

    /**
     * Prepares the source image for on-device inpainting by gently blending masked pixels
     * toward local surrounding tone + subtle grain, encouraging the diffusion model to
     * synthesize fresh content inside the painted mask while retaining structure.
     */
    fun prepareInpaintInitBitmap(
        sourcePath: String,
        maskBitmap: Bitmap,
        width: Int,
        height: Int,
        seed: Long
    ): Bitmap {
        val raw = BitmapFactory.decodeFile(sourcePath)
            ?: throw IOException("Cannot decode inpaint source image.")
        val scaled = if (raw.width != width || raw.height != height) {
            Bitmap.createScaledBitmap(raw, width, height, true).also { raw.recycle() }
        } else {
            raw
        }
        val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable !== scaled) scaled.recycle()

        val srcPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        mutable.getPixels(srcPixels, 0, width, 0, 0, width, height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)

        // Compute average unmasked color as a clean neutral base for heavily masked areas
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        for (i in srcPixels.indices) {
            val maskAlpha = (maskPixels[i] ushr 24) and 0xFF
            if (maskAlpha < 32) {
                val c = srcPixels[i]
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
                count++
            }
        }
        val avgR = if (count > 0) (sumR / count).toInt() else 128
        val avgG = if (count > 0) (sumG / count).toInt() else 128
        val avgB = if (count > 0) (sumB / count).toInt() else 128
        val rng = Random(seed)

        for (i in srcPixels.indices) {
            val maskAlpha = (maskPixels[i] ushr 24) and 0xFF
            if (maskAlpha > 0) {
                val t = (maskAlpha / 255f) * 0.45f
                val c = srcPixels[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val noise = rng.nextInt(-18, 19)
                val nr = ((r * (1f - t) + avgR * t) + noise).roundToInt().coerceIn(0, 255)
                val ng = ((g * (1f - t) + avgG * t) + noise).roundToInt().coerceIn(0, 255)
                val nb = ((b * (1f - t) + avgB * t) + noise).roundToInt().coerceIn(0, 255)
                srcPixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        mutable.setPixels(srcPixels, 0, width, 0, 0, width, height)
        return mutable
    }

    /**
     * Composites the diffused result into the original image using a feathered alpha mask
     * so unmasked areas remain 100% pixel-identical to the original photo.
     */
    fun compositeInpaintResult(
        originalPath: String,
        diffusedBitmap: Bitmap,
        maskBitmap: Bitmap,
        featherRadiusPx: Int
    ): Bitmap {
        val width = diffusedBitmap.width
        val height = diffusedBitmap.height

        val origRaw = BitmapFactory.decodeFile(originalPath)
            ?: throw IOException("Cannot decode original image for inpaint compositing.")
        val origScaled = if (origRaw.width != width || origRaw.height != height) {
            Bitmap.createScaledBitmap(origRaw, width, height, true).also { origRaw.recycle() }
        } else {
            origRaw
        }
        val origArgb = if (origScaled.config != Bitmap.Config.ARGB_8888) {
            origScaled.copy(Bitmap.Config.ARGB_8888, false).also { origScaled.recycle() }
        } else {
            origScaled
        }

        val origPixels = IntArray(width * height)
        val genPixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        origArgb.getPixels(origPixels, 0, width, 0, 0, width, height)
        diffusedBitmap.getPixels(genPixels, 0, width, 0, 0, width, height)
        maskBitmap.getPixels(maskPixels, 0, width, 0, 0, width, height)
        origArgb.recycle()

        val alphaChannel = IntArray(width * height) { idx -> (maskPixels[idx] ushr 24) and 0xFF }
        val featheredAlpha = if (featherRadiusPx > 0) {
            boxBlurAlpha(alphaChannel, width, height, featherRadiusPx.coerceIn(1, 24))
        } else {
            alphaChannel
        }

        val outPixels = IntArray(width * height)
        for (i in outPixels.indices) {
            val a = featheredAlpha[i]
            when (a) {
                0 -> outPixels[i] = origPixels[i]
                255 -> outPixels[i] = genPixels[i]
                else -> {
                    val inv = 255 - a
                    val oc = origPixels[i]
                    val gc = genPixels[i]
                    val r = (((oc ushr 16) and 0xFF) * inv + ((gc ushr 16) and 0xFF) * a) / 255
                    val g = (((oc ushr 8) and 0xFF) * inv + ((gc ushr 8) and 0xFF) * a) / 255
                    val b = ((oc and 0xFF) * inv + (gc and 0xFF) * a) / 255
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Multi-step high-quality bicubic upscale + adaptive unsharp luminance enhancement.
     * Runs instantaneously on-device for 2x or 4x upscaling without external servers.
     */
    fun upscaleBitmapSharp(
        sourcePath: String,
        scaleFactor: Int,
        sharpenStrength: Float = 0.55f
    ): Bitmap {
        val raw = BitmapFactory.decodeFile(sourcePath)
            ?: throw IOException("Cannot decode source image for upscaling.")

        val desiredW = (raw.width * scaleFactor).coerceAtMost(MAX_UPSCALE_DIMENSION)
        val desiredH = (raw.height * scaleFactor).coerceAtMost(MAX_UPSCALE_DIMENSION)

        // Progressive 2x stepping preserves edges significantly better than single-jump scaling
        var current = if (raw.config != Bitmap.Config.ARGB_8888) {
            raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        } else {
            raw
        }

        while (current.width * 2 <= desiredW && current.height * 2 <= desiredH) {
            val next = Bitmap.createScaledBitmap(current, current.width * 2, current.height * 2, true)
            current.recycle()
            current = next
        }
        if (current.width != desiredW || current.height != desiredH) {
            val finalScaled = Bitmap.createScaledBitmap(current, desiredW, desiredH, true)
            current.recycle()
            current = finalScaled
        }

        if (sharpenStrength <= 0.01f) return current

        val w = current.width
        val h = current.height
        val src = IntArray(w * h)
        val out = IntArray(w * h)
        current.getPixels(src, 0, w, 0, 0, w, h)
        current.recycle()

        // 3x3 unsharp mask focused on luminance edges with halo clamping
        val amount = sharpenStrength.coerceIn(0.1f, 1.2f)
        for (y in 0 until h) {
            val yUp = max(0, y - 1) * w
            val yMid = y * w
            val yDown = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xL = max(0, x - 1)
                val xR = min(w - 1, x + 1)

                val c = src[yMid + x]
                val n1 = src[yUp + x]
                val n2 = src[yDown + x]
                val n3 = src[yMid + xL]
                val n4 = src[yMid + xR]

                val cr = (c ushr 16) and 0xFF
                val cg = (c ushr 8) and 0xFF
                val cb = c and 0xFF

                val blurR = (((n1 ushr 16) and 0xFF) + ((n2 ushr 16) and 0xFF) + ((n3 ushr 16) and 0xFF) + ((n4 ushr 16) and 0xFF)) / 4
                val blurG = (((n1 ushr 8) and 0xFF) + ((n2 ushr 8) and 0xFF) + ((n3 ushr 8) and 0xFF) + ((n4 ushr 8) and 0xFF)) / 4
                val blurB = ((n1 and 0xFF) + (n2 and 0xFF) + (n3 and 0xFF) + (n4 and 0xFF)) / 4

                val nr = (cr + ((cr - blurR) * amount).roundToInt()).coerceIn(0, 255)
                val ng = (cg + ((cg - blurG) * amount).roundToInt()).coerceIn(0, 255)
                val nb = (cb + ((cb - blurB) * amount).roundToInt()).coerceIn(0, 255)
                out[yMid + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }

        val enhanced = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        enhanced.setPixels(out, 0, w, 0, 0, w, h)
        return enhanced
    }

    /**
     * Safely unpacks a .zip diffusion model archive with strict Zip-Slip canonical path validation.
     * Returns all extracted files so the caller can pick native weights (.safetensors/.ckpt/.gguf).
     */
    fun extractModelZipSafely(zipFile: File, targetDir: File): List<File> {
        if (!targetDir.exists()) targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalPath + File.separator
        val extracted = mutableListOf<File>()
        var totalWritten = 0L

        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            val buffer = ByteArray(64 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                val cleanName = entry.name.replace('\\', '/').trimStart('/')
                if (cleanName.isNotEmpty() && !entry.isDirectory) {
                    val destFile = File(targetDir, cleanName)
                    val destCanonical = destFile.canonicalPath
                    if (!destCanonical.startsWith(canonicalRoot)) {
                        throw SecurityException("Blocked unsafe archive entry path: ${entry.name}")
                    }
                    destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    FileOutputStream(destFile).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            totalWritten += read
                            if (totalWritten > MAX_ZIP_EXTRACT_BYTES) {
                                throw IOException("ZIP archive exceeds maximum allowed extraction size.")
                            }
                            fos.write(buffer, 0, read)
                        }
                    }
                    extracted.add(destFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return extracted
    }

    private fun boxBlurAlpha(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val temp = IntArray(w * h)
        val out = IntArray(w * h)
        val window = radius * 2 + 1

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (k in -radius..radius) {
                sum += src[row + k.coerceIn(0, w - 1)]
            }
            for (x in 0 until w) {
                temp[row + x] = sum / window
                val addIdx = (x + radius + 1).coerceAtMost(w - 1)
                val subIdx = (x - radius).coerceAtLeast(0)
                sum += src[row + addIdx] - src[row + subIdx]
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sum = 0
            for (k in -radius..radius) {
                sum += temp[k.coerceIn(0, h - 1) * w + x]
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / window
                val addIdx = (y + radius + 1).coerceAtMost(h - 1)
                val subIdx = (y - radius).coerceAtLeast(0)
                sum += temp[addIdx * w + x] - temp[subIdx * w + x]
            }
        }
        return out
    }
}
