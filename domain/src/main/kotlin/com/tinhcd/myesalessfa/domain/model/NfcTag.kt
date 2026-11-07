package com.tinhcd.myesalessfa.domain.model

/** The customer an NFC tag is fixed in, as far as the rep's branch can see. */
data class NfcTagOwner(
    val customerId: String,
    val code: String,
    val name: String,
)

/** What binding a tag to the customer being visited came to. */
sealed interface NfcRegistration {
    data object Registered : NfcRegistration

    /** It was already this customer's. */
    data object Unchanged : NfcRegistration

    /** Bound to another customer; tags are never moved, a supervisor clears them. */
    data class Taken(val customerName: String?) : NfcRegistration

    /** The rep is not checked in at this customer today. */
    data object NoVisit : NfcRegistration

    data object Invalid : NfcRegistration
}
