package com.tinhcd.myesalessfa.domain.model

/** A coded reference row as a form needs it: something to show, something to send. */
data class NamedRef(
    val id: String,
    val code: String,
    val name: String,
)

/**
 * The lists the registration form is filled in from.
 *
 * Geography arrives a level at a time rather than whole, because a country's
 * wards run to five figures and the form only ever needs the branch of the tree
 * the rep is standing in.
 */
data class CustomerOptions(
    val classes: List<NamedRef> = emptyList(),
    val channels: List<NamedRef> = emptyList(),
    val shopTypes: List<NamedRef> = emptyList(),
    val provinces: List<NamedRef> = emptyList(),
)

/**
 * What the rep has filled in so far.
 *
 * Everything but the name and the address is optional, and deliberately so. A rep
 * standing in a shop doorway with a queue behind them should be able to record
 * the outlet and move on; head office can chase the segment later. The two that
 * are required are the two nobody can reconstruct afterwards — an outlet with no
 * name and no address cannot be found again to ask.
 */
data class CustomerDraft(
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val provinceId: String? = null,
    val districtId: String? = null,
    val wardId: String? = null,
    val classId: String? = null,
    val channelId: String? = null,
    val shopTypeId: String? = null,
    val note: String = "",
    /** Where the rep was standing when they filled this in. */
    val point: GeoPoint? = null,
) {
    val nameError: String?
        get() = if (name.isBlank()) "Nhập tên cửa hàng" else null

    val addressError: String?
        get() = if (address.isBlank()) "Nhập địa chỉ" else null

    /**
     * Position is not required.
     *
     * A shop inside a market hall or under a flyover can be somewhere no fix
     * arrives, and refusing the registration there would lose the outlet
     * entirely. The consequence is understood: the outlet has no coordinates
     * until someone adds them, so a check-in against it will be waved through by
     * the same rule that waves through any ungeocoded customer.
     */
    val canSubmit: Boolean
        get() = nameError == null && addressError == null
}

/** What came back: the outlet exists now, with a code, and is awaiting a decision. */
data class RegisteredCustomer(
    val id: String,
    val code: String,
    val name: String,
)
