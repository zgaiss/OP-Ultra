package com.shilapi.xcertplay

import android.content.res.Configuration

internal fun isDarkMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun currentDarkMode(applicationUiMode: Int, activityUiMode: Int, previous: Boolean): Boolean {
    for (mode in intArrayOf(applicationUiMode, activityUiMode)) {
        when (mode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> return true
            Configuration.UI_MODE_NIGHT_NO -> return false
        }
    }
    return previous
}
