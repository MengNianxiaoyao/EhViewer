package com.hippo.ehviewer.cronet

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.CHROME_ACCEPT
import com.hippo.ehviewer.client.CHROME_ACCEPT_LANGUAGE
import com.hippo.ehviewer.client.EhCookieStore
import java.nio.ByteBuffer
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okio.Buffer
import okio.Path.Companion.toOkioPath
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
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
        .enableHttpCache(ExperimentalCronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, 100 * 1024)
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

// TODO: Rewrite this to use android.net.http.HttpEngine and make it Android 14 only when released
class CronetRequest {
    lateinit var consumer: (ByteBuffer) -> Unit
    lateinit var onResponse: CronetRequest.(UrlResponseInfo) -> Unit
    lateinit var request: UrlRequest
    lateinit var onError: (Throwable) -> Unit
    lateinit var readerCont: Continuation<Unit>
    val callback = object : UrlRequest.Callback() {
        override fun onRedirectReceived(req: UrlRequest, info: UrlResponseInfo, url: String) {
            req.followRedirect()
        }

        override fun onResponseStarted(req: UrlRequest, info: UrlResponseInfo) {
            onResponse(info)
        }

        override fun onReadCompleted(req: UrlRequest, info: UrlResponseInfo, data: ByteBuffer) {
            consumer(data)
        }

        override fun onSucceeded(req: UrlRequest, info: UrlResponseInfo) {
            readerCont.resume(Unit)
        }

        override fun onFailed(req: UrlRequest, info: UrlResponseInfo?, e: CronetException) {
            onError(e)
        }
    }
}

inline fun cronetRequest(url: String, referer: String? = null, origin: String? = null, conf: UrlRequest.Builder.() -> Unit = {}) = CronetRequest().apply {
    request = cronetHttpClient.newUrlRequestBuilder(url, callback, cronetHttpClientExecutor).apply {
        addHeader("Cookie", EhCookieStore.getCookieHeader(url.toHttpUrl()))
        addHeader("Accept", CHROME_ACCEPT)
        addHeader("Accept-Language", CHROME_ACCEPT_LANGUAGE)
        referer?.let { addHeader("Referer", it) }
        origin?.let { addHeader("Origin", it) }
    }.apply(conf).build()
}

suspend inline fun <R> CronetRequest.execute(crossinline callback: suspend CronetRequest.(UrlResponseInfo) -> R): R {
    contract {
        callsInPlace(callback, InvocationKind.EXACTLY_ONCE)
    }
    return coroutineScope {
        suspendCancellableCoroutine { cont ->
            onResponse = { launch { cont.resume(callback(it)) } }
            cont.invokeOnCancellation { request.cancel() }
            onError = { cont.resumeWithException(it) }
            request.start()
        }
    }
}

fun UrlRequest.Builder.withRequestBody(body: RequestBody) {
    addHeader("Content-Type", body.contentType().toString())
    val buffer = Buffer().apply { body.writeTo(this) }
    val provider = object : UploadDataProvider() {
        override fun getLength() = body.contentLength()
        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            buffer.read(byteBuffer)
            uploadDataSink.onReadSucceeded(false)
        }
        override fun rewind(uploadDataSink: UploadDataSink) {
            error("OneShot!")
        }
    }
    setUploadDataProvider(provider, cronetHttpClientExecutor)
}

fun UrlRequest.Builder.noCache(): UrlRequest.Builder = disableCache()
fun UrlResponseInfo.getHeadersMap(): Map<String, List<String>> = allHeaders
