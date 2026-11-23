package com.example.data.imagegen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.example.data.localmodel.LocalHardwareBackend
import com.llamatik.library.platform.StableDiffusionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class ImageGenBackendMode(
    val id: String,
    val displayName: String,
    val defaultUrl: String
) {
    ON_DEVICE_SD_CPP(
        id = "on_device_sd",
        displayName = "On-Device Studio",
        defaultUrl = ""
    ),
    LOCAL_A1111_FORGE(
        id = "local_a1111",
        displayName = "PC WebUI (A1111/Forge)",
        defaultUrl = "http://10.0.2.2:7860"
    ),
    LOCAL_OPENAI_IMAGES(
        id = "local_openai",
        displayName = "Local OpenAI API",
        defaultUrl = "http://10.0.2.2:1234/v1"
    );

    companion object {
        fun fromId(id: String?): ImageGenBackendMode =
            entries.firstOrNull { it.id == id } ?: ON_DEVICE_SD_CPP
    }
}

data class ImageGenRequest(
    val mode: ImageGenBackendMode,
    val pipelineMode: DiffusionPipelineMode = DiffusionPipelineMode.TXT2IMG,
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val scheduler: DiffusionScheduler = DiffusionScheduler.DPM_PP_2M_KARRAS,
    val clipSkip: Int = 1,
    val hiResFix: Boolean = false,
    val hiResStrength: Float = 0.38f,
    val localModelPath: String?,
    val localModelName: String,
    val serverUrl: String,
    val serverApiKey: String,
    val serverModel: String,
    val hardwareBackend: LocalHardwareBackend,
    val threads: Int,
    val initImagePath: String? = null,
    val img2imgStrength: Float = 0.65f,
    val maskStrokes: List<MaskStroke> = emptyList(),
    val invertMask: Boolean = false,
    val maskFeatherPx: Int = 6
)

data class ImageUpscaleRequest(
    val sourceImagePath: String,
    val method: UpscaleMethod,
    val prompt: String,
    val negativePrompt: String,
    val denoiseStrength: Float,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val localModelPath: String?,
    val localModelName: String,
    val hardwareBackend: LocalHardwareBackend,
    val threads: Int
)

data class ImageGenResult(
    val file: File,
    val width: Int,
    val height: Int,
    val seed: Long,
    val durationMs: Long,
    val engineLabel: String,
    val modelName: String
)

data class ImageStudioUiState(
    val pipelineMode: DiffusionPipelineMode = DiffusionPipelineMode.TXT2IMG,
    val prompt: String = "",
    val negativePrompt: String = LocalDreamCatalog.negativePresets.first().prompt,
    val seedInput: String = "",
    val seedLocked: Boolean = false,
    val initImagePath: String? = null,
    val img2imgStrength: Float = 0.65f,
    val maskStrokes: List<MaskStroke> = emptyList(),
    val invertMask: Boolean = false,
    val maskFeatherPx: Int = 6,
    val upscaleMethod: UpscaleMethod = UpscaleMethod.SMART_SHARP_2X,
    val upscaleDenoise: Float = 0.35f,
    val isGenerating: Boolean = false,
    val isEnhancingPrompt: Boolean = false,
    val progressLabel: String? = null,
    val lastError: String? = null,
    val serverModels: List<String> = emptyList(),
    val isProbingServer: Boolean = false,
    val serverStatus: String? = null,
    val selectedGalleryImageId: Long? = null
)

class ImageStudioStateHolder {
    private val _state = MutableStateFlow(ImageStudioUiState())
    val state: StateFlow<ImageStudioUiState> = _state.asStateFlow()

    fun update(transform: (ImageStudioUiState) -> ImageStudioUiState) {
        _state.update(transform)
    }
}

class LocalImageGenerator(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val lock = Any()

    @Volatile
    private var loadedSdModelPath: String? = null

    @Volatile
    private var loadedSdThreads: Int = -1

    private val outputDir: File
        get() = File(context.filesDir, "generated_images").apply { if (!exists()) mkdirs() }

    private val refImageDir: File
        get() = File(context.cacheDir, "img2img_refs").apply { if (!exists()) mkdirs() }

    private val unpackedSdDir: File
        get() = File(context.filesDir, "sd_models").apply { if (!exists()) mkdirs() }

    suspend fun generate(
        request: ImageGenRequest,
        onStatus: (String) -> Unit = {}
    ): Result<ImageGenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPrompt = request.prompt.trim()
            require(cleanPrompt.isNotEmpty()) { "Prompt cannot be empty." }

            val effectiveSeed = if (request.seed >= 0L) {
                request.seed
            } else {
                Random.nextLong(1L, 2_147_483_647L)
            }

            when (request.mode) {
                ImageGenBackendMode.ON_DEVICE_SD_CPP -> generateOnDeviceSdCpp(
                    request = request.copy(prompt = cleanPrompt, seed = effectiveSeed),
                    onStatus = onStatus
                )

                ImageGenBackendMode.LOCAL_A1111_FORGE -> generateViaA1111(
                    request = request.copy(prompt = cleanPrompt, seed = effectiveSeed),
                    onStatus = onStatus
                )

                ImageGenBackendMode.LOCAL_OPENAI_IMAGES -> generateViaOpenAiImages(
                    request = request.copy(prompt = cleanPrompt, seed = effectiveSeed),
                    onStatus = onStatus
                )
            }
        }
    }

    /**
     * Built-in On-Device Upscaler (2x/4x Instant Detail Sharp or 2x HiRes Diffusion Fix).
     * Mirrors Local Dream's Upscaler / UltraFix directly inside LocalMind.
     */
    suspend fun upscaleImage(
        request: ImageUpscaleRequest,
        onStatus: (String) -> Unit = {}
    ): Result<ImageGenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val srcFile = File(request.sourceImagePath)
            if (!srcFile.exists()) {
                throw IOException("Select or generate an image to upscale first.")
            }

            val startMs = System.currentTimeMillis()
            val effectiveSeed = if (request.seed >= 0L) {
                request.seed
            } else {
                Random.nextLong(1L, 2_147_483_647L)
            }

            if (!request.method.usesDiffusion) {
                onStatus("Upscaling ${request.method.scaleFactor}× on-device (progressive sharp enhance)…")
                val upscaled = InpaintMaskProcessor.upscaleBitmapSharp(
                    sourcePath = srcFile.absolutePath,
                    scaleFactor = request.method.scaleFactor,
                    sharpenStrength = if (request.method.scaleFactor >= 4) 0.68f else 0.52f
                )
                val w = upscaled.width
                val h = upscaled.height
                val outFile = writePngFile(upscaled)
                upscaled.recycle()
                return@runCatching ImageGenResult(
                    file = outFile,
                    width = w,
                    height = h,
                    seed = effectiveSeed,
                    durationMs = System.currentTimeMillis() - startMs,
                    engineLabel = "On-Device Upscaler ${request.method.scaleFactor}×",
                    modelName = "Detail Sharp ${request.method.scaleFactor}×"
                )
            }

            // HiRes Diffusion 2x Refinement Pass (on-device stable-diffusion.cpp)
            val resolvedModel = resolveRunnableCheckpoint(request.localModelPath, onStatus)
            val threads = request.threads.coerceIn(1, 8)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcFile.absolutePath, opts)
            val baseW = opts.outWidth.takeIf { it > 0 } ?: 512
            val baseH = opts.outHeight.takeIf { it > 0 } ?: 512
            val targetW = alignTo64((baseW * 2).coerceIn(512, 1024))
            val targetH = alignTo64((baseH * 2).coerceIn(512, 1024))
            val steps = request.steps.coerceIn(4, 30)
            val cfg = request.cfgScale.coerceIn(1.0f, 15.0f)
            val denoise = request.denoiseStrength.coerceIn(0.15f, 0.70f)

            onStatus("Pre-upscaling image to $targetW×$targetH…")
            val preUpscaled = InpaintMaskProcessor.upscaleBitmapSharp(
                sourcePath = srcFile.absolutePath,
                scaleFactor = 2,
                sharpenStrength = 0.30f
            )
            val resizedForSd = if (preUpscaled.width != targetW || preUpscaled.height != targetH) {
                Bitmap.createScaledBitmap(preUpscaled, targetW, targetH, true).also { preUpscaled.recycle() }
            } else {
                preUpscaled
            }
            val initRgba = bitmapToRgba(resizedForSd)
            resizedForSd.recycle()

            val rgbaBytes = synchronized(lock) {
                ensureSdModelLoaded(resolvedModel, threads, onStatus)
                onStatus("Running HiRes Diffusion 2× pass ($targetW×$targetH · $steps steps)…")
                StableDiffusionBridge.img2img(
                    initImageRgba = initRgba,
                    initImageW = targetW,
                    initImageH = targetH,
                    prompt = request.prompt.ifBlank { "masterpiece, ultra sharp, intricate detail, high resolution" },
                    negativePrompt = request.negativePrompt.ifBlank { null },
                    width = targetW,
                    height = targetH,
                    steps = steps,
                    cfgScale = cfg,
                    strength = denoise,
                    seed = effectiveSeed
                )
            }

            val finalBitmap = rgbaToBitmap(rgbaBytes, targetW, targetH)
            val outFile = writePngFile(finalBitmap)
            finalBitmap.recycle()

            ImageGenResult(
                file = outFile,
                width = targetW,
                height = targetH,
                seed = effectiveSeed,
                durationMs = System.currentTimeMillis() - startMs,
                engineLabel = "HiRes Fix 2× (${request.hardwareBackend.id.uppercase()} · ${threads}T)",
                modelName = request.localModelName.ifBlank { resolvedModel.nameWithoutExtension }
            )
        }
    }

    private fun generateOnDeviceSdCpp(
        request: ImageGenRequest,
        onStatus: (String) -> Unit
    ): ImageGenResult {
        val modelFile = resolveRunnableCheckpoint(request.localModelPath, onStatus)
        val startMs = System.currentTimeMillis()
        val threads = request.threads.coerceIn(1, 8)
        val width = alignTo64(request.width.coerceIn(256, 1024))
        val height = alignTo64(request.height.coerceIn(256, 1024))
        val steps = request.steps.coerceIn(1, 50)
        val cfg = request.cfgScale.coerceIn(1.0f, 15.0f)

        val initPath = request.initImagePath?.takeIf { File(it).exists() }
        val isInpaint = request.pipelineMode == DiffusionPipelineMode.INPAINT && initPath != null
        val isImg2Img = request.pipelineMode == DiffusionPipelineMode.IMG2IMG && initPath != null

        var inpaintMaskBitmap: Bitmap? = null

        val baseBitmap = try {
            val rgbaBytes = synchronized(lock) {
                ensureSdModelLoaded(modelFile, threads, onStatus)

                when {
                    isInpaint -> {
                        if (request.maskStrokes.isEmpty() && !request.invertMask) {
                            throw IOException("Paint the area you want to edit on the mask canvas first.")
                        }
                        onStatus("Building inpaint mask & running diffusion ($width×$height · $steps steps)…")
                        val maskBmp = InpaintMaskProcessor.renderMaskBitmap(
                            strokes = request.maskStrokes,
                            width = width,
                            height = height,
                            invert = request.invertMask
                        )
                        inpaintMaskBitmap = maskBmp
                        val seededInit = InpaintMaskProcessor.prepareInpaintInitBitmap(
                            sourcePath = initPath,
                            maskBitmap = maskBmp,
                            width = width,
                            height = height,
                            seed = request.seed
                        )
                        val initRgba = bitmapToRgba(seededInit)
                        seededInit.recycle()
                        StableDiffusionBridge.img2img(
                            initImageRgba = initRgba,
                            initImageW = width,
                            initImageH = height,
                            prompt = request.prompt,
                            negativePrompt = request.negativePrompt.ifBlank { null },
                            width = width,
                            height = height,
                            steps = steps,
                            cfgScale = cfg,
                            strength = request.img2imgStrength.coerceIn(0.25f, 0.99f),
                            seed = request.seed
                        )
                    }

                    isImg2Img -> {
                        onStatus("Running on-device img2img ($width×$height · $steps steps)…")
                        val (initRgba, initW, initH) = loadRgbaBytes(initPath, width, height)
                        StableDiffusionBridge.img2img(
                            initImageRgba = initRgba,
                            initImageW = initW,
                            initImageH = initH,
                            prompt = request.prompt,
                            negativePrompt = request.negativePrompt.ifBlank { null },
                            width = width,
                            height = height,
                            steps = steps,
                            cfgScale = cfg,
                            strength = request.img2imgStrength.coerceIn(0.05f, 0.99f),
                            seed = request.seed
                        )
                    }

                    else -> {
                        onStatus("Generating $width×$height on-device ($steps steps · ${request.scheduler.displayName})…")
                        StableDiffusionBridge.txt2img(
                            prompt = request.prompt,
                            negativePrompt = request.negativePrompt.ifBlank { null },
                            width = width,
                            height = height,
                            steps = steps,
                            cfgScale = cfg,
                            seed = request.seed
                        )
                    }
                }
            }

            val diffused = rgbaToBitmap(rgbaBytes, width, height)
            if (isInpaint && inpaintMaskBitmap != null && initPath != null) {
                onStatus("Compositing feathered inpaint mask…")
                val blended = InpaintMaskProcessor.compositeInpaintResult(
                    originalPath = initPath,
                    diffusedBitmap = diffused,
                    maskBitmap = inpaintMaskBitmap!!,
                    featherRadiusPx = request.maskFeatherPx
                )
                diffused.recycle()
                blended
            } else {
                diffused
            }
        } finally {
            inpaintMaskBitmap?.recycle()
        }

        // Optional Local Dream-style HiRes Fix 2x second pass
        val finalBitmap = if (request.hiResFix && !isInpaint && maxOf(width, height) <= 512) {
            val hiW = alignTo64((width * 2).coerceAtMost(1024))
            val hiH = alignTo64((height * 2).coerceAtMost(1024))
            val hiSteps = (steps / 2).coerceIn(6, 16)
            onStatus("Running HiRes Fix 2× ($hiW×$hiH · $hiSteps steps)…")
            val scaled = Bitmap.createScaledBitmap(baseBitmap, hiW, hiH, true)
            baseBitmap.recycle()
            val hiRgba = bitmapToRgba(scaled)
            scaled.recycle()

            val refinedBytes = synchronized(lock) {
                ensureSdModelLoaded(modelFile, threads, onStatus)
                StableDiffusionBridge.img2img(
                    initImageRgba = hiRgba,
                    initImageW = hiW,
                    initImageH = hiH,
                    prompt = request.prompt,
                    negativePrompt = request.negativePrompt.ifBlank { null },
                    width = hiW,
                    height = hiH,
                    steps = hiSteps,
                    cfgScale = cfg,
                    strength = request.hiResStrength.coerceIn(0.20f, 0.55f),
                    seed = request.seed + 1L
                )
            }
            rgbaToBitmap(refinedBytes, hiW, hiH)
        } else {
            baseBitmap
        }

        val finalW = finalBitmap.width
        val finalH = finalBitmap.height
        val outFile = writePngFile(finalBitmap)
        finalBitmap.recycle()

        val pipelineTag = when {
            isInpaint -> "Inpaint"
            isImg2Img -> "Img2Img"
            request.hiResFix -> "HiRes"
            else -> "Txt2Img"
        }

        return ImageGenResult(
            file = outFile,
            width = finalW,
            height = finalH,
            seed = request.seed,
            durationMs = System.currentTimeMillis() - startMs,
            engineLabel = "On-Device $pipelineTag · ${request.scheduler.displayName} (${request.hardwareBackend.id.uppercase()})",
            modelName = request.localModelName.ifBlank { modelFile.nameWithoutExtension }
        )
    }

    private fun resolveRunnableCheckpoint(
        localModelPath: String?,
        onStatus: (String) -> Unit
    ): File {
        val modelPath = localModelPath?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "No local diffusion model selected. Download a ready-to-run model (SD Turbo, DreamShaper 8, SD 1.5) in the Model Manager below or import a .safetensors / .ckpt / .gguf file."
            )
        val rawFile = File(modelPath)
        if (!rawFile.exists()) {
            throw IOException("Diffusion model file not found on disk: ${rawFile.name}")
        }

        val ext = rawFile.extension.lowercase()
        if (ext == "zip") {
            val targetDir = File(unpackedSdDir, rawFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val existingWeight = findNativeWeightInDir(targetDir)
            if (existingWeight != null) return existingWeight

            onStatus("Unpacking ${rawFile.name} into local sd_models/…")
            val extracted = InpaintMaskProcessor.extractModelZipSafely(rawFile, targetDir)
            val weight = extracted.firstOrNull {
                it.extension.lowercase() in setOf("safetensors", "ckpt", "gguf")
            }
            if (weight != null) return weight
            throw IOException(
                "Archive '${rawFile.name}' contains QNN/MNN split binaries rather than a unified .safetensors/.ckpt/.gguf checkpoint. Download a unified Stable Diffusion .safetensors or .gguf model from the Model Manager below to run 100% natively inside LocalMind."
            )
        }

        if (ext in setOf("mnn", "dlc", "bin", "qnn")) {
            throw IOException(
                "The file '${rawFile.name}' is a Qualcomm QNN/MNN split weight that requires proprietary external QNN binaries. Select or download a unified .safetensors, .ckpt, or SD .gguf model (e.g. SD Turbo, DreamShaper 8, ChilloutMix) to run completely natively inside LocalMind."
            )
        }

        return rawFile
    }

    private fun findNativeWeightInDir(dir: File): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        return dir.walkTopDown().firstOrNull { file ->
            file.isFile && file.extension.lowercase() in setOf("safetensors", "ckpt", "gguf")
        }
    }

    private fun ensureSdModelLoaded(
        modelFile: File,
        threads: Int,
        onStatus: (String) -> Unit
    ) {
        if (loadedSdModelPath == modelFile.absolutePath && loadedSdThreads == threads) return
        onStatus("Loading ${modelFile.name} into native diffusion engine…")
        if (loadedSdModelPath != null) {
            runCatching { StableDiffusionBridge.release() }
            loadedSdModelPath = null
        }
        val ok = StableDiffusionBridge.initModel(
            modelPath = modelFile.absolutePath,
            threads = threads
        )
        if (!ok) {
            throw IOException(
                "Native engine failed to load '${modelFile.name}'. Verify it is a valid Stable Diffusion / Flux checkpoint (.safetensors / .ckpt / .gguf) and that enough RAM is free."
            )
        }
        loadedSdModelPath = modelFile.absolutePath
        loadedSdThreads = threads
    }

    private fun generateViaA1111(
        request: ImageGenRequest,
        onStatus: (String) -> Unit
    ): ImageGenResult {
        val baseUrl = request.serverUrl.trim().trimEnd('/')
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "Enter a valid local Automatic1111 / Forge server URL (e.g. http://10.0.2.2:7860)."
        }

        val startMs = System.currentTimeMillis()
        val initPath = request.initImagePath?.takeIf { File(it).exists() }
        val isImg2Img = initPath != null &&
            (request.pipelineMode == DiffusionPipelineMode.IMG2IMG || request.pipelineMode == DiffusionPipelineMode.INPAINT)
        val endpoint = if (isImg2Img) "$baseUrl/sdapi/v1/img2img" else "$baseUrl/sdapi/v1/txt2img"

        onStatus(if (isImg2Img) "Sending img2img to PC WebUI…" else "Generating on PC WebUI (${request.steps} steps)…")

        val payload = JSONObject().apply {
            put("prompt", request.prompt)
            put("negative_prompt", request.negativePrompt)
            put("width", alignTo64(request.width.coerceIn(256, 1536)))
            put("height", alignTo64(request.height.coerceIn(256, 1536)))
            put("steps", request.steps.coerceIn(1, 60))
            put("cfg_scale", request.cfgScale.toDouble())
            put("seed", request.seed)
            put("sampler_name", request.scheduler.displayName)
            put("send_images", true)
            put("save_images", false)
            if (isImg2Img && initPath != null) {
                put("init_images", JSONArray().put(encodeImageFileBase64(initPath)))
                put("denoising_strength", request.img2imgStrength.coerceIn(0.05f, 0.99f).toDouble())
                if (request.pipelineMode == DiffusionPipelineMode.INPAINT &&
                    (request.maskStrokes.isNotEmpty() || request.invertMask)
                ) {
                    val maskBmp = InpaintMaskProcessor.renderMaskBitmap(
                        strokes = request.maskStrokes,
                        width = alignTo64(request.width),
                        height = alignTo64(request.height),
                        invert = request.invertMask
                    )
                    put("mask", encodeBitmapBase64(maskBmp))
                    put("mask_blur", request.maskFeatherPx)
                    maskBmp.recycle()
                }
            }
            val overrideSettings = JSONObject().apply {
                put("filter_nsfw", false)
                put("CLIP_stop_at_last_layers", request.clipSkip.coerceIn(1, 2))
                if (request.serverModel.isNotBlank()) {
                    put("sd_model_checkpoint", request.serverModel)
                }
            }
            put("override_settings", overrideSettings)
            put("override_settings_restore_afterwards", false)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        if (request.serverApiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${request.serverApiKey.trim()}")
        }

        val imageBytes = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("PC WebUI HTTP ${resp.code}: ${body.take(240)}")
            }
            val json = JSONObject(body)
            val images = json.optJSONArray("images")
            val b64 = images?.optString(0)?.substringAfter(",", images.optString(0)).orEmpty()
            if (b64.isBlank()) {
                throw IOException("Server returned no image payload.")
            }
            Base64.decode(b64, Base64.DEFAULT)
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IOException("Failed to decode PNG image returned by server.")
        val actualW = bitmap.width
        val actualH = bitmap.height
        val outFile = writePngFile(bitmap)
        bitmap.recycle()

        return ImageGenResult(
            file = outFile,
            width = actualW,
            height = actualH,
            seed = request.seed,
            durationMs = System.currentTimeMillis() - startMs,
            engineLabel = "PC WebUI (${request.scheduler.displayName})",
            modelName = request.serverModel.ifBlank { "Active WebUI Checkpoint" }
        )
    }

    private fun generateViaOpenAiImages(
        request: ImageGenRequest,
        onStatus: (String) -> Unit
    ): ImageGenResult {
        val baseUrl = request.serverUrl.trim().trimEnd('/')
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "Enter a valid Local OpenAI Images API base URL."
        }
        val endpoint = if (baseUrl.endsWith("/images/generations")) {
            baseUrl
        } else {
            "$baseUrl/images/generations"
        }

        val startMs = System.currentTimeMillis()
        onStatus("Generating via Local OpenAI Images API…")

        val payload = JSONObject().apply {
            if (request.serverModel.isNotBlank()) {
                put("model", request.serverModel)
            }
            put("prompt", request.prompt)
            if (request.negativePrompt.isNotBlank()) {
                put("negative_prompt", request.negativePrompt)
            }
            put("n", 1)
            put("size", "${alignTo64(request.width)}x${alignTo64(request.height)}")
            put("steps", request.steps)
            put("cfg_scale", request.cfgScale.toDouble())
            put("seed", request.seed)
            put("response_format", "b64_json")
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
        if (request.serverApiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${request.serverApiKey.trim()}")
        }

        val imageBytes = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Images API HTTP ${resp.code}: ${body.take(240)}")
            }
            val json = JSONObject(body)
            val first = json.optJSONArray("data")?.optJSONObject(0)
                ?: throw IOException("API returned empty data array.")
            val b64 = first.optString("b64_json")
            if (b64.isNotBlank()) {
                Base64.decode(b64.substringAfter(",", b64), Base64.DEFAULT)
            } else {
                val imgUrl = first.optString("url")
                if (imgUrl.isBlank()) throw IOException("API returned neither b64_json nor url.")
                val dlReq = Request.Builder().url(imgUrl).get().build()
                httpClient.newCall(dlReq).execute().use { dlResp ->
                    if (!dlResp.isSuccessful) throw IOException("Failed to fetch generated image URL.")
                    dlResp.body?.bytes() ?: throw IOException("Empty image stream.")
                }
            }
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IOException("Failed to decode returned image bytes.")
        val actualW = bitmap.width
        val actualH = bitmap.height
        val outFile = writePngFile(bitmap)
        bitmap.recycle()

        return ImageGenResult(
            file = outFile,
            width = actualW,
            height = actualH,
            seed = request.seed,
            durationMs = System.currentTimeMillis() - startMs,
            engineLabel = "Local OpenAI Images",
            modelName = request.serverModel.ifBlank { "Local Image Model" }
        )
    }

    suspend fun probeServerModels(
        mode: ImageGenBackendMode,
        serverUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = serverUrl.trim().trimEnd('/')
            require(base.startsWith("http://") || base.startsWith("https://")) {
                "Server URL must start with http:// or https://"
            }
            val url = when (mode) {
                ImageGenBackendMode.LOCAL_A1111_FORGE -> "$base/sdapi/v1/sd-models"
                ImageGenBackendMode.LOCAL_OPENAI_IMAGES -> "$base/models"
                ImageGenBackendMode.ON_DEVICE_SD_CPP -> return@runCatching emptyList()
            }
            val req = Request.Builder()
                .url(url)
                .get()
                .apply {
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
                }
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.message}")
                if (mode == ImageGenBackendMode.LOCAL_A1111_FORGE) {
                    val arr = JSONArray(body)
                    buildList {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val title = obj.optString("title").ifBlank { obj.optString("model_name") }
                            if (title.isNotBlank()) add(title)
                        }
                    }
                } else {
                    val obj = JSONObject(body)
                    val data = obj.optJSONArray("data") ?: JSONArray()
                    buildList {
                        for (i in 0 until data.length()) {
                            val id = data.optJSONObject(i)?.optString("id").orEmpty()
                            if (id.isNotBlank()) add(id)
                        }
                    }
                }
            }
        }
    }

    suspend fun importReferencePhoto(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dest = File(refImageDir, "ref_${UUID.randomUUID().toString().take(8)}.png")
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                    ?: throw IOException("Could not decode selected photo.")
                FileOutputStream(dest).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                bmp.recycle()
            } ?: throw IOException("Cannot open selected photo URI.")
            dest.absolutePath
        }
    }

    /**
     * Zero-permission Android MediaStore save (Google Play Policy compliant).
     */
    suspend fun saveToDeviceGallery(localPath: String, titlePrefix: String = "LocalMind"): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val src = File(localPath)
                if (!src.exists()) throw IOException("Image file no longer exists.")

                val fileName = "${titlePrefix}_${System.currentTimeMillis()}.png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/LocalMind"
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = context.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                val itemUri = resolver.insert(collection, values)
                    ?: throw IOException("Failed to create gallery MediaStore entry.")

                resolver.openOutputStream(itemUri)?.use { output ->
                    src.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Failed to open MediaStore output stream.")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    resolver.update(itemUri, doneValues, null, null)
                }
                fileName
            }
        }

    fun deleteFile(localPath: String) {
        runCatching {
            val f = File(localPath)
            if (f.exists()) f.delete()
        }
    }

    fun release() {
        synchronized(lock) {
            if (loadedSdModelPath != null) {
                runCatching { StableDiffusionBridge.release() }
                loadedSdModelPath = null
                loadedSdThreads = -1
            }
        }
    }

    private fun writePngFile(bitmap: Bitmap): File {
        val outFile = File(outputDir, "img_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png")
        FileOutputStream(outFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
        }
        return outFile
    }

    private fun bitmapToRgba(bitmap: Bitmap): ByteArray {
        val argb = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val buf = ByteBuffer.allocate(argb.width * argb.height * 4)
        argb.copyPixelsToBuffer(buf)
        if (argb !== bitmap) argb.recycle()
        return buf.array()
    }

    private fun rgbaToBitmap(rgbaBytes: ByteArray, width: Int, height: Int): Bitmap {
        val expectedSize = width * height * 4
        if (rgbaBytes.size < expectedSize) {
            throw IOException("Native diffusion engine returned incomplete image buffer (${rgbaBytes.size} bytes).")
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaBytes, 0, expectedSize))
        return bitmap
    }

    private fun loadRgbaBytes(path: String, targetW: Int, targetH: Int): Triple<ByteArray, Int, Int> {
        val raw = BitmapFactory.decodeFile(path)
            ?: throw IOException("Cannot decode img2img reference image.")
        val scaled = if (raw.width != targetW || raw.height != targetH) {
            Bitmap.createScaledBitmap(raw, targetW, targetH, true).also { raw.recycle() }
        } else {
            raw
        }
        val bytes = bitmapToRgba(scaled)
        val w = scaled.width
        val h = scaled.height
        scaled.recycle()
        return Triple(bytes, w, h)
    }

    private fun encodeImageFileBase64(path: String): String {
        val bmp = BitmapFactory.decodeFile(path)
            ?: throw IOException("Cannot decode reference image.")
        val res = encodeBitmapBase64(bmp)
        bmp.recycle()
        return res
    }

    private fun encodeBitmapBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 95, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun alignTo64(value: Int): Int = ((value + 31) / 64).coerceAtLeast(4) * 64
}
