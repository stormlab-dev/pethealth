package com.petpulse.app.data

import android.content.Context
import com.petpulse.core.integrations.LinkedAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which partner accounts are linked. Real providers must keep OAuth tokens in
 * EncryptedSharedPreferences / Android Keystore; this only stores non-secret ids.
 */
class LinkedAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("linked_accounts", Context.MODE_PRIVATE)
    private val _accounts = MutableStateFlow(load())
    val accounts: StateFlow<Map<String, LinkedAccount>> = _accounts.asStateFlow()

    fun get(providerId: String): LinkedAccount? = _accounts.value[providerId]

    fun save(account: LinkedAccount) {
        prefs.edit().putString(account.providerId, "${account.accountId}|${account.displayName}").apply()
        _accounts.value = load()
    }

    fun remove(providerId: String) {
        prefs.edit().remove(providerId).apply()
        _accounts.value = load()
    }

    private fun load(): Map<String, LinkedAccount> = prefs.all.mapNotNull { (k, v) ->
        val parts = (v as? String)?.split('|', limit = 2) ?: return@mapNotNull null
        k to LinkedAccount(k, parts[0], parts.getOrElse(1) { k })
    }.toMap()
}
