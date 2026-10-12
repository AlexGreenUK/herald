package dev.hermeskotlin.android.wear

import dev.hermeskotlin.core.wear.WatchRelay
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** The watch companion's phone side: the relay to Hermes, the Data Layer under it, and its request notifications. */
val wearModule = module {
    single { WatchRelay(get(), get(), get(), get(), get(), get(), get(), get(), PhoneOutbox(androidContext())) }
    single { WearRequests(get(), get()) }
}
