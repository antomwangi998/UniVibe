package com.univibe.app.data.repository

import kotlinx.coroutines.CancellationException

/**
 * For taps that already changed the screen (like, save, repost): sends [call] to the server and, if it fails,
 * runs [revert] to put the old state back and [onFail] to tell the person. Cancellation (leaving the screen) is
 * not a failure and is passed on untouched.
 *
 * [revert] should only undo the change if the item still shows the value this tap set, so a quick second tap
 * that already flipped it back is not undone twice.
 */
suspend fun optimisticCall(call: suspend () -> Unit, revert: () -> Unit, onFail: () -> Unit) {
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        revert()
        onFail()
    }
}
