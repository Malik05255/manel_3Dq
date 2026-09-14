package com.manzili.hai.engine

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS chain for devices/networks that intermittently fail to resolve Render or Supabase hosts.
 *
 * Normal Android/system DNS is always tried first. Only when it fails do we use DNS-over-HTTPS,
 * first through Google and then Cloudflare. The DoH resolvers have bootstrap IPs, so they do not
 * depend on the same broken system DNS path that triggered the fallback.
 */
internal class ResilientDns(
    private val delegates: List<Dns> = defaultDelegates()
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        var lastFailure: Throwable? = null
        delegates.forEach { delegate ->
            try {
                val result = delegate.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }

        throw UnknownHostException("Unable to resolve $hostname with system DNS or DoH fallbacks").apply {
            lastFailure?.let(::initCause)
        }
    }

    companion object {
        private fun defaultDelegates(): List<Dns> = listOf(
            Dns.SYSTEM,
            doh(
                endpoint = "https://dns.google/dns-query",
                bootstrapIps = listOf("8.8.8.8", "8.8.4.4")
            ),
            doh(
                endpoint = "https://cloudflare-dns.com/dns-query",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1")
            )
        )

        private fun doh(endpoint: String, bootstrapIps: List<String>): Dns {
            val bootstrap = bootstrapIps.map(InetAddress::getByName)
            val client = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()

            return DnsOverHttps.Builder()
                .client(client)
                .url(endpoint.toHttpUrl())
                .bootstrapDnsHosts(*bootstrap.toTypedArray())
                .includeIPv6(false)
                .build()
        }
    }
}
