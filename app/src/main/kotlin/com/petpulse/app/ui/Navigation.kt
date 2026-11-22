package com.petpulse.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.petpulse.app.ui.screens.*

object Routes {
    const val PETS = "pets"
    fun editPet(id: Long? = null) = "pet/edit?id=${id ?: -1}"
    fun pet(id: Long) = "pet/$id"
    fun logSymptoms(petId: Long) = "pet/$petId/symptoms"
    fun editMed(petId: Long, medId: Long? = null) = "pet/$petId/med?medId=${medId ?: -1}"
    fun pharmacy(petId: Long, medId: Long? = null) = "pet/$petId/pharmacy?medId=${medId ?: -1}"
    fun vet(petId: Long, reason: String = "") = "pet/$petId/vet?reason=${android.net.Uri.encode(reason)}"
}

@Composable
fun PetPulseNavHost(startPetId: Long?) {
    val nav = rememberNavController()
    val petArg = navArgument("petId") { type = NavType.LongType }
    val optLong = { name: String -> navArgument(name) { type = NavType.LongType; defaultValue = -1L } }
    fun Long.orNull() = takeIf { it >= 0 }

    NavHost(nav, startDestination = Routes.PETS) {
        composable(Routes.PETS) {
            PetListScreen(onOpen = { nav.navigate(Routes.pet(it)) }, onAdd = { nav.navigate(Routes.editPet()) })
        }
        composable("pet/edit?id={id}", arguments = listOf(optLong("id"))) { e ->
            PetEditScreen(
                petId = e.arguments!!.getLong("id").orNull(),
                onDone = { nav.popBackStack() },
                onDeleted = { nav.popBackStack(Routes.PETS, inclusive = false) },
            )
        }
        composable("pet/{petId}", arguments = listOf(petArg)) { e ->
            val petId = e.arguments!!.getLong("petId")
            PetDetailScreen(
                petId = petId,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate(Routes.editPet(petId)) },
                onLogSymptoms = { nav.navigate(Routes.logSymptoms(petId)) },
                onEditMed = { nav.navigate(Routes.editMed(petId, it)) },
                onPharmacy = { nav.navigate(Routes.pharmacy(petId, it)) },
                onVet = { nav.navigate(Routes.vet(petId, it)) },
            )
        }
        composable("pet/{petId}/symptoms", arguments = listOf(petArg)) { e ->
            val petId = e.arguments!!.getLong("petId")
            SymptomLogScreen(
                petId = petId,
                onDone = { nav.popBackStack() },
                onBookVet = { reason -> nav.navigate(Routes.vet(petId, reason)) { popUpTo("pet/{petId}") } },
            )
        }
        composable("pet/{petId}/med?medId={medId}", arguments = listOf(petArg, optLong("medId"))) { e ->
            MedicationEditScreen(e.arguments!!.getLong("petId"), e.arguments!!.getLong("medId").orNull(), onDone = { nav.popBackStack() })
        }
        composable("pet/{petId}/pharmacy?medId={medId}", arguments = listOf(petArg, optLong("medId"))) { e ->
            PharmacyScreen(e.arguments!!.getLong("petId"), e.arguments!!.getLong("medId").orNull(), onBack = { nav.popBackStack() })
        }
        composable(
            "pet/{petId}/vet?reason={reason}",
            arguments = listOf(petArg, navArgument("reason") { type = NavType.StringType; defaultValue = "" }),
        ) { e ->
            VetScreen(e.arguments!!.getLong("petId"), e.arguments!!.getString("reason").orEmpty(), onBack = { nav.popBackStack() })
        }
    }

    // Opened from a notification: jump straight to that pet.
    LaunchedEffect(startPetId) {
        startPetId?.let { nav.navigate(Routes.pet(it)) }
    }
}
