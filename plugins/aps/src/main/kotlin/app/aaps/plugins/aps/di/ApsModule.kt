package app.aaps.plugins.aps.di

import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.autotune.Autotune
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.autotune.AutotunePlugin
import app.aaps.plugins.aps.loop.LoopPlugin
import app.aaps.plugins.aps.openAPSBoost.GpsActivityTransitionReceiver
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module(
    includes = [
        AutotuneModule::class,
        LoopModule::class,
        ApsModule.Bindings::class
    ]
)

@Suppress("unused")
abstract class ApsModule {

    @ContributesAndroidInjector abstract fun contributesOpenAPSFragment(): OpenAPSFragment
    // Konzept 8 GPS part (2026-08-27) — Dagger field injection for the manifest-registered receiver.
    @ContributesAndroidInjector abstract fun contributesGpsActivityTransitionReceiver(): GpsActivityTransitionReceiver

    @Module
    interface Bindings {

        @Binds fun bindLoop(loopPlugin: LoopPlugin): Loop
        @Binds fun bindAutotune(autotunePlugin: AutotunePlugin): Autotune
    }
}