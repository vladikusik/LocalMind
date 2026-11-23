package com.example.data.imagegen

data class LocalDreamRepoPreset(
    val repoId: String,
    val title: String,
    val runtimeTag: String,
    val chipHint: String
)

object LocalDreamCatalog {
    const val GITHUB_URL = "https://github.com/xororz/local-dream"
    const val DEFAULT_SERVER_URL = "http://127.0.0.1:8081"
    const val HF_AUTHOR = "xororz"

    val curatedRepos: List<LocalDreamRepoPreset> = listOf(
        LocalDreamRepoPreset(
            repoId = "xororz/sd-qnn",
            title = "Stable Diffusion 1.5 (QNN NPU)",
            runtimeTag = "QNN NPU",
            chipHint = "Snapdragon 8 Gen 1 / 2 / 3 / Elite"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/sdxl-qnn",
            title = "SDXL (QNN NPU)",
            runtimeTag = "SDXL NPU",
            chipHint = "Snapdragon 8 Gen 3 / 8 Elite"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/sd-mnn",
            title = "Stable Diffusion (MNN CPU/OpenCL)",
            runtimeTag = "MNN CPU/GPU",
            chipHint = "All Android devices"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/anima-qnn",
            title = "Anima Pencil (QNN NPU)",
            runtimeTag = "QNN NPU",
            chipHint = "Snapdragon Hexagon NPU"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/ChilloutMix",
            title = "ChilloutMix (Photoreal)",
            runtimeTag = "Local Dream",
            chipHint = "QNN / MNN"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/CuteYukiMix",
            title = "CuteYukiMix (Anime)",
            runtimeTag = "Local Dream",
            chipHint = "QNN / MNN"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/QteaMix",
            title = "QteaMix (Stylized)",
            runtimeTag = "Local Dream",
            chipHint = "QNN / MNN"
        ),
        LocalDreamRepoPreset(
            repoId = "xororz/upscaler",
            title = "Real-ESRGAN Upscaler",
            runtimeTag = "Upscaler",
            chipHint = "NPU / MNN"
        )
    )

    fun isLocalDreamRepo(repoId: String): Boolean {
        val l = repoId.lowercase()
        return l.startsWith("xororz/") ||
            l.contains("sd-qnn") ||
            l.contains("sdxl-qnn") ||
            l.contains("sd-mnn") ||
            l.contains("local-dream")
    }

    fun isLocalDreamFormat(format: String): Boolean =
        format.lowercase() in setOf("mnn", "dlc", "qnn", "zip")

    fun isImageModelFormat(format: String): Boolean =
        format.lowercase() in setOf("safetensors", "ckpt", "mnn", "dlc", "qnn", "zip")
}
