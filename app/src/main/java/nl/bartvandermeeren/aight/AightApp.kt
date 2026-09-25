package nl.bartvandermeeren.aight

import android.app.Application
import android.content.Context
import nl.bartvandermeeren.aight.data.AppContainer

class AightApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as AightApp).container
