package com.tinhcd.myesalessfa.domain.model

/**
 * One outlet as the detail screen shows it.
 *
 * Deliberately not [Customer] with more fields on it. [Customer] travels inside
 * every route stop and every in-call screen, where a credit limit and a month's
 * revenue would be dead weight fetched dozens of times a day. This is read once,
 * when a rep opens one shop's card.
 *
 * The segment fields are names, not ids: the screen shows "Tạp hóa", and
 * resolving an id to that on the device would mean shipping three reference
 * tables to render three lines of text.
 */
data class CustomerInfo(
    val customerId: String,
    val code: String,
    val name: String,
    val phone: String?,
    val address: String?,
    val avatarUrl: String?,
    /**
     * Where the outlet is, when anyone has recorded it. Null on one a rep
     * registered in the field and head office has not geocoded, which is why the
     * header hides its map button rather than opening a map on nothing.
     */
    val lat: Double?,
    val lng: Double?,
    /** Who to ask for at the counter. Null is normal — most shops are the owner. */
    val contactName: String?,
    val channelName: String?,
    val className: String?,
    val shopTypeName: String?,
    /**
     * Credit head office allows, in dong.
     *
     * Null and zero say different things and the screen must not merge them:
     * null is "no limit has been set", zero is "cash only". A rep told the limit
     * is zero when nobody decided that would stop selling on terms the shop is
     * entitled to.
     */
    val creditLimit: Long?,
    /**
     * Bought this calendar month, cancelled orders excluded — the same rule the
     * dashboard totals by, so the customer figures add up to the one the rep
     * already trusts.
     */
    val monthRevenue: Long,
)
