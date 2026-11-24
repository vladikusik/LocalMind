package com.example.data.localmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import java.io.File

data class VulkanGpuStatus(
    val supported: Boolean,
    val computeSupported: Boolean,
    val versionLabel: String,
    val gpuFamily: String,
    val hasSystemVulkanLib: Boolean,
    val hasBundledVulkanBackend: Boolean,
    val shaderCacheDir: String,
    val recommendedHybridLayers: Int
)

/**
 * Inspects Android Vulkan GPU compute capabilities and configures environment variables
 * for llama.cpp (ggml-vulkan) and LiteRT-LM GPU acceleration.
 */
object VulkanGpuConfigurator {

    // Vulkan version bit-packing: (major << 22) | (minor << 12)
    private const val VK_VERSION_1_4 = (1 shl 22) or (4 shl 12)
    private const val VK_VERSION_1_3 = (1 shl 22) or (3 shl 12)
    private const val VK_VERSION_1_2 = (1 shl 22) or (2 shl 12)
    private const val VK_VERSION_1_1 = (1 shl 22) or (1 shl 12)
    private const val VK_VERSION_1_0 = (1 shl 22)

    @Volatile
    private var cachedStatus: VulkanGpuStatus? = null

    fun configureAndInspect(
        context: Context,
        enableVulkan: Boolean = true
    ): VulkanGpuStatus {
        val status = cachedStatus ?: inspectInternal(context).also { cachedStatus = it }
        applyRuntimeEnvironment(status, enableVulkan)
        return status
    }

    private fun inspectInternal(context: Context): VulkanGpuStatus {
        val pm = context.packageManager
        val hasVulkanLevel = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val versionLabel = when {
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VK_VERSION_1_4) -> "Vulkan 1.4"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VK_VERSION_1_3) -> "Vulkan 1.3"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VK_VERSION_1_2) -> "Vulkan 1.2"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VK_VERSION_1_1) -> "Vulkan 1.1"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VK_VERSION_1_0) -> "Vulkan 1.0"
            hasVulkanLevel -> "Vulkan"
            else -> "Unavailable"
        }
        val computeSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE) ||
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VK_VERSION_1_1)
        } else {
            hasVulkanLevel
        }

        val hasSystemVulkanLib = listOf(
            "/system/lib64/libvulkan.so",
            "/vendor/lib64/libvulkan.so",
            "/system/vendor/lib64/libvulkan.so"
        ).any { runCatching { File(it).exists() }.getOrDefault(false) }

        val nativeDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val nativeFiles = if (nativeDir.isNotBlank()) {
            runCatching { File(nativeDir).listFiles()?.map { it.name }.orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val hasBundledVulkanBackend = nativeFiles.any { name ->
            name.contains("ggml-vulkan", ignoreCase = true) ||
                name.contains("ggml-vk", ignoreCase = true) ||
                name.contains("ggml-opencl", ignoreCase = true)
        }

        val shaderCacheDir = File(context.cacheDir, "vulkan_shader_cache").apply {
            if (!exists()) mkdirs()
        }.absolutePath

        val gpuFamily = detectGpuFamily()
        val supported = hasVulkanLevel || hasSystemVulkanLib

        // Suggest balanced CPU+GPU hybrid layer split based on SoC GPU tier
        val recommendedHybridLayers = when {
            !supported -> 0
            gpuFamily.contains("Adreno 8") || gpuFamily.contains("Adreno 7") || gpuFamily.contains("Immortalis") -> 24
            gpuFamily.contains("Adreno") || gpuFamily.contains("Xclipse") -> 16
            gpuFamily.contains("Mali") -> 12
            else -> 8
        }

        return VulkanGpuStatus(
            supported = supported,
            computeSupported = computeSupported,
            versionLabel = versionLabel,
            gpuFamily = gpuFamily,
            hasSystemVulkanLib = hasSystemVulkanLib,
            hasBundledVulkanBackend = hasBundledVulkanBackend,
            shaderCacheDir = shaderCacheDir,
            recommendedHybridLayers = recommendedHybridLayers
        )
    }

    private fun applyRuntimeEnvironment(status: VulkanGpuStatus, enableVulkan: Boolean) {
        runCatching {
            if (enableVulkan && status.supported) {
                Os.setenv("GGML_VK_VISIBLE_DEVICES", "0", true)
                Os.setenv("GGML_VK_PIPELINE_CACHE_DIR", status.shaderCacheDir, true)
                // Avoid fp16 precision bugs on older Vulkan 1.0/1.1 mobile GPUs
                if (status.versionLabel == "Vulkan 1.0") {
                    Os.setenv("GGML_VK_DISABLE_F16", "1", true)
                } else {
                    Os.unsetenv("GGML_VK_DISABLE_F16")
                }
            } else {
                Os.setenv("GGML_VK_VISIBLE_DEVICES", "-1", true)
            }
        }
    }

    private fun detectGpuFamily(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MODEL.orEmpty()} ${Build.SOC_MANUFACTURER.orEmpty()}"
        } else {
            ""
        }
        val combined = "$soc ${Build.HARDWARE.orEmpty()} ${Build.BOARD.orEmpty()}".lowercase()
        return when {
            combined.contains("sm8750") || combined.contains("8 elite") || combined.contains("sun") ->
                "Qualcomm Adreno 830"
            combined.contains("sm8650") || combined.contains("pineapple") || combined.contains("8 gen 3") ->
                "Qualcomm Adreno 750"
            combined.contains("sm8550") || combined.contains("kalama") || combined.contains("8 gen 2") ->
                "Qualcomm Adreno 740"
            combined.contains("sm8475") || combined.contains("sm8450") || combined.contains("taro") ->
                "Qualcomm Adreno 730"
            combined.contains("qcom") || combined.contains("qualcomm") || combined.contains("sm8") || combined.contains("sm7") ->
                "Qualcomm Adreno GPU"
            combined.contains("mt6989") || combined.contains("mt6991") || combined.contains("dimensity 9") ->
                "ARM Immortalis GPU"
            combined.contains("mediatek") || combined.contains("mt6") || combined.contains("tensor") ->
                "ARM Mali / Immortalis GPU"
            combined.contains("exynos") || combined.contains("s5e") ->
                "Samsung Xclipse / Mali GPU"
            else -> "Mobile Vulkan GPU"
        }
    }
}
