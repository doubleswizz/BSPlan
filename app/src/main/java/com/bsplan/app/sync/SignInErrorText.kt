package com.bsplan.app.sync

import com.bsplan.app.api.NetworkState
import com.bsplan.app.api.NetworkStatus

/**
 * Google sign-in failures in the user's words (Oct 2026). Before this the calendar accounts showed
 * "Sign-in failed: Sign-in failed: 10: " (reported 21 Jan), a cancelled sign-in showed nothing (a
 * sign-in "loop", reported 14 Dec), and a failed Google Drive sign-in for backups showed nothing.
 * Codes are GoogleSignInStatusCodes / CommonStatusCodes.
 */
object SignInErrorText {
    const val NETWORK_ERROR = 7
    const val DEVELOPER_ERROR = 10
    const val SIGN_IN_FAILED = 12500
    const val CANCELLED = 12501
    const val IN_PROGRESS = 12502

    /** The text for a Google sign-in that ended with [statusCode] (null: no code, an unexpected error). */
    fun google(statusCode: Int?, network: NetworkState = NetworkStatus.current()): String = when {
        statusCode == CANCELLED -> "Sign-in was cancelled."
        statusCode == IN_PROGRESS -> "Sign-in is already in progress."
        network == NetworkState.NONE -> "No internet connection (Wi-Fi is off)."
        network == NetworkState.NO_INTERNET -> "No internet connection."
        statusCode == NETWORK_ERROR -> "Couldn't reach Google. Check the internet connection, then try again."
        statusCode == DEVELOPER_ERROR ->
            "Google didn't accept BSPlan's sign-in (code 10). This is a setup problem in BSPlan, " +
                "not your account. Please report it on GitHub."
        statusCode == SIGN_IN_FAILED ->
            "Google sign-in failed. Turn on Google Play in the BOOX settings and make sure Google Play " +
                "services is up to date, then try again."
        statusCode == null -> "Google sign-in failed."
        else -> "Google sign-in failed (code $statusCode)."
    }
}
