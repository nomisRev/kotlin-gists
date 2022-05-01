import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.ChromeCasts
import su.litvak.chromecast.api.v2.ChromeCastsListener

public fun discovery(inetAddress: InetAddress): Flow<List<ChromeCast>> = callbackFlow {
  val listener = object : ChromeCastsListener {
    override fun newChromeCastDiscovered(chromeCast: ChromeCast?) {
      launch { send(ChromeCasts.get()) }
    }

    override fun chromeCastRemoved(chromeCast: ChromeCast?) {
      launch { send(ChromeCasts.get()) }
    }
  }

  ChromeCasts.registerListener(listener)
  ChromeCasts.startDiscovery(inetAddress)

  awaitClose {
    ChromeCasts.unregisterListener(listener)
    ChromeCasts.stopDiscovery()
  }
}.flowOn(Dispatchers.IO)

suspend fun main(): Unit {

  withTimeout(10.seconds) {
    discovery(
      InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 246.toByte()))
    ).collect { chromecasts ->
      println(chromecasts)
    }
  }

  val ip = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 246.toByte()))
  ChromeCasts.startDiscovery(ip)
  var cast: ChromeCast? = null
  val listener = object : ChromeCastsListener {
    override fun newChromeCastDiscovered(chromeCast: ChromeCast?) {
      cast = chromeCast
      cast!!.launchApp("CC1AD845")
      chromeCast!!.load(
        "Big Buck Bunny",           // Media title
        "images/BigBuckBunny.jpg",  // URL to thumbnail based on media URL
        "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", // media URL
        null // media content type (optional, will be discovered automatically)
      )
      chromeCast!!.play()
    }

    override fun chromeCastRemoved(chromeCast: ChromeCast?) {}
  }
  ChromeCasts.registerListener(listener)
  delay(30_000)
  cast?.stopApp()
  cast?.disconnect()
  ChromeCasts.unregisterListener(listener)
  ChromeCasts.stopDiscovery()
}
