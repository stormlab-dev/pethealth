# Pharmacy and vet clinic integrations

CuteFamilyPet talks to partners only through two interfaces in `:core`, so a real partner is a new
class plus one line in `AppContainer`:

| Interface | Mock (ships today) | What it does |
|---|---|---|
| `PharmacyProvider` | `MockPharmacyProvider` | Link account (OAuth), list prescriptions, request refill, track order, browse catalog |
| `VetClinicProvider` | `MockVetClinicProvider` | Find clinics, verify owner and link a patient, pull medical records, list slots, book/cancel |

Both mocks simulate latency and state (orders move from awaiting vet approval to shipped to
delivered; booked slots disappear) so every screen can be exercised. Demo clinic verification
code: `123456`.

`IntegrationRegistry` holds a list of each, so several pharmacies or clinic networks can live side
by side. `ProviderInfo.capabilities` lets the UI hide features a partner doesn't support.

## Adding a real partner

1. Implement the interface in a new module (e.g. `:integrations:vetsource`) using Ktor or Retrofit.
2. Account linking: open the partner's OAuth page in a Custom Tab, catch the redirect, call
   `link(authCode)`. Keep tokens in EncryptedSharedPreferences / Android Keystore, never in Room.
   Better still, keep partner credentials on your backend (see `CLOUD_AND_WEB.md`) and have the
   app call your API; most partners will require that anyway.
3. Map partner errors to `IntegrationException` (`NotLinked`, `NotFound`, `Rejected`, `Unavailable`).
4. Register it in `AppContainer.integrations`.

## Candidate partners

None of these offer open self-serve consumer APIs as far as I know; each needs a business or
partner agreement. Verify current terms before planning around any of them.

**Pet pharmacies / prescription fulfilment**

- **Vetsource**: home-delivery pharmacy that runs clinic-branded online stores and auto-ship,
  integrated with major practice software (ezyVet, Cornerstone, etc.). The most natural refill
  partner because refills are approved through the owner's own vet.
- **Covetrus (vRxPro / Prescription Management)**: similar clinic-branded pharmacy, large US clinic base.
- **Chewy Pharmacy**: biggest consumer brand; no public API, so this would be a commercial
  partnership (or deep links to the product page as a fallback).
- Others to look at: PetMed Express (1-800-PetMeds), Mixlab (compounding), Wedgewood Pharmacy.
- Regulatory note (US): prescription refills need vet authorization, and pharmacies shipping pet
  meds should be NABP Vet-VIPPS accredited. The `AWAITING_VET_APPROVAL` order state models this.

**Vet clinics: records, reminders, booking**

Most clinic data lives in practice-management systems (PIMS). Integrate with PIMS vendors or
aggregators rather than clinic by clinic:

- **IDEXX** (Cornerstone, ezyVet, Neo): partner integration programs and APIs.
- **Covetrus Pulse**, **Shepherd**, **Vetspire**, **Digitail**: modern cloud PIMS with APIs/partner programs.
- **Aggregators / data layers**: Sikka (vet PIMS data API), Vetsource Data Services. One deal covers many PIMS.
- **Booking**: Vetstoria, PetDesk (client app + online booking used by many clinics).
- **Owner-side record sharing**: VitusVet lets owners request records from clinics; a possible
  partner or competitor.
- **Fallback that needs no partner**: let owners photograph or upload PDF records and extract
  vaccines/weights with an LLM, then review before saving. This works with every clinic on day one.

**Triage and safety**

- `TriageEngine` is the seam for AI triage. `RuleBasedTriageEngine` runs offline today. An
  LLM-backed engine (e.g. calling the Claude API from your backend) can add free-text
  understanding, but it should always run the rules too and never return a lower urgency than they do.
- ASPCA Animal Poison Control and Pet Poison Helpline are worth linking from the toxin flow
  (they're paid phone services; check partnership options).
