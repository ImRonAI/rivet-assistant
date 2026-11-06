package com.hiron.emrys.mcp

import java.net.Inet4Address
import java.net.NetworkInterface

/** The phone's Tailscale address (100.64.0.0/10), or null while Tailscale is off. */
internal fun tailnetAddress(): Inet4Address? = NetworkInterface.getNetworkInterfaces().toList()
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .firstOrNull { it.address[0].toInt() == 100 && it.address[1].toInt() in 64..127 }
