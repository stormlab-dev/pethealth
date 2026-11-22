package com.petpulse.core.integrations

import com.petpulse.core.integrations.pharmacy.PharmacyProvider
import com.petpulse.core.integrations.vet.VetClinicProvider

/** Single place the app looks up partners. Swap mocks for real providers here. */
class IntegrationRegistry(
    val pharmacies: List<PharmacyProvider>,
    val clinics: List<VetClinicProvider>,
) {
    fun pharmacy(id: String): PharmacyProvider =
        pharmacies.firstOrNull { it.info.id == id } ?: throw IntegrationException.NotFound("Pharmacy provider $id")

    fun clinicNetwork(id: String): VetClinicProvider =
        clinics.firstOrNull { it.info.id == id } ?: throw IntegrationException.NotFound("Clinic provider $id")
}
