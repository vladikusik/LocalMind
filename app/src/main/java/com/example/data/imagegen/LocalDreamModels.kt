package com.example.data.imagegen

enum class DiffusionPipelineMode(val id: String) {
    TXT2IMG("txt2img"),
    IMG2IMG("img2img"),
    INPAINT("inpaint"),
    UPSCALE("upscale");

    companion object {
        fun fromId(id: String?): DiffusionPipelineMode =
            entries.firstOrNull { it.id == id } ?: TXT2IMG
    }
}

enum class DiffusionScheduler(
    val id: String,
    val displayName: String,
    val recommendedSteps: Int,
    val recommendedCfg: Float
) {
    DPM_PP_2M_KARRAS(
        id = "dpmpp_2m_karras",
        displayName = "DPM++ 2M Karras",
        recommendedSteps = 20,
        recommendedCfg = 7.0f
    ),
    EULER_A(
        id = "euler_a",
        displayName = "Euler a",
        recommendedSteps = 20,
        recommendedCfg = 7.0f
    ),
    EULER(
        id = "euler",
        displayName = "Euler",
        recommendedSteps = 20,
        recommendedCfg = 7.0f
    ),
    DDIM(
        id = "ddim",
        displayName = "DDIM",
        recommendedSteps = 24,
        recommendedCfg = 7.5f
    ),
    LCM_TURBO(
        id = "lcm",
        displayName = "LCM / Turbo",
        recommendedSteps = 6,
        recommendedCfg = 1.8f
    );

    companion object {
        fun fromId(id: String?): DiffusionScheduler =
            entries.firstOrNull { it.id == id } ?: DPM_PP_2M_KARRAS
    }
}

enum class UpscaleMethod(
    val id: String,
    val scaleFactor: Int,
    val usesDiffusion: Boolean
) {
    SMART_SHARP_2X("sharp_2x", 2, false),
    SMART_SHARP_4X("sharp_4x", 4, false),
    HIRES_DIFFUSION_2X("hires_2x", 2, true);

    companion object {
        fun fromId(id: String?): UpscaleMethod =
            entries.firstOrNull { it.id == id } ?: SMART_SHARP_2X
    }
}

data class CuratedDiffusionPreset(
    val repoQuery: String,
    val title: String,
    val styleTag: String,
    val speedHint: String
)

data class NegativePromptPreset(
    val id: String,
    val label: String,
    val prompt: String
)

object LocalDreamCatalog {
    const val HF_AUTHOR = "xororz"

    val curatedOnDeviceModels: List<CuratedDiffusionPreset> = listOf(
        CuratedDiffusionPreset(
            repoQuery = "stabilityai/sd-turbo",
            title = "SD Turbo (4-Step Fast)",
            styleTag = "Fast · Safetensors",
            speedHint = "4–8 steps · CFG 1.5–2.0"
        ),
        CuratedDiffusionPreset(
            repoQuery = "Lykon/dreamshaper-8",
            title = "DreamShaper 8",
            styleTag = "Universal · SD 1.5",
            speedHint = "16–24 steps · CFG 7.0"
        ),
        CuratedDiffusionPreset(
            repoQuery = "runwayml/stable-diffusion-v1-5",
            title = "Stable Diffusion 1.5",
            styleTag = "Classic · SD 1.5",
            speedHint = "20 steps · CFG 7.5"
        ),
        CuratedDiffusionPreset(
            repoQuery = "SG161222/Realistic_Vision_V5.1_noVAE",
            title = "Realistic Vision 5.1",
            styleTag = "Photoreal · SD 1.5",
            speedHint = "20–28 steps · CFG 6.5"
        ),
        CuratedDiffusionPreset(
            repoQuery = "emilianJR/epiCRealism",
            title = "epiCRealism",
            styleTag = "Portrait · SD 1.5",
            speedHint = "20 steps · CFG 6.0"
        ),
        CuratedDiffusionPreset(
            repoQuery = "Linaqruf/anything-v3.0",
            title = "Anything v3 (Anime)",
            styleTag = "Anime · SD 1.5",
            speedHint = "20 steps · CFG 7.0"
        ),
        CuratedDiffusionPreset(
            repoQuery = "stablediffusionapi/chilloutmix",
            title = "ChilloutMix",
            styleTag = "Photoreal · Uncensored",
            speedHint = "20 steps · CFG 7.0"
        ),
        CuratedDiffusionPreset(
            repoQuery = "city96/FLUX.1-schnell-gguf",
            title = "FLUX.1 Schnell GGUF",
            styleTag = "Next-Gen · GGUF",
            speedHint = "4 steps · High RAM"
        )
    )

    val negativePresets: List<NegativePromptPreset> = listOf(
        NegativePromptPreset(
            id = "clean_photo",
            label = "Photo Clean",
            prompt = "lowres, blurry, bad anatomy, bad hands, extra fingers, watermark, text, deformed, ugly, jpeg artifacts, overexposed"
        ),
        NegativePromptPreset(
            id = "clean_anime",
            label = "Anime Clean",
            prompt = "lowres, bad anatomy, bad hands, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, signature, watermark, blurry"
        ),
        NegativePromptPreset(
            id = "minimal",
            label = "Minimal",
            prompt = "blurry, watermark, text, low quality"
        ),
        NegativePromptPreset(
            id = "none",
            label = "Empty (Raw)",
            prompt = ""
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

    fun isNativeOnDeviceDiffusionFormat(format: String): Boolean =
        format.lowercase() in setOf("safetensors", "ckpt", "gguf", "zip")

    fun isImageModelFormat(format: String): Boolean =
        format.lowercase() in setOf("safetensors", "ckpt", "mnn", "dlc", "qnn", "zip")
}
