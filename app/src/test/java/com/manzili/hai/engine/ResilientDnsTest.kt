package com.manzili.hai.engine

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {
    @Test
    fun fallsBackWhenPrimaryDnsCannotResolve() {
        val expected = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val failing = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("primary failed")
        }
        val backup = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(expected)
        }

        val result = ResilientDns(listOf(failing, backup)).lookup("example.invalid")

        assertEquals(listOf(expected), result)
    }

    @Test
    fun throwsOnlyAfterAllDnsProvidersFail() {
        val first = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("first")
        }
        val second = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("second")
        }

        assertThrows(UnknownHostException::class.java) {
            ResilientDns(listOf(first, second)).lookup("example.invalid")
        }
    }
}
