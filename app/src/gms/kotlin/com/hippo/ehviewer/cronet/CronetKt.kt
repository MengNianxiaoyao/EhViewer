package com.hippo.ehviewer.cronet

import com.hippo.ehviewer.Settings
import okio.Path.Companion.toOkioPath
import org.chromium.net.ExperimentalCronetEngine
import org.json.JSONObject
import splitties.init.appCtx

val CloudflareIP = Settings.cloudflareIp

val cronetHttpClient: ExperimentalCronetEngine = ExperimentalCronetEngine.Builder(appCtx).apply {
    configureCronetEngineBuilder(this)
}.build()
var experimentalOptions = JSONObject()
const val DNS_POISONING_CIRCUMVENTION_SUFFIX = ".cdn.cloudflare.net"

fun configureCronetEngineBuilder(builder: ExperimentalCronetEngine.Builder) {
    builder.enableBrotli(true)
        .enableQuic(true)
        .addQuicHint("e-hentai.org", 443, 443)
        .addQuicHint("api.e-hentai.org", 443, 443)
        .addQuicHint("upload.e-hentai.org", 443, 443)
        .addQuicHint("forums.e-hentai.org", 443, 443)
        .addQuicHint("exhentai.org", 443, 443)
        .addQuicHint("s.exhentai.org", 443, 443)
        .addQuicHint("cdn.jsdelivr.net", 443, 443)
    val cache = (appCtx.cacheDir.toOkioPath() / "http_cache").toFile().apply { mkdirs() }
    builder.setStoragePath(cache.absolutePath)
        .enableHttpCache(ExperimentalCronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 4096)
        .setUserAgent(Settings.userAgent)
    if (Settings.cloudflareIpOverride) {
        experimentalOptions = JSONObject().put(
            "HostResolverRules",
            JSONObject().put(
                "host_resolver_rules",
                "MAP *.e-hentai.org $CloudflareIP," +
                    "MAP e-hentai.org $CloudflareIP," +
                    "MAP exhentai.org $CloudflareIP," +
                    "MAP *.exhentai.org $CloudflareIP," +
                    "MAP cdn.jsdelivr.net $CloudflareIP",
            ),
        )
    } else {
        experimentalOptions = JSONObject().put(
            "HostResolverRules",
            JSONObject().put(
                "host_resolver_rules",
                "MAP *.e-hentai.org e-hentai.org$DNS_POISONING_CIRCUMVENTION_SUFFIX," +
                    "MAP e-hentai.org e-hentai.org$DNS_POISONING_CIRCUMVENTION_SUFFIX," +
                    "MAP exhentai.org exhentai.org$DNS_POISONING_CIRCUMVENTION_SUFFIX," +
                    "MAP *.exhentai.org exhentai.org$DNS_POISONING_CIRCUMVENTION_SUFFIX," +
                    "MAP cdn.jsdelivr.net cdn.jsdelivr.net$DNS_POISONING_CIRCUMVENTION_SUFFIX",
            ),
        )
    }
    builder.setExperimentalOptions(experimentalOptions.toString())
}
