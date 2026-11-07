package com.tinhcd.myesalessfa.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods the app runs on its way to a usable
 * first screen, so they ship compiled ahead of time instead of being
 * interpreted, then JIT-compiled, on every cold start until the phone gets
 * round to compiling them in the background days later.
 *
 * The output, baseline-prof.txt, is committed under app/src/release/generated.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        awaitFirstScreen()
        scrollFirstScreen()
    }
}
