package com.tinhcd.myesalessfa.domain.usecase

import com.tinhcd.myesalessfa.domain.AppError
import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.Salesperson
import com.tinhcd.myesalessfa.domain.repository.AuthRepository
import javax.inject.Inject

class SignInUseCase @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(username: String, password: String): DataResult<Salesperson> {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return DataResult.Failure(AppError.Rule("login_empty_fields"))
        }
        return authRepository.signIn(user, password)
    }
}
