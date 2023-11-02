package com.hippo.ehviewer.ui.settings

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.CHROME_USER_AGENT
import com.hippo.ehviewer.client.EhCookieStore
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.data.FavListUrlBuilder
import com.hippo.ehviewer.client.systemDns
import com.hippo.ehviewer.ui.legacy.EditTextDialogBuilder
import com.hippo.ehviewer.ui.tools.LocalDialogState
import com.hippo.ehviewer.ui.tools.observed
import com.hippo.ehviewer.ui.tools.rememberedAccessor
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.ReadableTime
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps

@Destination
@Composable
fun AdvancedScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope { Dispatchers.IO }
    val cloudflareIPhint = stringResource(id = R.string.settings_advanced_cloudflare_ip_hint)
    val cloudflareIPtitle = stringResource(id = R.string.settings_advanced_cloudflare_ip)
    fun launchSnackBar(content: String) = coroutineScope.launch { snackbarHostState.showSnackbar(content) }
    val dialogState = LocalDialogState.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_advanced)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).verticalScroll(rememberScrollState()).padding(paddingValues)) {
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_save_parse_error_body),
                summary = stringResource(id = R.string.settings_advanced_save_parse_error_body_summary),
                value = Settings::saveParseErrorBody,
            )
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_save_crash_log),
                summary = stringResource(id = R.string.settings_advanced_save_crash_log_summary),
                value = Settings::saveCrashLog,
            )
            val dumpLogError = stringResource(id = R.string.settings_advanced_dump_logcat_failed)
            LauncherPreference(
                title = stringResource(id = R.string.settings_advanced_dump_logcat),
                summary = stringResource(id = R.string.settings_advanced_dump_logcat_summary),
                contract = ActivityResultContracts.CreateDocument("application/zip"),
                key = "log-" + ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".zip",
            ) { uri ->
                uri?.run {
                    context.runCatching {
                        grantUriPermission(BuildConfig.APPLICATION_ID, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            val files = ArrayList<File>()
                            AppConfig.externalParseErrorDir?.listFiles()?.let { files.addAll(it) }
                            AppConfig.externalCrashDir?.listFiles()?.let { files.addAll(it) }
                            ZipOutputStream(outputStream).use { zipOs ->
                                files.forEach { file ->
                                    if (!file.isFile) return@forEach
                                    val entry = ZipEntry(file.name)
                                    zipOs.putNextEntry(entry)
                                    file.inputStream().use { it.copyTo(zipOs) }
                                }
                                val logcatEntry = ZipEntry("logcat-" + ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".txt")
                                zipOs.putNextEntry(logcatEntry)
                                Runtime.getRuntime().exec("logcat -d").inputStream.use { it.copyTo(zipOs) }
                            }
                            launchSnackBar(getString(R.string.settings_advanced_dump_logcat_to, uri.toString()))
                        }
                    }.onFailure {
                        launchSnackBar(dumpLogError)
                        it.printStackTrace()
                    }
                }
            }
            SimpleMenuPreferenceInt(
                title = stringResource(id = R.string.settings_advanced_read_cache_size),
                entry = R.array.read_cache_size_entries,
                entryValueRes = R.array.read_cache_size_entry_values,
                value = Settings::readCacheSize.observed,
            )
            SimpleMenuPreference(
                title = stringResource(id = R.string.settings_advanced_app_language_title),
                entry = R.array.app_language_entries,
                entryValueRes = R.array.app_language_entry_values,
                value = Settings::language,
            )
            SwitchPreference(
                title = stringResource(id = R.string.preload_thumb_aggressively),
                value = Settings::preloadThumbAggressively,
            )
            var userAgent by Settings::userAgent.observed
            val userAgentTitle = stringResource(id = R.string.user_agent)
            Preference(
                title = userAgentTitle,
                summary = userAgent,
            ) {
                coroutineScope.launch {
                    userAgent = dialogState.awaitInputText(
                        initial = userAgent,
                        title = userAgentTitle,
                    ).trim().takeUnless { it.isBlank() } ?: CHROME_USER_AGENT
                }
            }
            val exportFailed = stringResource(id = R.string.settings_advanced_export_data_failed)
            LauncherPreference(
                title = stringResource(id = R.string.settings_advanced_export_data),
                summary = stringResource(id = R.string.settings_advanced_export_data_summary),
                contract = ActivityResultContracts.CreateDocument("application/vnd.sqlite3"),
                key = ReadableTime.getFilenamableTime(System.currentTimeMillis()) + ".db",
            ) { uri ->
                uri?.let {
                    context.runCatching {
                        grantUriPermission(BuildConfig.APPLICATION_ID, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        EhDB.exportDB(context, uri)
                        launchSnackBar(getString(R.string.settings_advanced_export_data_to, uri.toString()))
                    }.onFailure {
                        it.printStackTrace()
                        launchSnackBar(exportFailed)
                    }
                }
            }
            val importFailed = stringResource(id = R.string.cant_read_the_file)
            val importSucceed = stringResource(id = R.string.settings_advanced_import_data_successfully)
            LauncherPreference(
                title = stringResource(id = R.string.settings_advanced_import_data),
                summary = stringResource(id = R.string.settings_advanced_import_data_summary),
                contract = ActivityResultContracts.GetContent(),
                key = "application/octet-stream",
            ) { uri ->
                uri?.let {
                    context.runCatching {
                        grantUriPermission(BuildConfig.APPLICATION_ID, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        EhDB.importDB(context, uri)
                        launchSnackBar(importSucceed)
                    }.onFailure {
                        it.printStackTrace()
                        launchSnackBar(importFailed)
                    }
                }
            }
            if (EhCookieStore.hasSignedIn()) {
                val backupNothing = stringResource(id = R.string.settings_advanced_backup_favorite_nothing)
                val backupFailed = stringResource(id = R.string.settings_advanced_backup_favorite_failed)
                val backupSucceed = stringResource(id = R.string.settings_advanced_backup_favorite_success)
                Preference(
                    title = stringResource(id = R.string.settings_advanced_backup_favorite),
                    summary = stringResource(id = R.string.settings_advanced_backup_favorite_summary),
                ) {
                    val favListUrlBuilder = FavListUrlBuilder()
                    var favTotal = 0
                    var favIndex = 0
                    tailrec suspend fun doBackup() {
                        val result = EhEngine.getFavorites(favListUrlBuilder.build())
                        if (result.galleryInfoList.isEmpty()) {
                            launchSnackBar(backupNothing)
                        } else {
                            if (favTotal == 0) favTotal = result.countArray.sum()
                            favIndex += result.galleryInfoList.size
                            val status = "($favIndex/$favTotal)"
                            EhDB.putLocalFavorites(result.galleryInfoList)
                            launchSnackBar(context.getString(R.string.settings_advanced_backup_favorite_start, status))
                            if (result.next != null) {
                                delay(Settings.downloadDelay.toLong())
                                favListUrlBuilder.setIndex(result.next, true)
                                doBackup()
                            }
                        }
                    }
                    coroutineScope.launch {
                        runSuspendCatching {
                            doBackup()
                        }.onSuccess {
                            launchSnackBar(backupSucceed)
                        }.onFailure {
                            it.printStackTrace()
                            launchSnackBar(backupFailed)
                        }
                    }
                }
            }
            Preference(title = stringResource(id = R.string.open_by_default)) {
                context.run {
                    try {
                        @SuppressLint("InlinedApi")
                        val intent = Intent(
                            ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                            Uri.parse("package:$packageName"),
                        )
                        startActivity(intent)
                    } catch (t: Throwable) {
                        val intent = Intent(
                            ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName"),
                        )
                        startActivity(intent)
                    }
                }
            }
            val isEnableCronet = Settings::enableCronet.observed
            val isEnabledF = Settings::dF.observed
            val ifCloudflareIPOverride = Settings::cloudflareIpOverride.observed
            if (isEnabledF.value) {
                isEnableCronet.value = false
            }
            if (isEnableCronet.value) {
                isEnabledF.value = false
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_cronet_integration),
                summary = stringResource(id = R.string.settings_advanced_cronet_integration_summary),
                value = isEnableCronet.rememberedAccessor,
                enabled = !isEnabledF.value,
            )
            AnimatedVisibility(visible = isEnableCronet.value) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_advanced_cloudflare_ip_override),
                    summary = stringResource(id = R.string.settings_advanced_cloudflare_ip_override_summary),
                    value = ifCloudflareIPOverride.rememberedAccessor,
                )
            }
            AnimatedVisibility(visible = ifCloudflareIPOverride.value && isEnableCronet.value) {
                Preference(
                    title = cloudflareIPtitle,
                    summary = Settings.cloudflareIp,
                ) {
                    coroutineScope.launch {
                        val newCloudflareIP = dialogState.awaitInputText(
                            initial = Settings.cloudflareIp.toString(),
                            title = cloudflareIPtitle,
                            hint = cloudflareIPhint,
                        )
                        if (newCloudflareIP.isNotEmpty()) {
                            Settings.cloudflareIp = newCloudflareIP
                        }
                    }
                }
            }
            SwitchPreference(
                title = stringResource(id = R.string.settings_advanced_domain_fronting_title),
                summary = stringResource(id = R.string.settings_advanced_domain_fronting_summary),
                value = isEnabledF.rememberedAccessor,
                enabled = !isEnableCronet.value,
            )
            AnimatedVisibility(visible = isEnabledF.value) {
                SwitchPreference(
                    title = stringResource(id = R.string.settings_advanced_built_in_hosts_title),
                    value = Settings::builtInHosts,
                )
            }
            AnimatedVisibility(visible = isEnabledF.value) {
                Preference(title = stringResource(id = R.string.settings_advanced_dns_over_http_title)) {
                    val builder = EditTextDialogBuilder(
                        context,
                        Settings.dohUrl,
                        context.getString(R.string.settings_advanced_dns_over_http_hint),
                    )
                    builder.setTitle(R.string.settings_advanced_dns_over_http_title)
                    builder.setPositiveButton(android.R.string.ok, null)
                    val dialog = builder.create().apply { show() }
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                        val text = builder.text.trim()
                        runCatching {
                            doh = if (text.isNotBlank()) buildDoHDNS(text) else null
                        }.onFailure {
                            builder.setError("Invalid URL!")
                        }.onSuccess {
                            Settings.dohUrl = text
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
    }
}

private fun buildDoHDNS(url: String): DnsOverHttps {
    return DnsOverHttps.Builder().apply {
        client(EhApplication.okHttpClient)
        url(url.toHttpUrl())
        post(true)
        systemDns(systemDns)
    }.build()
}

private var doh: DnsOverHttps? = Settings.dohUrl.runCatching { buildDoHDNS(this) }.getOrNull()

object EhDoH {
    fun lookup(hostname: String): List<InetAddress>? = doh?.runCatching { lookup(hostname).takeIf { it.isNotEmpty() } }?.onFailure { it.printStackTrace() }?.getOrNull()
}
