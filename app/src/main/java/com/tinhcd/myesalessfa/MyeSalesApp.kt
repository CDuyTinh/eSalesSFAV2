package com.tinhcd.myesalessfa

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.tinhcd.myesalessfa.domain.model.SessionState
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import com.tinhcd.myesalessfa.push.PushNotifications
import com.tinhcd.myesalessfa.domain.repository.ReferenceDataSync
import com.tinhcd.myesalessfa.progress.TodayProgress
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MyeSalesApp : Application() {

    @Inject
    lateinit var referenceDataSync: ReferenceDataSync

    @Inject
    lateinit var todayProgress: TodayProgress

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate() {
        super.onCreate()

        // Before any push can arrive: FCM shows background ones on this channel.
        PushNotifications.createChannel(this)

        val process = ProcessLifecycleOwner.get()

        /*
         * Reference data refreshes when the app comes to the foreground, which covers
         * both the cold start and the phone that has been in a pocket since yesterday.
         * The sync itself decides whether anything is due, so this stays a plain
         * notification that the rep is looking at the app again.
         *
         * Process lifecycle rather than an activity callback: this is a question about
         * the app, not about any one screen, and ON_START here fires once per
         * foregrounding rather than once per activity.
         *
         * Going to the background is when the day's figures are worth pushing out:
         * the rep has just checked out or sent an order and is pocketing the phone,
         * and the widget and the working-day notification should say so.
         */
        process.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    referenceDataSync.syncIfStale()
                }

                override fun onStop(owner: LifecycleOwner) {
                    process.lifecycleScope.launch { todayProgress.refresh() }
                }
            },
        )

        // Another rep's figures must not stay on the home screen after sign-out.
        process.lifecycleScope.launch {
            authRepository.sessionState.collect { session ->
                if (session is SessionState.SignedOut) todayProgress.clear()
            }
        }
    }
}
