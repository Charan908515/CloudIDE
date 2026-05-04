package com.cloudide.android.data.auth

import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.cloudide.android.BuildConfig
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class CloudIdeUser(
    val email: String,
    val displayName: String,
    val photoUrl: String?,
)

class AuthManager(private val appContext: Context) {

    private val signInOptions: GoogleSignInOptions by lazy {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(DRIVE_SCOPE))

        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()) {
            builder.requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
        }
        builder.build()
    }

    private val client: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(appContext, signInOptions)
    }

    private val _user = MutableStateFlow<CloudIdeUser?>(null)
    val user: StateFlow<CloudIdeUser?> = _user.asStateFlow()

    init {
        GoogleSignIn.getLastSignedInAccount(appContext)?.let { adopt(it) }
    }

    fun signInIntent(): Intent = client.signInIntent

    suspend fun handleSignInResult(result: ActivityResult): Result<CloudIdeUser> = withContext(Dispatchers.IO) {
        runCatching {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                ?: throw IllegalStateException("Sign-in returned no account")
            adopt(account)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching { client.signOut() }
        _user.value = null
    }

    /** Gets a fresh OAuth access token for Drive API calls. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(appContext) ?: return@withContext null
        runCatching {
            GoogleAuthUtil.getToken(appContext, account.account!!, "oauth2:$DRIVE_SCOPE")
        }.getOrNull()
    }

    private fun adopt(account: GoogleSignInAccount): CloudIdeUser {
        val user = CloudIdeUser(
            email = account.email.orEmpty(),
            displayName = account.displayName ?: account.email.orEmpty(),
            photoUrl = account.photoUrl?.toString(),
        )
        _user.value = user
        return user
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }
}
