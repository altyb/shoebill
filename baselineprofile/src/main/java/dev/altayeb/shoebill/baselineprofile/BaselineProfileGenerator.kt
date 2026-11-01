package dev.altayeb.shoebill.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    // Cold start to the home tab is what every launch pays for; the rest of the app is used too rarely to profile.
    @Test fun startup() = rule.collect(packageName = "dev.altayeb.shoebill", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }
}
