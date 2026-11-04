package com.jijilabsdigital.swell

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jijilabsdigital.swell.ui.AddContactScreen
import com.jijilabsdigital.swell.ui.ChatScreen
import com.jijilabsdigital.swell.ui.ChatsScreen
import com.jijilabsdigital.swell.ui.ContactInfoScreen
import com.jijilabsdigital.swell.ui.Onboarding
import com.jijilabsdigital.swell.ui.SettingsScreen
import com.jijilabsdigital.swell.ui.SwellTheme

class SwellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Swell.init(this)
    }
}

class MainActivity : FragmentActivity() {
    /** A code that arrived through a swell: link, waiting for the Add contact screen. */
    private var incomingCode by mutableStateOf<String?>(null)
    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onStart() {
        super.onStart()
        if (Swell.storageBroken.value) return
        Swell.setForeground(true)
        // Lock again after 30 s in the background (or on a fresh start).
        if (Swell.appLock && (stoppedAt == 0L || System.currentTimeMillis() - stoppedAt > 30_000)) locked = true
    }

    override fun onStop() {
        super.onStop()
        Swell.setForeground(false)
        stoppedAt = System.currentTimeMillis()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Swell.storageBroken.value) {
            // The database key is gone (audit L-06): explain and offer a clean start instead of crashing.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            setContent { SwellTheme { com.jijilabsdigital.swell.ui.BrokenStorage { Swell.deleteEverything(this) } } }
            return
        }
        applySecure(Swell.blockScreenshots)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Swell.account != null) SwellService.start(this)
        handleIntent(intent)
        setContent {
            SwellTheme {
                val hasAccount by Swell.hasAccount.collectAsStateWithLifecycle()
                when {
                    !hasAccount -> Onboarding()
                    locked -> com.jijilabsdigital.swell.ui.LockScreen { Auth.request(this) { locked = false } }
                    else -> Main()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Main() {
        val nav = rememberNavController()
        val code = incomingCode
        androidx.compose.runtime.LaunchedEffect(code) { if (code != null) nav.navigate("add") }
        // Someone used our code: ask to check the safety number instead of opening the chat silently (audit H-01).
        val prompt by Swell.newContactPrompt.collectAsStateWithLifecycle()
        prompt?.let { id ->
            com.jijilabsdigital.swell.ui.NewContactDialog(
                id,
                onCheck = { Swell.newContactPrompt.value = null; nav.navigate("info/$id") },
                onLater = { Swell.newContactPrompt.value = null; nav.navigate("chat/$id") { popUpTo("chats") } },
            )
        }
        NavHost(nav, startDestination = "chats") {
            composable("chats") {
                ChatsScreen(onOpen = { id, m -> nav.navigate(if (m != null) "chat/$id?m=$m" else "chat/$id") }, onAdd = { nav.navigate("add") }, onSettings = { nav.navigate("settings") })
            }
            composable(
                "chat/{id}?m={m}",
                arguments = listOf(androidx.navigation.navArgument("m") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null }),
            ) { e ->
                val id = e.arguments!!.getString("id")!!
                ChatScreen(id, onBack = { nav.popBackStack() }, onInfo = { nav.navigate("info/$id") }, jumpTo = e.arguments?.getString("m"))
            }
            composable("info/{id}") { e ->
                ContactInfoScreen(e.arguments!!.getString("id")!!, onBack = { nav.popBackStack() }, onDeleted = { nav.popBackStack("chats", false) })
            }
            composable("add") {
                AddContactScreen(
                    incoming = incomingCode.also { incomingCode = null },
                    onBack = { nav.popBackStack() },
                    onConnected = { id -> nav.navigate("chat/$id") { popUpTo("chats") } },
                )
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onSecureChanged = { applySecure(it) }) }
        }
    }

    private fun applySecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.data?.takeIf { it.scheme == "swell" }?.let { incomingCode = it.toString() }
        // https://jijilabsdigital.com/swell/i#<code>: the code is in the fragment (never sent to the web server).
        intent.data?.takeIf { it.scheme == "https" }?.let { Links.codeFrom(it.toString()) }?.let { incomingCode = it }
        if (BuildConfig.DEBUG) debugHooks(intent)
    }

    /** Debug builds only: lets the Mac drive tests over adb (`am start --es accept <code>` …). */
    private fun debugHooks(intent: Intent) {
        if (intent.hasExtra("autosetup") && Swell.account == null) {
            Swell.createAccount()
            Swell.profileName = intent.getStringExtra("autosetup") ?: "Pixel"
            Swell.finishOnboarding(this)
        }
        intent.getStringExtra("accept")?.let { code ->
            Swell.accept(code, onDone = { Log.i("Swell", "ACCEPTED ${it.short}") }, onError = { Log.i("Swell", "ERROR $it") })
        }
        intent.getStringExtra("send")?.let { text ->
            Swell.whenOnline { n -> n.contacts().lastOrNull()?.let { Swell.send(it.id, text) } }
        }
        // File in the app's external files dir -> our profile photo (same path as the photo picker).
        intent.getStringExtra("avatar")?.let { name ->
            Swell.work { runCatching { Swell.makeAvatar(android.net.Uri.fromFile(java.io.File(getExternalFilesDir(null), name))) }.onSuccess { Swell.setMyAvatar(it); Log.i("Swell", "AVATAR set ${it.size} B") }.onFailure { Log.i("Swell", "AVATAR failed ${it}") } }
        }
        if (intent.hasExtra("newinvite")) Swell.newInvite(fresh = true) { }
        intent.getStringExtra("mailbox")?.let { Swell.mailboxOnion = it; Log.i("Swell", "MAILBOX set") }
        if (intent.hasExtra("verifylast")) Swell.whenOnline { n -> n.contacts().lastOrNull()?.let { n.setVerified(it, true) } }
        if (intent.hasExtra("poll")) Swell.whenOnline { Swell.pollNow() }
        // File in the app's external files dir (adb push, no storage permission needed).
        intent.getStringExtra("sendphoto")?.let { name ->
            Swell.whenOnline { n ->
                val f = java.io.File(getExternalFilesDir(null), name)
                val t0 = System.nanoTime()
                val c = n.contacts().last()
                val bytes = Swell.readPhoto(android.net.Uri.fromFile(f))
                Log.i("Swell", "PHOTO prepared ${f.length()} -> ${bytes.size} B")
                n.sendFile(c, "photo.jpg", "image/jpeg", bytes, "foto dari Pixel")
                Log.i("Swell", "PHOTO sent in ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
    }
}
