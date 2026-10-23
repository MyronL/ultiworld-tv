package io.github.myronl.ultiworldtv.login

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

/**
 * The TV's IPv4 address on the home network (Wi-Fi or Ethernet), or null when it has none. Not a VPN's, even
 * when one is on: a phone on the Wi-Fi can't reach that address, and the login page shouldn't be offered there.
 */
@Suppress("DEPRECATION") // allNetworks: the replacement, a NetworkCallback, is overkill for one look on one screen.
fun lanAddress(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val home = (listOfNotNull(cm.activeNetwork) + cm.allNetworks).distinct().filter { network ->
        val caps = cm.getNetworkCapabilities(network) ?: return@filter false
        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    return home.firstNotNullOfOrNull { network ->
        cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }
}
