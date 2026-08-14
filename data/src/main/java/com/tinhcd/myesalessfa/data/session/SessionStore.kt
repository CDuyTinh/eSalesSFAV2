package com.tinhcd.myesalessfa.data.session

import com.tinhcd.myesalessfa.domain.model.Salesperson
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the signed-in rep so writes can stamp salesperson_id and branch_id
 * without every caller threading them through. RLS re-checks both server side,
 * so this is a convenience, not the security boundary.
 */
@Singleton
class SessionStore @Inject constructor() {
    val current = MutableStateFlow<Salesperson?>(null)
}
