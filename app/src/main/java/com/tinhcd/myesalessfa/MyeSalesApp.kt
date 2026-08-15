package com.tinhcd.myesalessfa

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyeSalesApp : Application() {

    @Inject
    lateinit var referenceDataSync: ReferenceDataSync

    override fun onCreate() {
        super.onCreate()

        /*
         * Reference data refreshes when the app comes to the foreground, which covers
         * both the cold start and the phone that has been in a pocket since yesterday.
         * The sync itself decides whether anything is due, so this stays a plain
         * notification that the rep is looking at the app again.
         *
         * Process lifecycle rather than an activity callback: this is a question about
         * the app, not about any one screen, and ON_START here fires once per
         * foregrounding rather than once per activity.
         */
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    referenceDataSync.syncIfStale()
                }
            },
        )
    }
}
