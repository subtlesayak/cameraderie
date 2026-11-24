package app.cameraderie.sony

import android.net.Network
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

data class SonyHttpItem(val id: String, val name: String, val folder: Boolean, val size: Long, val url: String?, val previewUrl: String? = null, val detailPreviewUrl: String? = null, val importResources:List<SonyImportSizePolicy.Resource> = emptyList(), val captureDate:String?=null)

/** Read-only ContentDirectory client. All packets and HTTP sockets belong to the
 * explicitly selected camera Network; no default route, redirects or DNS lookup.
 * A DLNA representation is never promoted into an ARW by its display title. */
class SonyHttpGallery(private val network: Network, private val gateway: InetAddress,private val expectedModel:String?=null): Closeable {
    @Volatile private var closed = false
    private val resourceLock = Any()
    private val sockets = ConcurrentHashMap.newKeySet<Closeable>()
    private var discoveryReplies=0
    private var locationsFound=0
    private var descriptionsRead=0
    private var descriptionsRejected=0
    private var descriptionShape=""
    fun diagnostic()="discovery_replies=$discoveryReplies locations=$locationsFound descriptions=$descriptionsRead rejected=$descriptionsRejected $descriptionShape"
    private var control: URI? = null
    private var service = "urn:schemas-upnp-org:service:ContentDirectory:1"
    var model: String = ""; private set
    var totalMatches: Int = -1; private set
    var updateId:String?=null;private set

    fun open(onDescription: ((String)->Unit)?=null) {
        checkLive()
        require(gateway is Inet4Address && (gateway.isSiteLocalAddress || gateway.isLinkLocalAddress)) { "Camera must use a local IPv4 network" }
        val locations = discover();locationsFound=locations.size
        for (location in locations) {
            checkLive()
            val description = try { request(location, "GET").use { readXml(it.stream) } } catch (_: IOException) { continue }
            onDescription?.invoke(description)
            descriptionsRead++
            val found = try { parseDescription(description, location, gateway,expectedModel) } catch (_: IOException) { descriptionsRejected++;descriptionShape=descriptionShape(description);continue }
            model = found.model; control = found.control; service = found.service
            return
        }
        throw IOException("No compatible Sony camera media service found. On the camera choose Send to Smartphone, then Select on Smartphone, and reconnect to its Wi-Fi.")
    }

    fun list(container: String = "0", start: Int = 0, count: Int = 40): List<SonyHttpItem> {
        checkLive()
        require(start >= 0 && count in 1..100 && container.length in 1..1024)
        val target = control ?: throw IOException("Connect the camera gallery first")
        val body = browseEnvelope(container, start, count, service).toByteArray(StandardCharsets.UTF_8)
        val xml = request(target, "POST", body, mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPACTION" to "\"$service#Browse\"")).use { readXml(it.stream) }
        val result = parseBrowse(xml, target, gateway)
        if (result.items.size > count) throw IOException("Camera returned more items than requested")
        totalMatches = result.total;updateId=result.update
        return result.items
    }

    fun download(item: SonyHttpItem, output: OutputStream, cancelled: () -> Boolean, progress: (Long) -> Unit): Long {
        checkLive()
        if (item.folder || item.size <= 0 || item.size > MAX_FILE || item.url == null) throw IOException("This camera item has no downloadable full-size file")
        val uri = localUri(URI(item.url), gateway)
        return request(uri, "GET").use { response ->
            if (response.length != null && response.length != item.size) throw IOException("Camera file length changed. Refresh the gallery and try again.")
            var read = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkLive()
                if (cancelled()) throw InterruptedIOException("Download cancelled")
                val n = response.stream.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (read + n > item.size) throw IOException("Camera sent more bytes than the advertised file")
                output.write(buffer, 0, n); read += n; progress(read)
            }
            if (read != item.size) throw EOFException("Camera file was incomplete")
            read
        }
    }

    /** Preview is a separate, bounded representation. Never substitutes for download. */
    fun preview(item: SonyHttpItem,detail:Boolean=false): ByteArray? {
        checkLive()
        val url=(if(detail) item.detailPreviewUrl ?: item.previewUrl else item.previewUrl) ?: return null
        return request(localUri(URI(url),gateway),"GET").use { response ->
            val timer=java.util.Timer("Sony-preview-deadline",true)
            try {
                timer.schedule(object:java.util.TimerTask() { override fun run() { runCatching { response.close() } } },15000)
                SonyPreviewData.read(response.stream,response.length,if(detail) SonyPreviewData.MAX_DETAIL_BYTES else SonyPreviewData.MAX_BYTES)
            } finally { timer.cancel() }
        }
    }

    private fun discover(): Set<URI> {
        val result = linkedSetOf<URI>()
        val socket = DatagramSocket(null)
        register(socket)
        try {
            network.bindSocket(socket); socket.bind(InetSocketAddress(0)); socket.soTimeout = 750
            val multicast = InetAddress.getByAddress(byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte()))
            for (target in listOf("urn:schemas-upnp-org:service:ContentDirectory:1", "urn:schemas-upnp-org:device:MediaServer:1", "ssdp:all")) {
                val data = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                socket.send(DatagramPacket(data, data.size, multicast, 1900))
            }
            val deadline = System.nanoTime() + 6_000_000_000L
            var packets = 0
            while (System.nanoTime() < deadline && packets < 100) {
                checkLive()
                val packet = DatagramPacket(ByteArray(16 * 1024), 16 * 1024)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                packets++;discoveryReplies++
                if (packet.address != gateway) continue
                val message = String(packet.data, 0, packet.length, StandardCharsets.ISO_8859_1)
                if (!message.startsWith("HTTP/1.1 200")) continue
                val location = message.lineSequence().firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim() ?: continue
                try { result.add(localUri(URI(location), gateway)) } catch (_: Exception) { }
            }
        } finally { sockets.remove(socket); socket.close() }
        return result
    }

    private class Response(val stream: InputStream, val length: Long?, private val release: () -> Unit): Closeable {
        override fun close() = release()
    }

    /** Raw HTTP is intentionally limited to a verified numeric camera address.
     * It does not widen the app's Android cleartext policy for other traffic. */
    private fun request(input: URI, method: String, body: ByteArray = byteArrayOf(), headers: Map<String, String> = emptyMap()): Response {
        checkLive()
        val uri = localUri(input, gateway)
        val socket = network.socketFactory.createSocket()
        register(socket)
        try {
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress(gateway, if (uri.port < 0) 80 else uri.port), 5000)
            checkLive()
            val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val host = uri.host + if (uri.port >= 0) ":${uri.port}" else ""
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nAccept-Encoding: identity\r\n")
                headers.forEach { (key, value) ->
                    require(!key.contains('\r') && !key.contains('\n') && !value.contains('\r') && !value.contains('\n'))
                    append("$key: $value\r\n")
                }
                if (method == "POST") append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray(StandardCharsets.US_ASCII)); if (body.isNotEmpty()) write(body); flush() }
            val source = BufferedInputStream(socket.getInputStream())
            val status = line(source)
            if (!status.matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) throw IOException("Camera HTTP request failed (${status.take(32)})")
            val responseHeaders = linkedMapOf<String, String>()
            var headerBytes = status.length
            while (true) {
                val row = line(source); headerBytes += row.length
                if (headerBytes > 32768) throw IOException("Camera HTTP headers are too large")
                if (row.isEmpty()) break
                val split = row.indexOf(':'); if (split <= 0) throw IOException("Invalid camera HTTP header")
                val key = row.substring(0, split).lowercase(); val value = row.substring(split + 1).trim()
                if (key in setOf("content-length", "transfer-encoding") && responseHeaders.containsKey(key)) throw IOException("Ambiguous camera HTTP framing")
                responseHeaders[key] = value
            }
            val encoding = responseHeaders["content-encoding"]
            if (encoding != null && !encoding.equals("identity", true)) throw IOException("Compressed camera responses are unsupported")
            val length = responseHeaders["content-length"]?.let { it.toLongOrNull()?.takeIf { n -> n in 0..MAX_FILE } ?: throw IOException("Invalid camera response length") }
            val transfer = responseHeaders["transfer-encoding"]
            if (transfer != null && (!transfer.equals("chunked", true) || length != null)) throw IOException("Ambiguous camera response framing")
            val stream = when {
                transfer != null -> Chunked(source)
                length != null -> Limited(source, length)
                else -> source
            }
            return Response(stream, length) { sockets.remove(socket); socket.close() }
        } catch (error: Exception) { sockets.remove(socket); socket.close(); throw error }
    }

    private fun checkLive() { if (closed) throw InterruptedIOException("Camera gallery closed") }
    private fun register(resource: Closeable) = synchronized(resourceLock) {
        if (closed) { resource.close(); throw InterruptedIOException("Camera gallery closed") }
        sockets.add(resource)
    }
    override fun close() {
        val owned = synchronized(resourceLock) { closed = true; sockets.toList().also { sockets.clear() } }
        owned.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val MAX_FILE = 64L * 1024 * 1024 * 1024
        private const val MAX_XML = 2 * 1024 * 1024
        internal data class Description(val model: String, val control: URI, val service: String)
        internal data class Browse(val items: List<SonyHttpItem>, val total: Int,val update:String?=null)

        internal fun localUri(uri: URI, gateway: InetAddress): URI {
            val host = uri.host ?: throw IOException("Camera URL has no host")
            if (uri.scheme != "http" || uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 || !Regex("[0-9.]+").matches(host)) throw IOException("Invalid local camera URL")
            val octets = host.split('.').map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: throw IOException("Invalid camera address") }
            if (octets.size != 4 || !InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()).equals(gateway)) throw IOException("Camera URL points outside the selected camera")
            if (uri.rawPath.orEmpty().contains(Regex("[\\r\\n]")) || uri.rawQuery.orEmpty().contains(Regex("[\\r\\n]"))) throw IOException("Invalid camera URL")
            return uri
        }

        internal fun descriptionShape(text: String): String = try {
            val devices=xml(text).getElementsByTagNameNS("*","device")
            var sony=0;var names=0;var numbers=0;var media=0
            for(i in 0 until devices.length) {
                val d=devices.item(i) as Element
                if(child(d,"manufacturer").contains("sony",true)) sony++
                if(SonyCameraIdentity.modelCode(child(d,"modelName"))!=null) names++
                if(SonyCameraIdentity.modelCode(child(d,"modelNumber"))!=null) numbers++
                val services=d.getElementsByTagNameNS("*","serviceType")
                for(j in 0 until services.length) if(services.item(j).textContent.trim().startsWith("urn:schemas-upnp-org:service:ContentDirectory:")) media++
            }
            "sony_devices=$sony model_names=$names model_numbers=$numbers content_services=$media"
        } catch(_:Exception) { "description_unreadable" }
        internal fun parseDescription(xml: String, location: URI, gateway: InetAddress,expectedModel:String?=null): Description {
            localUri(location, gateway)
            val doc = xml(xml)
            val devices = doc.getElementsByTagNameNS("*", "device")
            for (index in 0 until devices.length) {
                val device = devices.item(index) as Element
                val modelName = child(device, "modelName")
                val modelNumber = child(device, "modelNumber")
                val model=SonyCameraIdentity.description(child(device,"manufacturer"),modelName,modelNumber,
                    child(device,"friendlyName"),child(device,"deviceType"),expectedModel) ?: continue
                val services = device.getElementsByTagNameNS("*", "service")
                for (s in 0 until services.length) {
                    val element = services.item(s) as Element
                    val type = child(element, "serviceType")
                    if (!Regex("urn:schemas-upnp-org:service:ContentDirectory:[0-9]+").matches(type)) continue
                    val path = child(element, "controlURL")
                    if (path.isBlank()) continue
                    val urlBase = doc.getElementsByTagNameNS("*", "URLBase").let { if (it.length > 0) it.item(0).textContent.trim() else "" }
                    val base = if (urlBase.isBlank()) location else localUri(URI(urlBase), gateway)
                    return Description(model, localUri(base.resolve(path), gateway), type)
                }
            }
            throw IOException("Selected device does not expose a compatible Sony camera ContentDirectory")
        }

        internal fun browseEnvelope(id: String, start: Int, count: Int, service: String): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"${escape(service)}\"><ObjectID>${escape(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>"

        internal fun parseBrowse(response: String, base: URI, gateway: InetAddress): Browse {
            val doc = xml(response)
            if (doc.getElementsByTagNameNS("*", "Fault").length > 0) throw IOException("Camera rejected the gallery request")
            val result = doc.getElementsByTagNameNS("*", "Result")
            if (result.length != 1) throw IOException("Camera gallery response has no result")
            val didl = xml(result.item(0).textContent)
            val totalNode = doc.getElementsByTagNameNS("*", "TotalMatches")
            val total = if (totalNode.length == 1) totalNode.item(0).textContent.trim().toIntOrNull()?.takeIf { it >= 0 } ?: -1 else -1
            val items = mutableListOf<SonyHttpItem>()
            val nodes = didl.documentElement.childNodes
            if (nodes.length > 1000) throw IOException("Camera gallery page is too large")
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val kind = element.localName ?: element.tagName.substringAfter(':')
                if (kind != "item" && kind != "container") continue
                val id = element.getAttribute("id").takeIf { it.length in 1..1024 } ?: throw IOException("Camera item identifier is invalid")
                val title = child(element, "title").take(255).ifBlank { "Camera item" }
                val captureDate=child(element,"date").takeIf { it.length in 8..64 }
                if (kind == "container") { items.add(SonyHttpItem(id, title, true, 0, null)); continue }
                val resources = element.getElementsByTagNameNS("*", "res")
                data class Resource(val url: URI, val size: Long, val rank: Long, val mime: String, val pixels:Long)
                val candidates = mutableListOf<Resource>()
                val previews = mutableListOf<Resource>()
                val imports=mutableListOf<SonyImportSizePolicy.Resource>()
                for (r in 0 until resources.length) {
                    val resource = resources.item(r) as Element
                    val protocol = resource.getAttribute("protocolInfo")
                    val dimensions=resource.getAttribute("resolution").lowercase().split('x')
                    val width=dimensions.getOrNull(0)?.toLongOrNull()?.takeIf { it in 1..65535 } ?: 0
                    val height=dimensions.getOrNull(1)?.toLongOrNull()?.takeIf { it in 1..65535 } ?: 0
                    val pixels=width*height
                    val preview=protocol.contains("JPEG_TN",true) || protocol.contains("JPEG_SM",true) || protocol.contains("JPEG_MED",true) || protocol.contains("thumbnail",true)
                    val importSize=resource.getAttribute("size").toLongOrNull()?.takeIf { it in 1..MAX_FILE }
                    val importMime=protocol.split(':').getOrNull(2).orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
                    val importUrl=try { localUri(base.resolve(resource.textContent.trim()),gateway) } catch(_:Exception) { null }
                    if(importSize!=null && importUrl!=null) {
                        val importName=SonyImportPlan.representationName(importUrl.path.substringAfterLast('/'),title,importMime)
                        if(importName!=null) imports.add(SonyImportSizePolicy.Resource(importUrl.toASCIIString(),importName,importMime,importSize,
                            width.takeIf { it>0 }?.toInt(),height.takeIf { it>0 }?.toInt(),
                            protocol.contains("JPEG_TN",true)||protocol.contains("thumbnail",true),preview))
                    }
                    if(preview) {
                        val mime=protocol.split(':').getOrNull(2).orEmpty().lowercase()
                        val size=resource.getAttribute("size").toLongOrNull()
                        val url=try { localUri(base.resolve(resource.textContent.trim()),gateway) } catch(_:Exception) { continue }
                        if(mime in setOf("image/jpeg","image/png") && (size==null || size in 1..SonyPreviewData.MAX_BYTES.toLong()))
                            previews.add(Resource(url,size ?: -1,if(protocol.contains("JPEG_TN",true)) 0 else 1,mime,pixels))
                        continue
                    }
                    val size = resource.getAttribute("size").toLongOrNull()?.takeIf { it in 1..MAX_FILE } ?: continue
                    val url = try { localUri(base.resolve(resource.textContent.trim()), gateway) } catch (_: Exception) { continue }
                    val mime = importMime
                    if(SonyImportPlan.representationName(url.path.substringAfterLast('/'),title,mime)==null) continue
                    candidates.add(Resource(url, size, size, mime,pixels))
                }
                // A full RAW is preferable to its JPEG representation even if
                // the JPEG happens to be larger. A JPEG MIME never proves RAW.
                fun isRaw(r:Resource)=r.mime.contains("arw",true) ||
                    (r.mime=="application/octet-stream" && r.url.path.endsWith(".arw",true))
                val chosen = candidates.maxWithOrNull(compareBy<Resource> { isRaw(it) }.thenBy { it.rank })
                val albumArt=child(element,"albumArtURI").takeIf { it.isNotBlank() }?.let {
                    try { localUri(base.resolve(it),gateway) } catch(_:Exception) { null }
                }
                val preview=previews.minWithOrNull(compareBy<Resource> { it.rank }.thenBy { if(it.size>0) it.size else Long.MAX_VALUE })?.url ?: albumArt
                val previewUrl=preview?.toASCIIString()
                val detailUrl=(previews+candidates).filter {
                    it.mime.lowercase() in setOf("image/jpeg","image/png") && it.size in 1..SonyPreviewData.MAX_DETAIL_BYTES.toLong()
                }.maxWithOrNull(compareBy<Resource> { it.pixels }.thenBy { it.size })?.url?.toASCIIString() ?: previewUrl
                if (chosen == null) { items.add(SonyHttpItem(id, title, false, -1, null, previewUrl,detailUrl,imports,captureDate=captureDate)); continue }
                val name = checkNotNull(SonyImportPlan.representationName(chosen.url.path.substringAfterLast('/'),title,chosen.mime))
                items.add(SonyHttpItem(id, name, false, chosen.size, chosen.url.toASCIIString(), previewUrl,detailUrl,imports,captureDate=captureDate))
            }
            val returnedNodes = doc.getElementsByTagNameNS("*", "NumberReturned")
            if (returnedNodes.length == 1 && returnedNodes.item(0).textContent.trim().toIntOrNull() != items.size) throw IOException("Incomplete camera gallery page")
            val updates=doc.getElementsByTagNameNS("*","UpdateID")
            val update=if(updates.length==0) null else {
                if(updates.length!=1) throw IOException("Ambiguous gallery update identifier")
                updates.item(0).textContent.trim().toLongOrNull()?.takeIf { it in 0..0xFFFFFFFFL }?.toString() ?: throw IOException("Invalid gallery update identifier")
            }
            return Browse(items, total,update)
        }

        private fun child(parent: Element, name: String): String {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val e = children.item(i) as? Element ?: continue
                if ((e.localName ?: e.tagName.substringAfter(':')) == name) return e.textContent.trim()
            }
            return ""
        }
        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
        private fun xml(text: String): org.w3c.dom.Document {
            if (text.length > MAX_XML || text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) throw IOException("Unsafe camera XML")
            try {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true; isExpandEntityReferences = false
                    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                    runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                }
                val builder = factory.newDocumentBuilder()
                builder.setEntityResolver { _, _ -> throw IOException("External XML entities are disabled") }
                return builder.parse(InputSource(StringReader(text)))
            } catch (error: Exception) { if (error is IOException) throw error; throw IOException("Invalid camera XML", error) }
        }
        private fun readXml(input: InputStream): String {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; if (out.size() + n > MAX_XML) throw IOException("Camera XML is too large"); out.write(buffer, 0, n) }
            return out.toString("UTF-8")
        }
        private fun line(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (out.size() <= 8192) {
                val b = input.read(); if (b < 0) throw EOFException("Truncated camera HTTP response")
                if (b == 10) { val bytes = out.toByteArray(); if (bytes.isEmpty() || bytes.last() != 13.toByte()) throw IOException("Invalid HTTP line ending"); return String(bytes, 0, bytes.size - 1, StandardCharsets.ISO_8859_1) }
                out.write(b)
            }
            throw IOException("Camera HTTP line too long")
        }
    }

    private class Limited(private val source: InputStream, private var remaining: Long): InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining == 0L) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt()); if (n < 0) throw EOFException("Truncated camera response")
            remaining -= n; return n
        }
    }
    private class Chunked(private val source: InputStream): InputStream() {
        private var remaining = 0L; private var finished = false; private var needsCrLf = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (finished) return -1
            if (remaining == 0L) {
                if (needsCrLf && line(source).isNotEmpty()) throw IOException("Invalid HTTP chunk ending")
                remaining = line(source).substringBefore(';').trim().toLongOrNull(16)?.takeIf { it in 0..MAX_FILE } ?: throw IOException("Invalid HTTP chunk length")
                if (remaining == 0L) {
                    var trailers = 0
                    while (true) { val row = line(source); trailers += row.length; if (trailers > 32768) throw IOException("HTTP trailers too large"); if (row.isEmpty()) break }
                    finished = true; return -1
                }
            }
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt()); if (n < 0) throw EOFException("Truncated HTTP chunk")
            remaining -= n; needsCrLf = remaining == 0L; return n
        }
    }
}
