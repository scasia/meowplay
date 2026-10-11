package com.meowplay.app

import android.app.Application
import android.util.Log
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.weblink.sdk.WebLinkManager
import java.io.File

class MeowPlayApplication : Application() {
    companion object {
        private const val TAG = "MeowPlayApplication"
        lateinit var instance: MeowPlayApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "MeowPlay 🐱 Application initializing...")

        // Install offline MFi credentials to private storage
        installOfflineMfiCredentials()

        // Initialize WebLink manager
        WebLinkManager.getInstance().init(this)
    }

    private fun installOfflineMfiCredentials() {
        try {
            val targetDir = File(noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
            if (!targetDir.exists()) {
                val staging = File(noBackupFilesDir, "offline-mfi-staging")
                staging.deleteRecursively()
                staging.mkdirs()
                staging.setReadable(true, true)
                staging.setExecutable(true, true)

                val files = listOf("identity.pk8", "certificate.p7b")
                for (name in files) {
                    val dest = File(staging, name)
                    assets.open("offline-mfi/$name").use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    dest.setReadable(true, true)
                    dest.setWritable(true, true)
                }

                // Verify credentials with LocalMfiAuthenticationClient
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(targetDir)) { "Failed to install MFi directory" }
                Log.i(TAG, "Offline MFi credentials installed successfully to: ${targetDir.absolutePath}")
            } else {
                LocalMfiAuthenticationClient.load(targetDir)
                Log.i(TAG, "Verified existing offline MFi credentials at: ${targetDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load/install offline MFi credentials", e)
        }
    }
}
