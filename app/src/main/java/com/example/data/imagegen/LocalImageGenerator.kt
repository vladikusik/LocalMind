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
        displayName = "On-Device (sd.cpp)",
        defaultUrl = ""
    ),
    LOCAL_A1111_FORGE(
        id = "local_a1111",
        displayName = "A1111 / Forge / WebUI",
        defaultUrl = "http://10.0.2.2:7860"
    ),
    LOCAL_OPENAI_IMAGES(
        id = "local_openai",
        displayName = "Local OpenAI Images API",
        defaultUrl = "http://10.0.2.2:1234/v1"
    );

    companion object {
        fun fromId(id: String?): ImageGenBackendMode =
            entries.firstOrNull { it.id == id } ?: ON_DEVICE_SD_CPP
    }
}

data class ImageGenRequest(
    val mode: ImageGenBackendMode,
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Float,
    val seed: Long,
    val localModelPath: String?,
    val localModelName: String,
    val serverUrl: String,
    val serverApiKey: String,
    val serverModel: String,
    val hardwareBackend: LocalHardwareBackend,
    val threads: Int,
    val initImagePath: String? = null,
    val img2imgStrength: Float = 0.65f
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
    val prompt: String = "",
    val negativePrompt: String = "lowres, blurry, bad anatomy, watermark, text, deformed, ugly, jpeg artifacts",
    val seedInput: String = "",
    val initImagePath: String? = null,
    val img2imgStrength: Float = 0.65f,
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

    private fun generateOnDeviceSdCpp(
        request: ImageGenRequest,
        onStatus: (String) -> Unit
    ): ImageGenResult {
        val modelPath = request.localModelPath?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "No local diffusion model selected. Download a Stable Diffusion / Flux (.safetensors, .ckpt, or .gguf) model from Hugging Face Hub or import one from your phone."
            )
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw IOException("Diffusion model file not found on disk: ${modelFile.name}")
        }

        val startMs = System.currentTimeMillis()
        val threads = request.threads.coerceIn(1, 8)
        val width = alignTo64(request.width.coerceIn(256, 1024))
        val height = alignTo64(request.height.coerceIn(256, 1024))
        val steps = request.steps.coerceIn(1, 50)
        val cfg = request.cfgScale.coerceIn(1.0f, 15.0f)

        val rgbaBytes = synchronized(lock) {
            if (loadedSdModelPath != modelFile.absolutePath || loadedSdThreads != threads) {
                onStatus("Loading ${modelFile.name} into stable-diffusion.cpp…")
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
                        "stable-diffusion.cpp failed to load '${modelFile.name}'. Verify the checkpoint format (.safetensors / .ckpt / SD .gguf) and available RAM."
                    )
                }
                loadedSdModelPath = modelFile.absolutePath
                loadedSdThreads = threads
            }

            val initPath = request.initImagePath?.takeIf { File(it).exists() }
            if (initPath != null) {
                onStatus("Running local img2img ($width×$height · $steps steps)…")
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
            } else {
                onStatus("Generating $width×$height locally ($steps steps · uncensored)…")
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

        val expectedSize = width * height * 4
        if (rgbaBytes.size < expectedSize) {
            throw IOException("stable-diffusion.cpp returned incomplete image buffer (${rgbaBytes.size} bytes).")
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaBytes, 0, expectedSize))

        val outFile = writePngFile(bitmap)
        bitmap.recycle()

        val durationMs = System.currentTimeMillis() - startMs
        return ImageGenResult(
            file = outFile,
            width = width,
            height = height,
            seed = request.seed,
            durationMs = durationMs,
            engineLabel = "sd.cpp (${request.hardwareBackend.id.uppercase()} · ${threads}T)",
            modelName = request.localModelName.ifBlank { modelFile.nameWithoutExtension }
        )
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
        val isImg2Img = initPath != null
        val endpoint = if (isImg2Img) "$baseUrl/sdapi/v1/img2img" else "$baseUrl/sdapi/v1/txt2img"

        onStatus(if (isImg2Img) "Sending img2img to local WebUI…" else "Generating on local WebUI (${request.steps} steps)…")

        val payload = JSONObject().apply {
            put("prompt", request.prompt)
            put("negative_prompt", request.negativePrompt)
            put("width", alignTo64(request.width.coerceIn(256, 1536)))
            put("height", alignTo64(request.height.coerceIn(256, 1536)))
            put("steps", request.steps.coerceIn(1, 60))
            put("cfg_scale", request.cfgScale.toDouble())
            put("seed", request.seed)
            put("send_images", true)
            put("save_images", false)
            if (isImg2Img && initPath != null) {
                put("init_images", JSONArray().put(encodeImageFileBase64(initPath)))
                put("denoising_strength", request.img2imgStrength.coerceIn(0.05f, 0.99f).toDouble())
            }
            val overrideSettings = JSONObject().apply {
                put("filter_nsfw", false)
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
                throw IOException("Local WebUI HTTP ${resp.code}: ${body.take(240)}")
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
            engineLabel = "Local WebUI (A1111/Forge)",
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

    private fun loadRgbaBytes(path: String, targetW: Int, targetH: Int): Triple<ByteArray, Int, Int> {
        val raw = BitmapFactory.decodeFile(path)
            ?: throw IOException("Cannot decode img2img reference image.")
        val scaled = if (raw.width != targetW || raw.height != targetH) {
            Bitmap.createScaledBitmap(raw, targetW, targetH, true).also { raw.recycle() }
        } else {
            raw
        }
        val argbBitmap = if (scaled.config != Bitmap.Config.ARGB_8888) {
            scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
        } else {
            scaled
        }
        val buf = ByteBuffer.allocate(argbBitmap.width * argbBitmap.height * 4)
        argbBitmap.copyPixelsToBuffer(buf)
        val w = argbBitmap.width
        val h = argbBitmap.height
        argbBitmap.recycle()
        return Triple(buf.array(), w, h)
    }

    private fun encodeImageFileBase64(path: String): String {
        val bmp = BitmapFactory.decodeFile(path)
            ?: throw IOException("Cannot decode reference image.")
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
        bmp.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun alignTo64(value: Int): Int = ((value + 31) / 64).coerceAtLeast(4) * 64
}
