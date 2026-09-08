package com.sal7one.common_jni.error

/**
 * Exception thrown when native operations fail.
 */
class NativeException(
    val error: NativeError,
    cause: Throwable? = null
) : RuntimeException(error.toString(), cause) {
    
    val errorCode: Int get() = error.code
    
    companion object {
        /**
         * Create from native error string.
         */
        fun fromNative(errorString: String?): NativeException {
            val error = NativeError.fromNative(errorString) ?: NativeError.Unknown("No error details")
            return NativeException(error)
        }
    }
}

/**
 * Result type for native operations.
 * Use this instead of throwing exceptions for expected failures.
 */
sealed class NativeResult<out T> {
    data class Success<T>(val value: T) : NativeResult<T>()
    data class Failure(val error: NativeError) : NativeResult<Nothing>()
    
    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
    
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw NativeException(error)
    }
    
    fun errorOrNull(): NativeError? = when (this) {
        is Success -> null
        is Failure -> error
    }
    
    inline fun <R> map(transform: (T) -> R): NativeResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }
    
    inline fun <R> flatMap(transform: (T) -> NativeResult<R>): NativeResult<R> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }
    
    inline fun onSuccess(action: (T) -> Unit): NativeResult<T> {
        if (this is Success) action(value)
        return this
    }
    
    inline fun onFailure(action: (NativeError) -> Unit): NativeResult<T> {
        if (this is Failure) action(error)
        return this
    }
    
    companion object {
        fun <T> success(value: T): NativeResult<T> = Success(value)
        fun failure(error: NativeError): NativeResult<Nothing> = Failure(error)
        fun failure(errorString: String?): NativeResult<Nothing> = 
            Failure(NativeError.fromNative(errorString) ?: NativeError.Unknown())
    }
}

/**
 * Extension to convert nullable result with error check to NativeResult.
 */
inline fun <T> runNative(
    getError: () -> String?,
    block: () -> T?
): NativeResult<T> {
    val result = block()
    return if (result != null) {
        NativeResult.Success(result)
    } else {
        val error = getError()
        NativeResult.failure(error)
    }
}

