package com.petpulse.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.petpulse.app.AppContainer
import com.petpulse.app.PetPulseApp

/** Creates a ViewModel with access to the app container, scoped to the current nav destination. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as PetPulseApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
