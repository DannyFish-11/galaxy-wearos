package com.galaxy.wear.di

import com.galaxy.wear.data.AIPClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * LOW-FIX: Koin dependency injection module for Wear OS app.
 *
 * Provides singleton instances and view models following the app's
 * architectural layers:
 * - data: AIPClient (WebSocket transport)
 * - presentation: ViewModels for UI screens
 *
 * Usage: Initialize in GalaxyWearApplication.onCreate() via startKoin { modules(appModule) }
 */
val appModule = module {
    // Data layer — AIPClient is a singleton tied to Application scope
    single {
        AIPClient(
            context = androidContext(),
            scope = get()
        )
    }

    // Provide application coroutine scope for background operations
    single { org.koin.android.ext.koin.androidApplication().coroutineScope }

    // Presentation layer — ViewModels
    // viewModel { MainViewModel(get()) }
}

/**
 * Optional network module for injecting GatewayClient adapters.
 * Allows swapping transport implementations in tests.
 */
val networkModule = module {
    // Network adapters (BLE, MQTT) can be registered here for DI
    // single<com.galaxy.wear.network.GatewayClient> { get<AIPClient>() }
}
