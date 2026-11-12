package dev.carapps.quiz

import android.content.Context

object BuildConfigInfo {
    fun version(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
