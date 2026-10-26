package nl.bartvandermeeren.dudan

import android.app.Application
import android.content.Context
import nl.bartvandermeeren.dudan.data.AppContainer

class DudanApp : Application() {
    private val lazyContainer = lazy { AppContainer(this) }
    val container: AppContainer by lazyContainer

    /** The container when something in this process made it already. Background work uses it without starting the app's services. */
    val existingContainer: AppContainer? get() = if (lazyContainer.isInitialized()) lazyContainer.value else null
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DudanApp).container
