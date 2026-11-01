// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Whether the console answers a discovery SRCH at [host] within [timeoutMs].
 * Consoles answer in standby too, so this checks reachability, not power state.
 * Blocking; call off the main thread.
 */
fun consoleAnswers(host: String, ps5: Boolean, timeoutMs: Int = 800): Boolean = try
{
	DatagramSocket().use { socket ->
		socket.soTimeout = timeoutMs
		val msg = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:${if(ps5) "00030010" else "00020020"}\n".toByteArray()
		val addr = InetAddress.getByName(host)
		socket.send(DatagramPacket(msg, msg.size, addr, if(ps5) 9302 else 987))
		socket.receive(DatagramPacket(ByteArray(1024), 1024))
		true
	}
}
catch(e: Exception)
{
	false
}
