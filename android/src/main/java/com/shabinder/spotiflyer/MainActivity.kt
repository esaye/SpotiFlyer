/*
 *  * Copyright (c)  2021  Shabinder Singh
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  *  You should have received a copy of the GNU General Public License
 *  *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.shabinder.spotiflyer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.extensions.compose.jetbrains.rememberRootComponent
import com.arkivanov.mvikotlin.logging.store.LoggingStoreFactory
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.github.k1rakishou.fsaf.FileChooser
import com.github.k1rakishou.fsaf.FileManager
import com.github.k1rakishou.fsaf.callback.directory.DirectoryChooserCallback
import com.google.accompanist.insets.ProvideWindowInsets
import com.google.accompanist.insets.navigationBarsPadding
import com.google.accompanist.insets.statusBarsHeight
import com.google.accompanist.insets.statusBarsPadding
import com.shabinder.common.di.*
import com.shabinder.common.di.worker.ForegroundService
import com.shabinder.common.models.Actions
import com.shabinder.common.models.DownloadStatus
import com.shabinder.common.models.PlatformActions
import com.shabinder.common.models.PlatformActions.Companion.SharedPreferencesKey
import com.shabinder.common.models.SpotiFlyerBaseDir
import com.shabinder.common.models.Status
import com.shabinder.common.models.TrackDetails
import com.shabinder.common.models.methods
import com.shabinder.common.root.SpotiFlyerRoot
import com.shabinder.common.root.SpotiFlyerRoot.Analytics
import com.shabinder.common.root.callbacks.SpotiFlyerRootCallBacks
import com.shabinder.common.uikit.*
import com.shabinder.spotiflyer.ui.NetworkDialog
import com.shabinder.spotiflyer.ui.PermissionDialog
import com.shabinder.spotiflyer.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.android.ext.android.inject
import org.matomo.sdk.extra.TrackHelper
import java.io.File

@ExperimentalAnimationApi
class MainActivity : ComponentActivity() {

    private val fetcher: FetchPlatformQueryResult by inject()
    private val dir: Dir by inject()
    private lateinit var root: SpotiFlyerRoot
    private val callBacks: SpotiFlyerRootCallBacks
        get() = root.callBacks
    private val trackStatusFlow = MutableSharedFlow<HashMap<String, DownloadStatus>>(1)
    private var permissionGranted = mutableStateOf(true)
    private lateinit var updateUIReceiver: BroadcastReceiver
    private lateinit var queryReceiver: BroadcastReceiver
    private val internetAvailability by lazy { ConnectionLiveData(applicationContext) }
    private val tracker get() = (application as App).tracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This app draws behind the system bars, so we want to handle fitting system windows
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            SpotiFlyerTheme {
                Surface(contentColor = colorOffWhite) {
                    ProvideWindowInsets {
                        permissionGranted = remember { mutableStateOf(true) }
                        val view = LocalView.current

                        Box {
                            root = SpotiFlyerRootContent(
                                rememberRootComponent(::spotiFlyerRoot),
                                Modifier.statusBarsPadding().navigationBarsPadding()
                            )
                            Spacer(
                                Modifier
                                    .statusBarsHeight()
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colors.background.copy(alpha = 0.65f))
                            )
                        }

                        LaunchedEffect(view) {
                            permissionGranted.value = checkPermissions()
                        }
                        NetworkDialog(isInternetAvailableState())
                        PermissionDialog(
                            permissionGranted.value,
                            { requestStoragePermission() },
                            { disableDozeMode(disableDozeCode) },
                            dir::enableAnalytics
                        )
                    }
                }
            }
        }
        initialise()
    }

    private fun initialise() {
        checkIfLatestVersion()
        handleIntentFromExternalActivity()
        if(dir.isAnalyticsEnabled){
            // Download/App Install Event
            TrackHelper.track().download().with(tracker)
        }
    }

    @Composable
    private fun isInternetAvailableState(): State<Boolean?> {
        return internetAvailability.observeAsState()
    }

    @Suppress("DEPRECATION")
    private fun setUpOnPrefClickListener() {
        /*Get User Permission to access External SD*//*
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, externalSDWriteAccess)*/
        val fileChooser = FileChooser(applicationContext)
        fileChooser.openChooseDirectoryDialog(object : DirectoryChooserCallback() {
            override fun onResult(uri: Uri) {
                println("treeUri = $uri")
                // Can be only used using SAF
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val treeDocumentFile = DocumentFile.fromTreeUri(applicationContext, uri)

                dir.setDownloadDirectory(uri)
                showPopUpMessage("New Download Directory Set")
                GlobalScope.launch {
                    dir.createDirectories()
                }
            }

            override fun onCancel(reason: String) {
                println("Canceled by user")
            }
        })
    }

    private fun showPopUpMessage(string: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            applicationContext,
            string,
            if(long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionGranted.value = checkPermissions()
    }

    private fun spotiFlyerRoot(componentContext: ComponentContext): SpotiFlyerRoot =
        SpotiFlyerRoot(
            componentContext,
            dependencies = object : SpotiFlyerRoot.Dependencies{
                override val storeFactory = LoggingStoreFactory(DefaultStoreFactory)
                override val database = this@MainActivity.dir.db
                override val fetchPlatformQueryResult = this@MainActivity.fetcher
                @SuppressLint("StaticFieldLeak")
                override val directories: Dir = this@MainActivity.dir
                override val downloadProgressReport: MutableSharedFlow<HashMap<String, DownloadStatus>> = trackStatusFlow
                override val actions = object: Actions {

                    override val platformActions = object : PlatformActions {
                        override val imageCacheDir: File = applicationContext.cacheDir
                        override val sharedPreferences = applicationContext.getSharedPreferences(SharedPreferencesKey,
                            MODE_PRIVATE
                        )

                        override fun addToLibrary(path: String) {
                            MediaScannerConnection.scanFile (
                                applicationContext,
                                listOf(path).toTypedArray(), null, null
                            )
                        }

                        override fun sendTracksToService(array: ArrayList<TrackDetails>) {
                            for (list in array.chunked(50)) {
                                val serviceIntent = Intent(this@MainActivity, ForegroundService::class.java)
                                serviceIntent.putParcelableArrayListExtra("object", list as ArrayList)
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                            }
                        }
                    }

                    override fun showPopUpMessage(string: String, long: Boolean) = this@MainActivity.showPopUpMessage(string,long)

                    override fun setDownloadDirectoryAction() = setUpOnPrefClickListener()

                    override fun queryActiveTracks() {
                        val serviceIntent = Intent(this@MainActivity, ForegroundService::class.java).apply {
                            action = "query"
                        }
                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                    }

                    override fun giveDonation() {
                        openPlatform("",platformLink = "https://razorpay.com/payment-button/pl_GnKuuDBdBu0ank/view/?utm_source=payment_button&utm_medium=button&utm_campaign=payment_button")
                    }

                    override fun shareApp() {
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, "Hey, checkout this excellent Music Downloader http://github.com/Shabinder/SpotiFlyer")
                            type = "text/plain"
                        }

                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }

                    override fun openPlatform(packageID: String, platformLink: String) {
                        val manager: PackageManager = applicationContext.packageManager
                        try {
                            val intent = manager.getLaunchIntentForPackage(packageID)
                                ?: throw PackageManager.NameNotFoundException()
                            intent.addCategory(Intent.CATEGORY_LAUNCHER)
                            startActivity(intent)
                        } catch (e: PackageManager.NameNotFoundException) {
                            val uri: Uri =
                                Uri.parse(platformLink)
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            startActivity(intent)
                        }
                    }

                    override fun writeMp3Tags(trackDetails: TrackDetails) {/*IMPLEMENTED*/}

                    override val isInternetAvailable get()  = internetAvailability.value ?: true
                }
                override val analytics = object: Analytics {
                    override fun appLaunchEvent() {
                        TrackHelper.track()
                            .event("events","App_Launch")
                            .name("App Launch").with(tracker)
                    }

                    override fun homeScreenVisit() {
                        if(dir.isAnalyticsEnabled){
                            // HomeScreen Visit Event
                            TrackHelper.track().screen("/main_activity/home_screen")
                                .title("HomeScreen").with(tracker)
                        }
                    }

                    override fun listScreenVisit() {
                        if(dir.isAnalyticsEnabled){
                            // ListScreen Visit Event
                            TrackHelper.track().screen("/main_activity/list_screen")
                                .title("ListScreen").with(tracker)
                        }
                    }

                    override fun donationDialogVisit() {
                        if (dir.isAnalyticsEnabled) {
                            // Donation Dialog Open Event
                            TrackHelper.track().screen("/main_activity/donation_dialog")
                                .title("DonationDialog").with(tracker)
                        }
                    }
                }
            }
        )


    @SuppressLint("ObsoleteSdkInt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when(requestCode) {
            disableDozeCode -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val pm =
                        getSystemService(Context.POWER_SERVICE) as PowerManager
                    val isIgnoringBatteryOptimizations =
                        pm.isIgnoringBatteryOptimizations(packageName)
                    if (isIgnoringBatteryOptimizations) {
                        // Already Ignoring battery optimization
                        permissionGranted.value = true
                    } else {
                        //Again Ask For Permission!!
                        disableDozeMode(disableDozeCode)
                    }
                }
            }

            externalSDWriteAccess -> {
                // Can be only used using SAF
                /*if (resultCode == RESULT_OK) {
                    val treeUri: Uri? = data?.data
                    if (treeUri == null){
                        showPopUpMessage("Some Error Occurred While Setting New Download Directory")
                    }else {
                        // Persistently save READ & WRITE Access to whole Selected Directory Tree
                        contentResolver.takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        dir.setDownloadDirectory(com.shabinder.common.models.File(
                            DocumentFile.fromTreeUri(applicationContext,treeUri)?.createDirectory("SpotiFlyer")!!)
                        )
                        showPopUpMessage("New Download Directory Set")
                        GlobalScope.launch {
                            dir.createDirectories()
                        }
                    }
                }*/
            }
        }
    }

    /*
    * Broadcast Handlers
    * */
    private fun initializeBroadcast(){
        val intentFilter = IntentFilter().apply {
            addAction(Status.QUEUED.name)
            addAction(Status.FAILED.name)
            addAction(Status.DOWNLOADING.name)
            addAction(Status.COMPLETED.name)
            addAction("Progress")
            addAction("Converting")
        }
        updateUIReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                //Update Flow with latest details
                if (intent != null) {
                    val trackDetails = intent.getParcelableExtra<TrackDetails?>("track")
                    trackDetails?.let { track ->
                        lifecycleScope.launch {
                            val latestMap = trackStatusFlow.replayCache.getOrElse(0
                            ) { hashMapOf() }.apply {
                                this[track.title] = when (intent.action) {
                                    Status.QUEUED.name -> DownloadStatus.Queued
                                    Status.FAILED.name -> DownloadStatus.Failed
                                    Status.DOWNLOADING.name -> DownloadStatus.Downloading()
                                    "Progress" ->  DownloadStatus.Downloading(intent.getIntExtra("progress", 0))
                                    "Converting" -> DownloadStatus.Converting
                                    Status.COMPLETED.name -> DownloadStatus.Downloaded
                                    else -> DownloadStatus.NotDownloaded
                                }
                            }
                            trackStatusFlow.emit(latestMap)
                            Log.i("Track Update",track.title + track.downloaded.toString())
                        }
                    }
                }
            }
        }
        val queryFilter = IntentFilter().apply { addAction("query_result") }
        queryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                //UI update here
                if (intent != null){
                    @Suppress("UNCHECKED_CAST")
                    val trackList = intent.getSerializableExtra("tracks") as? HashMap<String, DownloadStatus>?
                    trackList?.let { list ->
                        Log.i("Service Response", "${list.size} Tracks Active")
                        lifecycleScope.launch {
                            trackStatusFlow.emit(list)
                        }
                    }
                }
            }
        }
        registerReceiver(updateUIReceiver, intentFilter)
        registerReceiver(queryReceiver, queryFilter)
    }

    override fun onResume() {
        super.onResume()
        initializeBroadcast()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateUIReceiver)
        unregisterReceiver(queryReceiver)
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntentFromExternalActivity(intent)
    }

    private fun handleIntentFromExternalActivity(intent: Intent? = getIntent()) {
        if (intent?.action == Intent.ACTION_SEND) {
            if ("text/plain" == intent.type) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    val filterLinkRegex = """http.+\w""".toRegex()
                    val string = it.replace("\n".toRegex(), " ")
                    val link = filterLinkRegex.find(string)?.value.toString()
                    Log.i("Intent",link)
                    lifecycleScope.launch {
                        while(!this@MainActivity::root.isInitialized){
                            delay(100)
                        }
                        if(methods.value.isInternetAvailable)callBacks.searchLink(link)
                    }
                }
            }
        }
    }

    companion object {
        const val disableDozeCode = 1223
        const val externalSDWriteAccess = 1224
    }
}
