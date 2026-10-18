package com.chesstutor.nativeapp

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProgressReporter(private val callback: (String) -> Unit) {
    fun update(value: String) = callback(value)
}

class ChessRuntime(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancellations = Executors.newSingleThreadExecutor()
    @Volatile private var module: PyObject? = null
    private var initialized = false

    suspend fun call(path: String, body: JSONObject? = null, progress: (JSONObject) -> Unit = {}): Any =
        suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString()
            val request = JSONObject().put("path", path).put("method", if (body == null) "GET" else "POST").put("body", body)
            continuation.invokeOnCancellation { cancellations.execute { module?.callAttr("cancel", id) } }
            worker.execute {
                if (!continuation.isActive) return@execute
                try {
                    if (module == null) {
                        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
                        module = Python.getInstance().getModule("mobile_runtime")
                    }
                    if (!initialized && path !in listOf("/api/parse-pgn", "/api/validate-fen")) {
                        val engine = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
                        module!!.callAttr("initialize", engine.absolutePath)
                        initialized = true
                    }
                    if (!continuation.isActive) return@execute
                    val reply = JSONObject(module!!.callAttr("request", id, request.toString(),
                        ProgressReporter { if (continuation.isActive) progress(JSONObject(it)) }).toString())
                    val status = reply.getInt("status")
                    if (status != 200) {
                        if (status == 503) { module?.callAttr("close"); initialized = false }
                        throw IllegalStateException(reply.optJSONObject("body")?.optString("detail") ?: "Analysis failed.")
                    }
                    if (continuation.isActive) continuation.resume(reply.get("body"))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
}
