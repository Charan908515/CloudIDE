package com.cloudide.android

import android.app.Application
import com.cloudide.android.data.auth.AuthManager
import com.cloudide.android.data.terminal.ProotEnvironment
import com.cloudide.android.data.drive.DriveService
import com.cloudide.android.data.drive.ProjectRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

class CloudIdeApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    val authManager: AuthManager by lazy { AuthManager(this) }

    val driveService: DriveService by lazy { DriveService(httpClient, authManager) }

    val projectRepository: ProjectRepository by lazy { ProjectRepository(driveService) }

    val prootEnvironment: ProotEnvironment by lazy { ProotEnvironment(this) }

    override fun onCreate() {
        super.onCreate()
        prootEnvironment.ensureInitializedInBackground(applicationScope)
    }
}
