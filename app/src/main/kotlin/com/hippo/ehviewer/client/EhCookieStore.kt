package com.hippo.ehviewer.client

import android.util.Log
import android.webkit.CookieManager
import androidx.compose.ui.util.fastForEach
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import io.ktor.http.parseClientCookiesHeader
import io.ktor.http.renderSetCookieHeader

object EhCookieStore : CookiesStorage {
    private val manager = CookieManager.getInstance()
    fun removeAllCookies() = manager.removeAllCookies(null)

    fun hasSignedIn(): Boolean = getCookies(EhUrl.HOST_E)?.run {
        containsKey(KEY_IPB_MEMBER_ID) && containsKey(KEY_IPB_PASS_HASH)
    } ?: false

    const val KEY_IPB_MEMBER_ID = "ipb_member_id"
    const val KEY_IPB_PASS_HASH = "ipb_pass_hash"
    const val KEY_IGNEOUS = "igneous"
    private const val KEY_STAR = "star"
    private const val KEY_CONTENT_WARNING = "nw"
    private const val CONTENT_WARNING_NOT_SHOW = "1"
    private const val KEY_UTMP_NAME = "__utmp"
    private val sTipsCookie = Cookie(
        name = KEY_CONTENT_WARNING,
        value = CONTENT_WARNING_NOT_SHOW,
    )

    fun clearIgneous() {
        manager.setCookie(
            EhUrl.HOST_EX,
            renderSetCookieHeader(KEY_IGNEOUS, "", maxAge = 0, domain = EhUrl.DOMAIN_EX, path = "/"),
        )
    }

    fun getIdentityCookies(): List<Pair<String, String?>> {
        val eCookies = getCookies(EhUrl.HOST_E)
        val exCookies = getCookies(EhUrl.HOST_EX)
        val ipbMemberId = eCookies?.get(KEY_IPB_MEMBER_ID)
        val ipbPassHash = eCookies?.get(KEY_IPB_PASS_HASH)
        val igneous = exCookies?.get(KEY_IGNEOUS)
        return listOf(
            KEY_IPB_MEMBER_ID to ipbMemberId,
            KEY_IPB_PASS_HASH to ipbPassHash,
            KEY_IGNEOUS to igneous,
        )
    }

    fun copyNecessaryCookies() {
        val cookies = load(Url(EhUrl.HOST_E))
        cookies.fastForEach {
            if (it.name == KEY_STAR || it.name == KEY_IPB_MEMBER_ID || it.name == KEY_IPB_PASS_HASH) {
                manager.setCookie(EhUrl.HOST_EX, it.copy(maxAge = Int.MAX_VALUE).toString())
            }
        }
    }

    fun addCookie(k: String, v: String, domain: String) {
        val cookie = Cookie(name = k, value = v, domain = domain, maxAge = Int.MAX_VALUE)
        val url = if (EhUrl.DOMAIN_E == cookie.domain) EhUrl.HOST_E else EhUrl.HOST_EX
        manager.setCookie(url, renderSetCookieHeader(cookie))
    }

    fun flush() = manager.flush()

    fun getCookieHeader(url: String): String? = manager.getCookie(url)

    // See https://github.com/Ehviewer-Overhauled/Ehviewer/issues/873
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        val shouldIgnore = cookie.value == "0" || (cookie.name == "igneous" && cookie.value == "deleted") || cookie.name == KEY_UTMP_NAME
        if (shouldIgnore) {
            Log.d("EhCookieStore", "Ignoring cookie: ${cookie.name} with value ${cookie.value} because it meets the ignore criteria.")
        } else {
            manager.setCookie(requestUrl.toString(), renderSetCookieHeader(cookie))
        }
    }

    override fun close() = Unit

    private fun getCookies(url: String) = manager.getCookie(url)?.let { parseClientCookiesHeader(it) }

    fun load(url: Url): List<Cookie> {
        val checkTips = EhUrl.DOMAIN_E in url.host
        return getCookies(url.toString())?.mapTo(mutableListOf()) {
            Cookie(it.key, it.value)
        }?.apply {
            if (checkTips) {
                add(sTipsCookie)
            }
        } ?: if (checkTips) listOf(sTipsCookie) else emptyList()
    }

    override suspend fun get(requestUrl: Url) = load(requestUrl)
}
