package com.example.data.localmodel

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class HardwareAcceleratorInfo(
    val socName: String,
    val npuVendor: String,
    val npuSupported: Boolean,
    val npuDetail: String,
    val hasOpenCl: Boolean,
    val hasVulkan: Boolean,
    val hasNnapi: Boolean,
    val qnnBundled: Boolean,
    val hexagonArch: String,
    val cpuCores: Int,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val abi: String
)

object HardwareAcceleratorInspector {

    fun inspect(context: Context): HardwareAcceleratorInfo {
        // Configure ADSP_LIBRARY_PATH and inspect bundled QNN HTP / LiteRT Dispatch libs immediately
        val qnnStatus = QnnRuntimeConfigurator.configureAndInspect(context)

        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.orEmpty().trim()
        } else {
            ""
        }
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty().trim()
        } else {
            ""
        }
        val hardware = Build.HARDWARE.orEmpty().trim()
        val board = Build.BOARD.orEmpty().trim()
        val combined = "$socManufacturer $socModel $hardware $board".lowercase()

        val socName = listOf(socManufacturer, socModel)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
            .ifBlank {
                listOf(hardware, board)
                    .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    .distinct()
                    .joinToString(" / ")
                    .ifBlank { Build.MODEL ?: "Android Device" }
            }

        val hasSystemQnn = existsAny(
            "/vendor/lib64/libQnnHtp.so",
            "/vendor/lib64/libcdsprpc.so",
            "/system/vendor/lib64/libcdsprpc.so"
        )
        val hasNeuron = existsAny(
            "/vendor/lib64/libneuron_adapter.so",
            "/vendor/lib64/libneuron_runtime.so",
            "/vendor/lib64/libapuwareutils.mtk.so"
        )
        val hasOpenCl = existsAny(
            "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/lib64/libOpenCL.so"
        )
        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1

        val qnnBundledReady = qnnStatus.isQnnHtpBundled || qnnStatus.isLiteRtDispatchBundled

        val (npuVendor, npuSupported, npuDetail) = when {
            combined.contains("qcom") || combined.contains("qualcomm") || combined.contains("snapdragon") ||
                combined.contains("sm8") || combined.contains("sm7") || hasSystemQnn -> {
                val detail = buildString {
                    append(qnnStatus.hexagonArch)
                    if (qnnBundledReady) {
                        append(" · QNN HTP bundled (${qnnStatus.bundledQnnLibCount} libs)")
                    } else if (hasSystemQnn) {
                        append(" · System CDSP/QNN driver")
                    }
                    if (qnnStatus.adspPathConfigured) {
                        append(" · ADSP ready")
                    }
                }
                Triple("Qualcomm Hexagon NPU (QNN HTP)", true, detail)
            }

            combined.contains("tensor") || combined.contains("gs101") || combined.contains("gs201") ||
                combined.contains("zuma") || combined.contains("zumapro") ||
                (Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.MODEL.contains("Pixel", ignoreCase = true)) -> {
                Triple(
                    "Google Tensor TPU / NPU",
                    true,
                    "Google Tensor NPU · LiteRT-LM NPU delegate ready"
                )
            }

            combined.contains("mediatek") || combined.contains("mt6") || combined.contains("dimensity") || hasNeuron -> {
                Triple(
                    "MediaTek APU (NeuroPilot)",
                    true,
                    if (hasNeuron) "NeuroPilot APU driver detected" else "MediaTek Dimensity · LiteRT NPU ready"
                )
            }

            combined.contains("exynos") || combined.contains("s5e") -> {
                Triple(
                    "Samsung Exynos NPU",
                    true,
                    "Exynos NPU / NNAPI · LiteRT delegate ready"
                )
            }

            combined.contains("ranchu") || combined.contains("goldfish") || combined.contains("emu") -> {
                Triple(
                    "NNAPI / Virtual NPU",
                    false,
                    "Emulator — NPU auto-falls back to GPU/CPU (QNN libs bundled: ${qnnStatus.bundledQnnLibCount})"
                )
            }

            hasNnapi -> {
                Triple(
                    "Android NNAPI / NPU",
                    true,
                    if (qnnBundledReady) {
                        "QNN/LiteRT runtime bundled (${qnnStatus.bundledQnnLibCount} libs) · auto GPU/CPU fallback"
                    } else {
                        "LiteRT NPU with automatic GPU/CPU fallback"
                    }
                )
            }

            else -> {
                Triple(
                    "CPU / GPU Only",
                    false,
                    "NPU unavailable on API < 27"
                )
            }
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        return HardwareAcceleratorInfo(
            socName = socName,
            npuVendor = npuVendor,
            npuSupported = npuSupported,
            npuDetail = npuDetail,
            hasOpenCl = hasOpenCl,
            hasVulkan = hasVulkan,
            hasNnapi = hasNnapi,
            qnnBundled = qnnBundledReady,
            hexagonArch = qnnStatus.hexagonArch,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalRamMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L),
            availableRamMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        )
    }

    private fun existsAny(vararg paths: String): Boolean =
        paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
}
