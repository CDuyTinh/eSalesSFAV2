package com.tinhcd.myesalessfa.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE = "com.tinhcd.myesalessfa"

/**
 * The first screen settled: whatever the app opens on -- the sign-in form, or
 * Tổng quan for a rep already signed in -- has drawn and stopped moving.
 */
fun MacrobenchmarkScope.awaitFirstScreen() {
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
    device.waitForIdle()
}

/**
 * What a rep does first thing: open the app and scroll what it shows. The
 * scroll is what pulls the list, card and image code into the profile, which
 * is where a cold app stutters. Found by behaviour, not by text, so the
 * journey runs in either language.
 */
fun MacrobenchmarkScope.scrollFirstScreen() {
    val list = device.findObject(By.scrollable(true)) ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    list.fling(Direction.UP)
    device.waitForIdle()
}
