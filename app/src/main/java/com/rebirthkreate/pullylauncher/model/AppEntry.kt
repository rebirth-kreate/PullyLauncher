package com.rebirthkreate.pullylauncher.model

import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)
