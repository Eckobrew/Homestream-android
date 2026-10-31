package com.homestream.app

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.*

data class DiscoveredServer(
    val name: String,
    val url: String,
    val host: String,
    val isLocal: Boolean = true
)

object NetworkDiscovery {

    fun startDiscovery(
        scope: CoroutineScope,
        onServerFound: (DiscoveredServer) -> Unit
    ): Job {
        val seenUrls = mutableSetOf<String>()
        return scope.launch(Dispatchers.IO) {
            // 1. Check common local hosts and default test endpoints immediately
            val commonHosts = listOf(
                "192.168.1.1",
                "192.168.0.1",
                "10.0.0.1",
                "homeflix.local",
                "homestream.local",
                "localhost"
            )

            commonHosts.forEach { host ->
                launch {
                    probeServer("http://$host:3000", host, onServerFound, seenUrls)
                }
            }

            // 2. UDP Broadcast discovery on port 7359 (Jellyfin/HomeStream discovery protocol)
            launch {
                try {
                    val socket = DatagramSocket().apply {
                        soTimeout = 2000
                        broadcast = true
                    }
                    val message = "who is JellyfinServer?".toByteArray()
                    val packet = DatagramPacket(message, message.size, InetAddress.getByName("255.255.255.255"), 7359)
                    socket.send(packet)

                    val buffer = ByteArray(2048)
                    val recvPacket = DatagramPacket(buffer, buffer.size)
                    val endTime = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < endTime && isActive) {
                        try {
                            socket.receive(recvPacket)
                            val resp = String(recvPacket.data, 0, recvPacket.length)
                            val json = JSONObject(resp)
                            val addr = json.optString("Address").ifBlank { recvPacket.address.hostAddress }
                            val serverName = json.optString("Name", "HomeStream Media Server")
                            if (!addr.isNullOrBlank()) {
                                val cleanHost = addr.replace("http://", "").replace("https://", "").split(':')[0]
                                probeServer("http://$cleanHost:3000", cleanHost, onServerFound, seenUrls, serverName)
                            }
                        } catch (_: SocketTimeoutException) {
                            break
                        } catch (_: Exception) {}
                    }
                    socket.close()
                } catch (_: Exception) {}
            }

            // 3. Scan the local Wi-Fi subnet (e.g. 192.168.1.x) in fast parallel batches
            val localSubnets = getLocalSubnets()
            for (subnet in localSubnets) {
                // Scan 1 to 254 in parallel batches of 32
                for (batchStart in 1..254 step 32) {
                    if (!isActive) break
                    val batchJobs = (batchStart until (batchStart + 32).coerceAtMost(255)).map { hostNum ->
                        launch {
                            val ip = "$subnet.$hostNum"
                            probeServer("http://$ip:3000", ip, onServerFound, seenUrls)
                        }
                    }
                    batchJobs.joinAll()
                }
            }
        }
    }

    private fun probeServer(
        url: String,
        host: String,
        onServerFound: (DiscoveredServer) -> Unit,
        seenUrls: MutableSet<String>,
        defaultName: String = "HomeStream Server"
    ) {
        val cleanUrl = url.trimEnd('/')
        synchronized(seenUrls) {
            if (seenUrls.contains(cleanUrl)) return
        }

        val conn = (URL("$cleanUrl/api/mobile/v1/discovery").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1200; readTimeout = 1200; requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            instanceFollowRedirects = false
        }
        try {
            if (conn.responseCode != 200) return
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (json.optInt("apiVersion") != 1 || !json.has("appName")) return
            val server = DiscoveredServer(json.optString("appName", defaultName), cleanUrl, host, true)
            synchronized(seenUrls) { if (seenUrls.add(cleanUrl)) onServerFound(server) }
        } catch (_: Exception) {
            // Manual pairing remains available when discovery is unsupported.
        } finally { conn.disconnect() }
    }

    private fun getLocalSubnets(): List<String> {
        val subnets = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split('.')
                        if (parts.size == 4) {
                            subnets.add("${parts[0]}.${parts[1]}.${parts[2]}")
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return subnets.distinct()
    }
}
