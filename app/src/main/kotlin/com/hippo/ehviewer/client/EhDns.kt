package com.hippo.ehviewer.client

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.builtInHosts
import com.hippo.ehviewer.ui.settings.EhDoH
import com.hippo.ehviewer.util.isAtLeastQ
import java.net.InetAddress
import okhttp3.AsyncDns
import okhttp3.Dns
import okhttp3.ExperimentalOkHttpApi
import okhttp3.android.AndroidAsyncDns

private typealias HostsMap = MutableMap<String, List<InetAddress>>

const val CFSUFFIX = ".cdn.cloudflare.net"

val builtInDoHUrls = listOf(
    "https://185.222.222.222/dns-query",
    "https://45.11.45.11/dns-query",
    "https://9.9.9.9/dns-query",
    "https://149.112.112.112/dns-query",
    "https://208.67.220.220/dns-query",
    "https://208.67.222.222/dns-query",
    "https://146.112.41.5/dns-query",
    "https://101.101.101.101/dns-query",
    "https://130.59.31.248/dns-query",
    "https://130.59.31.251/dns-query",
    "https://77.88.8.1/dns-query",
    "https://77.88.8.8/dns-query",
    "https://94.140.14.140/dns-query",
    "https://94.140.14.141/dns-query",
    "https://wikimedia-dns.org/dns-query",
    "https://odvr.nic.cz/dns-query",
)

val builtInCensoredDoHUrls = listOf(
    "https://doh.360.cn/dns-query",
    "https://doh.pub/dns-query",
    "https://dns.alidns.com/dns-query",
)

val cloudflaredDomains = listOf(
    "e-hentai.org",
    "api.e-hentai.org",
    "upload.e-hentai.org",
    "forums.e-hentai.org",
    "exhentai.org",
    "s.exhentai.org",
    "testingcf.jsdelivr.net",
)

val dFEnabledDomains = listOf(
    "github.com", "api.github.com",
    "ehgt.org", "gt0.ehgt.org", "gt1.ehgt.org", "gt2.ehgt.org", "gt3.ehgt.org", "ul.ehgt.org",
    "e-hentai.org", "api.e-hentai.org", "forums.e-hentai.org", "repo.e-hentai.org", "upload.e-hentai.org",
    "exhentai.org", "s.exhentai.org",
)

val dohSkipDomains = listOf(
    "www.recaptcha.net",
    "www.gstatic.cn",
    "www.gstatic.com",
)

val echEnabledDomains = listOf(
    "e-hentai.org",
    "exhentai.org",
    "forums.e-hentai.org",
    "testingcf.jsdelivr.net",
)

fun hostsDsl(builder: HostsMap.() -> Unit): HostsMap = mutableMapOf<String, List<InetAddress>>().apply(builder)

fun interface HostMapBuilder {
    infix fun String.blockedInCN(boolean: Boolean)
}

fun HostsMap.hosts(vararg hosts: String, builder: HostMapBuilder.() -> Unit) = apply {
    hosts.forEach { host ->
        fun String.toInetAddress() = InetAddress.getByName(this).let { InetAddress.getByAddress(host, it.address) }
        mutableListOf<InetAddress>().apply {
            HostMapBuilder { if (!it) add(toInetAddress()) }.apply(builder)
            put(host, this)
        }
    }
}

@OptIn(ExperimentalOkHttpApi::class)
val systemDns = if (isAtLeastQ) AsyncDns.toDns(AndroidAsyncDns.IPv4, AndroidAsyncDns.IPv6) else Dns.SYSTEM

object EhDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = when {
        (hostname in echEnabledDomains && Settings.enableECH) ->
            EhDoH.lookup("$hostname$CFSUFFIX") ?: systemDns.lookup("$hostname$CFSUFFIX")
        (hostname in "exhentai.org" && Settings.dF) ->
            builtInHosts[hostname] ?: systemDns.lookup("s.exhentai.org$CFSUFFIX")
        (hostname in cloudflaredDomains) ->
            EhDoH.lookup(hostname) ?: builtInHosts[hostname] ?: systemDns.lookup("$hostname$CFSUFFIX")
        (hostname in dohSkipDomains) ->
            systemDns.lookup(hostname)
        else ->
            EhDoH.lookup(hostname) ?: builtInHosts[hostname] ?: systemDns.lookup(hostname)
    }.shuffled()
}
