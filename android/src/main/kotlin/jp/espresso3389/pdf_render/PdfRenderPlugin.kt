package jp.espresso3389.pdf_render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.MODE_READ_ONLY
import android.util.SparseArray
import android.view.Surface
import androidx.annotation.NonNull
import androidx.collection.LongSparseArray
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.view.TextureRegistry
import java.io.File
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer

/** PdfRenderPlugin */
class PdfRenderPlugin: FlutterPlugin, MethodCallHandler {
  private lateinit var channel : MethodChannel
  private lateinit var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding

  private val documents: SparseArray<PdfRenderer> = SparseArray()
  private var lastDocId: Int = 0
  private val textures: SparseArray<TextureRegistry.SurfaceTextureEntry> = SparseArray()
  private val buffers: LongSparseArray<ByteBuffer> = LongSparseArray()

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    this.flutterPluginBinding = flutterPluginBinding
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "pdf_render")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    try {
      when {
        call.method == "file" -> {
          val pdfFilePath = call.arguments as? String
          if (pdfFilePath == null) {
            result.success(null)
            return
          }
          result.success(registerNewDoc(openFileDoc(pdfFilePath)))
        }
        call.method == "asset" -> {
          val pdfAssetName = call.arguments as? String
          if (pdfAssetName == null) {
            result.success(null)
            return
          }
          result.success(registerNewDoc(openAssetDoc(pdfAssetName)))
        }
        call.method == "data" -> {
          val data = call.arguments as? ByteArray
          if (data == null) {
            result.success(null)
            return
          }
          result.success(registerNewDoc(openDataDoc(data)))
        }
        call.method == "close" -> {
          val id = call.arguments as? Int
          if (id != null)
            close(id)
          result.success(0)
        }
        call.method == "info" -> {
          val (renderer, id) = getDoc(call)
          if (renderer == null) {
            result.success(-1)
            return
          }
          result.success(getInfo(renderer, id))
        }
        call.method == "page" -> {
          val args = call.arguments as? HashMap<String, Any>
          if (args == null) {
            result.success(null)
            return
          }
          result.success(openPage(args))
        }
        call.method == "render" -> {
          val args = call.arguments as? HashMap<String, Any>
          if (args == null) {
            result.success(-1)
            return
          }
          render(args, result)
        }
        call.method == "releaseBuffer" -> {
          val addr = call.arguments as? Long
          if (addr != null)
            releaseBuffer(addr)
          result.success(0)
        }
        call.method == "allocTex" -> {
          result.success(allocTex())
        }
        call.method == "releaseTex" -> {
          val id = call.arguments as? Int
          if (id != null)
            releaseTex(id)
          result.success(0)
        }
        call.method == "resizeTex" -> {
          val args = call.arguments as? HashMap<String, Any>
          if (args == null) {
            result.success(-1)
            return
          }
          result.success(resizeTex(args))
        }
        call.method == "updateTex" -> {
          val args = call.arguments as? HashMap<String, Any>
          if (args == null) {
            result.success(-1)
            return
          }
          result.success(updateTex(args))
        }
        else -> result.notImplemented()
      }
    } catch (e: Exception) {
      result.error("exception", "Internal error.", e)
    }
  }

  private fun registerNewDoc(pdfRenderer: PdfRenderer): HashMap<String, Any> {
    val id = ++lastDocId
    documents.put(id, pdfRenderer)
    return getInfo(pdfRenderer, id)
  }

  private fun getDoc(call: MethodCall): Pair<PdfRenderer?, Int> {
    val id = call.arguments as? Int
    if (id != null)
      return Pair(documents[id], id)
    return Pair(null, -1)
  }

  private fun getInfo(pdfRenderer: PdfRenderer, id: Int): HashMap<String, Any> {
    return hashMapOf(
      "docId" to id,
      "pageCount" to pdfRenderer.pageCount,
      "verMajor" to 1,
      "verMinor" to 7,
      "isEncrypted" to false,
      "allowsCopying" to false,
      "allowPrinting" to false)
  }

  private fun close(id: Int) {
    val renderer = documents[id]
    if (renderer != null) {
      renderer.close()
      documents.remove(id)
    }
  }

  private fun openFileDoc(pdfFilePath: String): PdfRenderer {
    val fd = ParcelFileDescriptor.open(File(pdfFilePath), MODE_READ_ONLY)
    return PdfRenderer(fd)
  }

  private fun copyToTempFileAndOpenDoc(writeData: (OutputStream) -> Unit): PdfRenderer {
    val file = File.createTempFile("pdfr", null, null)
    try {
      file.outputStream().use {
        writeData(it)
      }
      file.inputStream().use {
        return PdfRenderer(ParcelFileDescriptor.dup(it.fd))
      }
    } finally {
      file.delete()
    }
  }

  private fun openAssetDoc(pdfAssetName: String): PdfRenderer {
    val key = flutterPluginBinding.flutterAssets.getAssetFilePathByName(pdfAssetName)
    // NOTE: the input stream obtained from asset may not be
    // a file stream and we should convert it to file
    flutterPluginBinding.applicationContext.assets.open(key).use { input ->
      return copyToTempFileAndOpenDoc { input.copyTo(it) }
    }
  }

  private fun openDataDoc(data: ByteArray): PdfRenderer {
    return copyToTempFileAndOpenDoc { it.write(data) }
  }

  private fun openPage(args: HashMap<String, Any>): HashMap<String, Any>? {
    val docId = args["docId"] as? Int ?: return null
    val renderer = documents[docId] ?: return null
    val pageNumber = args["pageNumber"] as? Int ?: return null
    if (pageNumber < 1 || pageNumber > renderer.pageCount) return null
    renderer.openPage(pageNumber - 1).use {
      return hashMapOf(
        "docId" to docId,
        "pageNumber" to pageNumber,
        "width" to it.width.toDouble(),
        "height" to it.height.toDouble()
      )
    }
  }

  private fun renderOnByteBuffer(args: HashMap<String, Any>, createBuffer: (Int) -> ByteBuffer): HashMap<String, Any?>? {
    val docId = args["docId"] as? Int
    val renderer = if (docId != null) documents[docId] else null
    val pageNumber = args["pageNumber"] as? Int
    if (renderer == null || pageNumber == null || pageNumber < 1 || pageNumber > renderer.pageCount) {
      return null
    }

    renderer.openPage(pageNumber - 1).use {
      val x = args["x"] as? Int? ?: 0
      val y = args["y"] as? Int? ?: 0
      val _w = args["width"] as? Int? ?: 0
      val _h = args["height"] as? Int? ?: 0
      val w = if (_w > 0) _w else it.width
      val h = if (_h > 0) _h else it.height
      val _fw = args["fullWidth"] as? Double ?: 0.0
      val _fh = args["fullHeight"] as? Double ?: 0.0
      val fw = if (_fw > 0) _fw.toFloat() else w.toFloat()
      val fh = if (_fh > 0) _fh.toFloat() else h.toFloat()
      val backgroundFill = args["backgroundFill"] as? Boolean ?: true

      val buf = createBuffer(w * h * 4)

      val mat = Matrix()
      mat.setValues(floatArrayOf(fw / it.width, 0f, -x.toFloat(), 0f, fh / it.height, -y.toFloat(), 0f, 0f, 1f))

      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

      if (backgroundFill) {
        bmp.eraseColor(Color.WHITE)
      }

      it.render(bmp, null, mat, RENDER_MODE_FOR_DISPLAY)

      bmp.copyPixelsToBuffer(buf)
      bmp.recycle()

      return hashMapOf(
        "docId" to docId,
        "pageNumber" to pageNumber,
        "x" to x,
        "y" to y,
        "width" to w,
        "height" to h,
        "fullWidth" to fw.toDouble(),
        "fullHeight" to fh.toDouble(),
        "pageWidth" to it.width.toDouble(),
        "pageHeight" to it.height.toDouble()
      )
    }
  }

  private fun render(args: HashMap<String, Any>, result: Result) {
    var buf: ByteBuffer? = null
    var addr: Long = 0L
    val m = renderOnByteBuffer(args) {
      if (false) {
        val abuf = ByteBuffer.allocate(it)
        buf = abuf
        return@renderOnByteBuffer abuf
      }
      val (addr_, bbuf) = allocBuffer(it)
      buf = bbuf
      addr = addr_
      return@renderOnByteBuffer bbuf
    }
    if (addr != 0L) {
      m?.set("addr", addr)
    } else {
      m?.set("data", buf?.array())
    }
    m?.set("size", buf?.capacity())
    result.success(m)
  }

  private fun allocBuffer(size: Int): Pair<Long, ByteBuffer> {
    // FIXME: Dirty hack to obtain address of the DirectByteBuffer
    val addressField = Buffer::class.java.getDeclaredField("address")
    addressField.setAccessible(true)
    val bb = ByteBuffer.allocateDirect(size)
    val addr = addressField.getLong(bb)
    buffers.put(addr, bb)
    return addr to bb
  }

  private fun releaseBuffer(addr: Long) {
    buffers.remove(addr)
  }

  private fun allocTex(): Int {
    val surfaceTexture = flutterPluginBinding.textureRegistry.createSurfaceTexture()
    val id = surfaceTexture.id().toInt()
    textures.put(id, surfaceTexture)
    return id
  }

  private fun releaseTex(texId: Int) {
    val tex = textures[texId]
    tex?.release()
    textures.remove(texId)
  }

  private fun resizeTex(args: HashMap<String, Any>): Int {
    val texId = args["texId"] as? Int
    val width = args["width"] as? Int
    val height = args["height"] as? Int
    if (texId == null || width == null || height == null) {
      return -1
    }
    val tex = textures[texId]
    tex?.surfaceTexture()?.setDefaultBufferSize(width, height)
    return 0
  }

  private fun updateTex(args: HashMap<String, Any>): Int {
    val texId = args["texId"] as? Int ?: return -1
    val tex = textures[texId] ?: return -2
    val docId = args["docId"] as? Int ?: return -3
    val renderer = documents[docId] ?: return -4
    val pageNumber = args["pageNumber"] as? Int ?: return -5
    if (pageNumber < 1 || pageNumber > renderer.pageCount)
      return -6

    renderer.openPage(pageNumber - 1). use {page ->
      val fullWidth = args["fullWidth"] as? Double ?: page.width.toDouble()
      val fullHeight = args["fullHeight"] as? Double ?: page.height.toDouble()
      val destX = args["destX"] as? Int ?: 0
      val destY = args["destY"] as? Int ?: 0
      val width = args["width"] as? Int ?: 0
      val height = args["height"] as? Int ?: 0
      val srcX = args["srcX"] as? Int ?: 0
      val srcY = args["srcY"] as? Int ?: 0
      val backgroundFill = args["backgroundFill"] as? Boolean ?: true

      if (width <= 0 || height <= 0)
        return -7

      val mat = Matrix()
      mat.setValues(floatArrayOf((fullWidth / page.width).toFloat(), 0f, -srcX.toFloat(), 0f, (fullHeight / page.height).toFloat(), -srcY.toFloat(), 0f, 0f, 1f))

      val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      if (backgroundFill) {
        bmp.eraseColor(Color.WHITE)
      }
      page.render(bmp, null, mat, RENDER_MODE_FOR_DISPLAY)

      val texWidth = args["texWidth"] as? Int
      val texHeight = args["texHeight"] as? Int
      if (texWidth != null && texHeight != null)
        tex.surfaceTexture()?.setDefaultBufferSize(texWidth, texHeight)

      Surface(tex.surfaceTexture()).use {
        val canvas = it.lockCanvas(Rect(destX, destY, width, height))

        canvas.drawBitmap(bmp, destX.toFloat(), destY.toFloat(), null)
        bmp.recycle()

        it.unlockCanvasAndPost(canvas)
      }
    }
    return 0
  }
}

fun <R> Surface.use(block: (Surface) -> R): R {
  try {
    return block(this)
  }
  finally {
    this.release()
  }
}
