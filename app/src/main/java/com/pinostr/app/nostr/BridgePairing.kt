package com.pinostr.app.nostr

/**
 * Bridge pairing data — from the bridge's HTTP /pairing endpoint.
 */
data class BridgePairing(
    val pubkey: String,
    val relays: List<String>,
    val pairingCode: String,
    val name: String? = null,
)
