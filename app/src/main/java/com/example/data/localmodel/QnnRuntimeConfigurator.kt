package com.example.data.localmodel

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File

data class QnnRuntimeStatus(
    val isQnnHtpBundled: Boolean,
    val isLiteRtDispatchBundled: Boolean,
    val adspPathConfigured: Boolean,
    val hexagonArch: String,
    val nativeLibDir: String,
    val qnnCacheDir: String,
    val bundledQnnLibCount: Int
)

/**
 * Configures Qualcomm QNN / Hexagon Tensor Processor (HTP) environment variables and
 * verifies bundled QNN + LiteRT Dispatch libraries inside nativeLibraryDir.
 *
 * Critical on Snapdragon Android 12+: Hexagon DSP skeleton binaries (libQnnHtpV*Skel.so)
 * require ADSP_LIBRARY_PATH to include the app's extracted nativeLibraryDir before
 * initializing LiteRT-LM Backend.NPU(...) or QNN HTP sessions.
 */
object QnnRuntimeConfigurator {

    @Volatile
    private var cachedStatus: QnnRuntimeStatus? = null

    fun configureAndInspect(context: Context): QnnRuntimeStatus {
        cachedStatus?.let { return it }

        val nativeDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val cacheDir = File(context.cacheDir, "qnn_htp_cache").apply {
            if (!exists()) mkdirs()
        }

        val adspPaths = buildList {
            if (nativeDir.isNotBlank()) add(nativeDir)
            add("/vendor/lib/rfsa/adsp")
            add("/vendor/dsp/cdsp")
            add("/vendor/dsp/adsp")
            add("/system/lib/rfsa/adsp")
            add("/system/vendor/lib/rfsa/adsp")
            add("/dsp")
        }.joinToString(";")

        val adspConfigured = runCatching {
            Os.setenv("ADSP_LIBRARY_PATH", adspPaths, true)
            if (nativeDir.isNotBlank()) {
                val existingLd = Os.getenv("LD_LIBRARY_PATH").orEmpty()
                val newLd = if (existingLd.contains(nativeDir)) {
                    existingLd
                } else {
                    listOf(nativeDir, "/vendor/lib64", "/system/vendor/lib64", existingLd)
                        .filter { it.isNotBlank() }
                        .joinToString(":")
                }
                Os.setenv("LD_LIBRARY_PATH", newLd, true)
            }
            Os.setenv("QNN_HTP_CACHE_DIR", cacheDir.absolutePath, true)
            true
        }.getOrDefault(false)

        val nativeFiles = if (nativeDir.isNotBlank()) {
            runCatching { File(nativeDir).listFiles()?.map { it.name }.orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val qnnLibs = nativeFiles.filter { name ->
            name.startsWith("libQnn", ignoreCase = true) ||
                name.startsWith("libHtp", ignoreCase = true)
        }
        val hasLiteRtDispatch = nativeFiles.any { name ->
            name.contains("LiteRtDispatch_Qualcomm", ignoreCase = true) ||
                name.contains("QnnHtp", ignoreCase = true)
        }
        val hasQnnHtp = qnnLibs.any { it.contains("QnnHtp", ignoreCase = true) }

        val status = QnnRuntimeStatus(
            isQnnHtpBundled = hasQnnHtp,
            isLiteRtDispatchBundled = hasLiteRtDispatch,
            adspPathConfigured = adspConfigured,
            hexagonArch = detectHexagonArch(),
            nativeLibDir = nativeDir,
            qnnCacheDir = cacheDir.absolutePath,
            bundledQnnLibCount = qnnLibs.size
        )
        cachedStatus = status
        return status
    }

    fun detectHexagonArch(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MODEL.orEmpty()} ${Build.SOC_MANUFACTURER.orEmpty()}"
        } else {
            ""
        }
        val combined = "$soc ${Build.HARDWARE.orEmpty()} ${Build.BOARD.orEmpty()}".lowercase()
        return when {
            combined.contains("sm8750") || combined.contains("8 elite") || combined.contains("sun") ||
                combined.contains("pakala") -> "Hexagon v79 (8 Elite)"
            combined.contains("sm8650") || combined.contains("pineapple") || combined.contains("8 gen 3") ->
                "Hexagon v75 (8 Gen 3)"
            combined.contains("sm8635") || combined.contains("sm7675") || combined.contains("cliffs") ->
                "Hexagon v73 (8s / 7+ Gen 3)"
            combined.contains("sm8550") || combined.contains("kalama") || combined.contains("8 gen 2") ->
                "Hexagon v73 (8 Gen 2)"
            combined.contains("sm8475") || combined.contains("sm8450") || combined.contains("taro") ||
                combined.contains("8 gen 1") -> "Hexagon v69 (8 Gen 1)"
            combined.contains("sm8350") || combined.contains("lahaina") || combined.contains("888") ->
                "Hexagon v68 (SD 888)"
            combined.contains("sm7") || combined.contains("sm8") || combined.contains("qcom") ||
                combined.contains("qualcomm") -> "Hexagon HTP"
            else -> "Generic NPU"
        }
    }
}
