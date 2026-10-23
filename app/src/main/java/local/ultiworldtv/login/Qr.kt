package local.ultiworldtv.login

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address

/** A black-on-white QR code for [text], [size] pixels square. */
fun qrCode(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The TV's IPv4 address on the home network (Wi-Fi or Ethernet), or null when it has none. */
fun lanAddress(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
}
