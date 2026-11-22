package com.project.saaya.ui.state

/** Photos never request microphone permission. Video in this milestone includes audio. */
fun evidencePermissions(withAudio: Boolean): Array<String> = if (withAudio)
    arrayOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
else arrayOf("android.permission.CAMERA")

fun evidenceCaptureHasSpace(localBytes: Long): Boolean = localBytes < 200L * 1024 * 1024
