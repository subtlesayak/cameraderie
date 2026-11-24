package app.cameraderie.gallery.net

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import android.system.ErrnoException
import android.system.OsConstants
import app.cameraderie.gallery.GalleryResourceScope
import app.cameraderie.gallery.GalleryConnectFlow
import app.cameraderie.gallery.protocol.CameraWifiCredentials
import app.cameraderie.gallery.protocol.GalleryDatalinkSession.SocketFailure
import app.cameraderie.gallery.protocol.GalleryDatalinkSession.SocketReason
import app.cameraderie.gallery.protocol.GalleryDatalinkSession.SocketStep
import java.io.IOException
import java.net.*

/** Keep exception messages, endpoints and device details out of exported diagnostics. */
internal inline fun <T> cameraSocketStep(step: SocketStep, action: ()->T): T {
    try {
        return action()
    } catch(error: IOException) {
        val errno=generateSequence<Throwable>(error) { it.cause }.take(8).filterIsInstance<ErrnoException>().firstOrNull()?.errno
        val reason=when {
            errno==OsConstants.ECONNREFUSED || (errno==null && error is ConnectException) -> SocketReason.REFUSED
            errno==OsConstants.ENETUNREACH || errno==OsConstants.EHOSTUNREACH ||
                errno==OsConstants.ENONET || error is NoRouteToHostException -> SocketReason.UNREACHABLE
            errno==OsConstants.ETIMEDOUT || error is SocketTimeoutException -> SocketReason.TIMED_OUT
            errno==OsConstants.EACCES || errno==OsConstants.EPERM -> SocketReason.PERMISSION
            else -> SocketReason.OTHER_IO
        }
        throw SocketFailure(step,reason)
    } catch(_: SecurityException) {
        throw SocketFailure(step,SocketReason.PERMISSION)
    }
}

/** Every socket/HTTP request is bound to this one camera Network. No process-wide
 * binding or default-network/DNS fallback. Currently scoped to the Action 6 profile. */
class BoundCameraNetwork internal constructor(private val network: Network): GalleryConnectFlow.Network {
    private val scope=GalleryResourceScope()
    private val camera=InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),2,1))
    fun tcp(port: Int=7001): GalleryResourceScope.Handle<Socket> {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Camera networking must run off the UI thread" }
        require(port in setOf(7001,80))
        val result=scope.track(Socket()) { it.close() }
        try {
            cameraSocketStep(SocketStep.TCP_BIND) { network.bindSocket(result.value) }
            cameraSocketStep(SocketStep.TCP_CONNECT) { result.value.connect(InetSocketAddress(camera,port),5000) }
            result.value.soTimeout=5000;return result
        } catch(error: Exception) { result.close();throw error }
    }
    fun udp(): GalleryResourceScope.Handle<DatagramSocket> {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Camera networking must run off the UI thread" }
        val result=scope.track(DatagramSocket(null)) { it.close() }
        try {
            cameraSocketStep(SocketStep.UDP_NETWORK_BIND) { network.bindSocket(result.value) }
            cameraSocketStep(SocketStep.UDP_LOCAL_BIND) { result.value.bind(InetSocketAddress(0)) }
            cameraSocketStep(SocketStep.UDP_CONNECT) { result.value.connect(InetSocketAddress(camera,9004)) }
            result.value.soTimeout=1000;return result
        } catch(error: Exception) { result.close();throw error }
    }
    /** A relative camera URI only; redirects are never followed onto other hosts. */
    fun http(path: URI): GalleryResourceScope.Handle<HttpURLConnection> {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Camera networking must run off the UI thread" }
        require(!path.isAbsolute && path.rawAuthority==null && path.rawFragment==null && path.rawPath.startsWith("/") && !path.toASCIIString().startsWith("//"))
        check(scope.active) { "Camera network session ended" }
        val connection=network.openConnection(URL("http://192.168.2.1"+path.toASCIIString()),java.net.Proxy.NO_PROXY) as HttpURLConnection
        val result=scope.track(connection) { it.disconnect() }
        connection.instanceFollowRedirects=false;connection.connectTimeout=5000;connection.readTimeout=10000
        connection.useCaches=false;connection.requestMethod="GET"
        return result
    }
    override fun close()=scope.close()
    override fun toString()="BoundCameraNetwork(local-camera)"
}

/** Explicit user-approved WPA2 camera request. No credentials are persisted or logged. */
class GalleryWifiClient(context: Context,private val ownsGallery: ()->Boolean,private val listener: Listener) {
    enum class Failure { OWNERSHIP_REQUIRED, PERMISSION_REQUIRED, WIFI_DISABLED, REQUEST_FAILED, UNAVAILABLE, JOIN_TIMEOUT, INVALID_CAMERA_ROUTE, NETWORK_LOST }
    interface Listener {
        fun connected(network: BoundCameraNetwork)
        fun failed(reason: Failure)
    }
    private val app=context.applicationContext
    private val manager=app.getSystemService(ConnectivityManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var current: Attempt?=null
    private inner class Attempt {
        var callback: ConnectivityManager.NetworkCallback?=null
        var network: Network?=null
        var wifi=false
        var route=false
        var bound: BoundCameraNetwork?=null
        val deadline=Runnable { if(current === this) fail(this,if(network==null) Failure.JOIN_TIMEOUT else Failure.INVALID_CAMERA_ROUTE) }
    }
    val enabled get()=app.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled==true
    fun hasPermission(): Boolean = CameraWifiAccess.missing(app).isEmpty()
    private fun requireMain()=check(Looper.myLooper()==Looper.getMainLooper())
    fun join(credentials: CameraWifiCredentials) {
        requireMain();close()
        if(!ownsGallery()) { listener.failed(Failure.OWNERSHIP_REQUIRED);return }
        if(!hasPermission()) { listener.failed(Failure.PERMISSION_REQUIRED);return }
        if(!enabled) { listener.failed(Failure.WIFI_DISABLED);return }
        val request=try {
            credentials.use { ssid,password ->
                val spec=WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
            }
        } catch(_: IllegalArgumentException) { listener.failed(Failure.REQUEST_FAILED);return }
        val a=Attempt();current=a
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if(current !== a || !ownsGallery()) return
                if(a.network==network) return
                if(a.network!=null) { fail(a,Failure.NETWORK_LOST);return }
                a.network=network;handler.removeCallbacks(a.deadline);handler.postDelayed(a.deadline,5000)
            }
            override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) {
                if(current !== a || a.network!=network || !ownsGallery()) return
                a.wifi=capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if(a.bound!=null && !a.wifi) { fail(a,Failure.NETWORK_LOST);return }
                deliver(a)
            }
            override fun onLinkPropertiesChanged(network: Network,properties: LinkProperties) {
                if(current !== a || a.network!=network || !ownsGallery()) return
                val host=InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),2,1))
                // A default route alone is not proof that this is the camera's local subnet.
                a.route=properties.routes.any { !it.isDefaultRoute && it.matches(host) }
                if(a.bound!=null && !a.route) { fail(a,Failure.INVALID_CAMERA_ROUTE);return }
                deliver(a)
            }
            override fun onUnavailable() { if(current === a) fail(a,Failure.UNAVAILABLE) }
            override fun onLost(network: Network) { if(current === a && a.network==network) fail(a,Failure.NETWORK_LOST) }
        }
        a.callback=callback
        try {
            manager.requestNetwork(request,callback,handler,45000)
            handler.postDelayed(a.deadline,45000)
        } catch(_: SecurityException) { fail(a,Failure.PERMISSION_REQUIRED) }
        catch(_: RuntimeException) { fail(a,Failure.REQUEST_FAILED) }
    }
    private fun deliver(a: Attempt) {
        if(current !== a || !ownsGallery() || a.bound!=null || !a.wifi || !a.route) return
        val network=a.network ?: return
        val bound=BoundCameraNetwork(network);a.bound=bound;handler.removeCallbacks(a.deadline)
        listener.connected(bound)
    }
    private fun fail(a: Attempt,reason: Failure) { if(current !== a) return;close();listener.failed(reason) }
    /** Close transports before releasing Android's network request. Late callbacks are inert. */
    fun close() {
        requireMain();val a=current ?: return;current=null
        handler.removeCallbacks(a.deadline);a.bound?.close();a.bound=null
        a.callback?.let { try { manager.unregisterNetworkCallback(it) } catch(_: RuntimeException) {} }
        a.callback=null;a.network=null
    }
}
