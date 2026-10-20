package com.kuankuan.daigan.data.model

/**
 * How the worker signs in to the customer's game account.
 *
 * Verification codes are never persisted; only the chosen method is stored.
 */
enum class LoginMethod {
    PASSWORD,
    VERIFICATION_CODE,
}
