package com.imagecompressor.app.vm

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imagecompressor.app.engine.CompressOutcome
import com.imagecompressor.app.engine.ImageCompressor
import com.imagecompressor.app.engine.Storage
import com.imagecompressor.app.engine.UnsupportedImageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

enum class Mode { SINGLE, MULTIPLE }
enum class Screen { HOME, RESULTS }
enum class Phase { PENDING, RUNNING, DONE, FAILED }
enum class SizeUnit { KB, MB }

data class SelectedImage(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val mime: String?,
    val thumb: ImageBitmap? = null
)

data class ResultItem(
    val image: SelectedImage,
    val phase: Phase = Phase.PENDING,
    val progress: Float = 0f,
    val outcome: CompressOutcome? = null,
    val error: String? = null,
    val saved: Boolean = false
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val mode: Mode = Mode.SINGLE,
    val images: List<SelectedImage> = emptyList(),
    val presetKb: Long = 100,
    val useCustom: Boolean = false,
    val customText: String = "",
    val customUnit: SizeUnit = SizeUnit.KB,
    val results: List<ResultItem> = emptyList(),
    val isRunning: Boolean = false,
    /** Target (per image) that the current/last run used. */
    val runTargetBytes: Long = 0,
    val previewFile: File? = null
) {
    /** Target size per image in bytes, or null when the custom value is missing or invalid. */
    val targetBytes: Long?
        get() {
            if (!useCustom) return presetKb * 1024
            val v = customText.replace(',', '.').toDoubleOrNull() ?: return null
            val kb = if (customUnit == SizeUnit.MB) v * 1024 else v
            return if (kb < MIN_CUSTOM_KB || kb > MAX_CUSTOM_KB) null else (kb * 1024).toLong()
        }

    val customError: Boolean
        get() = useCustom && customText.isNotBlank() && targetBytes == null

    companion object {
        const val MIN_CUSTOM_KB = 5.0
        const val MAX_CUSTOM_KB = 102400.0
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var job: Job? = null
    private val ids = AtomicLong(0)
    private val context get() = getApplication<Application>()
    private val outDir get() = File(context.cacheDir, "results")

    // ---------- selection ----------

    fun setMode(mode: Mode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode, images = emptyList()) }
    }

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val single = _state.value.mode == Mode.SINGLE
        viewModelScope.launch {
            val known = if (single) emptySet() else _state.value.images.map { it.uri }.toSet()
            val fresh = uris.distinct().filter { it !in known }.let { if (single) it.take(1) else it }
            if (fresh.isEmpty()) return@launch
            val loaded = withContext(Dispatchers.IO) { fresh.map { readInfo(it) } }
            _state.update { s ->
                s.copy(images = if (single) loaded else s.images + loaded)
            }
            val side = if (single) 1280 else 320
            for (img in loaded) {
                val thumb = withContext(Dispatchers.IO) {
                    try {
                        ImageCompressor.decodeOriented(context, img.uri, side).asImageBitmap()
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (thumb != null) {
                    _state.update { s ->
                        s.copy(images = s.images.map { if (it.id == img.id) it.copy(thumb = thumb) else it })
                    }
                }
            }
        }
    }

    fun removeImage(id: Long) = _state.update { s -> s.copy(images = s.images.filter { it.id != id }) }

    fun clearImages() = _state.update { it.copy(images = emptyList()) }

    private fun readInfo(uri: Uri): SelectedImage {
        var name = "image"
        var size = -1L
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        if (size < 0) {
            size = try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
        val mime = try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        return SelectedImage(ids.incrementAndGet(), uri, name, size, mime)
    }

    // ---------- target size ----------

    fun selectPreset(kb: Long) = _state.update { it.copy(presetKb = kb, useCustom = false) }
    fun selectCustom() = _state.update { it.copy(useCustom = true) }
    fun setCustomText(text: String) {
        val cleaned = text.filter { it.isDigit() || it == '.' || it == ',' }.take(8)
        _state.update { it.copy(customText = cleaned) }
    }

    fun setCustomUnit(unit: SizeUnit) = _state.update { it.copy(customUnit = unit) }

    // ---------- compression ----------

    fun compress() {
        val s = _state.value
        val target = s.targetBytes ?: return
        if (s.images.isEmpty() || s.isRunning) return

        clearCache()
        val items = s.images.map { ResultItem(it) }
        _state.update {
            it.copy(screen = Screen.RESULTS, results = items, isRunning = true, runTargetBytes = target)
        }

        job = viewModelScope.launch {
            // Strictly sequential: one image in memory at a time, one failure never stops the batch.
            for (item in items) {
                val id = item.image.id
                mapItem(id) { it.copy(phase = Phase.RUNNING, progress = 0f) }
                try {
                    val outcome = ImageCompressor.compress(
                        context, item.image.uri, item.image.sizeBytes, item.image.mime, target, outDir
                    ) { p -> mapItem(id) { it.copy(progress = p) } }
                    mapItem(id) { it.copy(phase = Phase.DONE, progress = 1f, outcome = outcome) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    fail(id, "This image is too large for the device's memory.")
                } catch (e: UnsupportedImageException) {
                    fail(id, "Unsupported or corrupted image. Please use a JPG or PNG.")
                } catch (e: IOException) {
                    fail(id, "Couldn't read this image. It may have been moved or deleted.")
                } catch (e: Exception) {
                    fail(id, "Something went wrong while compressing this image.")
                }
            }
            _state.update { it.copy(isRunning = false) }
        }
    }

    private fun fail(id: Long, msg: String) =
        mapItem(id) { it.copy(phase = Phase.FAILED, progress = 0f, error = msg) }

    private fun mapItem(id: Long, f: (ResultItem) -> ResultItem) {
        _state.update { s -> s.copy(results = s.results.map { if (it.image.id == id) f(it) else it }) }
    }

    fun backToHome() {
        job?.cancel()
        job = null
        clearCache()
        _state.update {
            it.copy(screen = Screen.HOME, results = emptyList(), isRunning = false, previewFile = null)
        }
    }

    // ---------- preview / save ----------

    fun openPreview(file: File) = _state.update { it.copy(previewFile = file) }
    fun closePreview() = _state.update { it.copy(previewFile = null) }

    fun saveOne(id: Long) {
        val item = _state.value.results.firstOrNull { it.image.id == id } ?: return
        val outcome = item.outcome ?: return
        if (item.saved) {
            post("Already saved to Pictures/${Storage.FOLDER}")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Storage.saveToGallery(context, outcome.file, Storage.outputName(item.image.name))
                }.isSuccess
            }
            if (ok) {
                mapItem(id) { it.copy(saved = true) }
                post("Saved to Pictures/${Storage.FOLDER}")
            } else {
                post("Couldn't save the image. Please try again.")
            }
        }
    }

    fun saveAll() {
        val pending = _state.value.results.filter { it.outcome != null && !it.saved }
        if (pending.isEmpty()) {
            post("Nothing new to save.")
            return
        }
        viewModelScope.launch {
            var saved = 0
            var failed = 0
            for (item in pending) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Storage.saveToGallery(
                            context, item.outcome!!.file, Storage.outputName(item.image.name)
                        )
                    }.isSuccess
                }
                if (ok) {
                    saved++
                    mapItem(item.image.id) { it.copy(saved = true) }
                } else failed++
            }
            post(
                if (failed == 0) "Saved $saved image${if (saved == 1) "" else "s"} to Pictures/${Storage.FOLDER}"
                else "Saved $saved, $failed couldn't be saved."
            )
        }
    }

    fun post(message: String) {
        _messages.trySend(message)
    }

    // ---------- housekeeping ----------

    private fun clearCache() {
        try {
            outDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        job?.cancel()
        clearCache()
        super.onCleared()
    }
}
