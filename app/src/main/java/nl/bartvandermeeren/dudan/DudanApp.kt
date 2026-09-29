package nl.bartvandermeeren.dudan

import android.app.Application
import android.content.Context
import nl.bartvandermeeren.dudan.data.AppContainer

class DudanApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DudanApp).container
