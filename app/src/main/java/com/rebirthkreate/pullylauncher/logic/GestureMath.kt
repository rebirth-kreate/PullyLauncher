package com.rebirthkreate.pullylauncher.logic

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt

fun Offset.length(): Float {
    return sqrt(x * x + y * y)
}

fun Offset.normalized(): Offset {
    val len = length()
    return if (len == 0f) Offset.Zero else Offset(x / len, y / len)
}

fun dot(a: Offset, b: Offset): Float {
    return a.x * b.x + a.y * b.y
}

operator fun Offset.times(value: Float): Offset {
    return Offset(x * value, y * value)
}

operator fun Offset.plus(other: Offset): Offset {
    return Offset(x + other.x, y + other.y)
}

operator fun Offset.minus(other: Offset): Offset {
    return Offset(x - other.x, y - other.y)
}