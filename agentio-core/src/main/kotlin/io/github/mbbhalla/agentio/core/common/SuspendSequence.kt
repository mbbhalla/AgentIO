package io.github.mbbhalla.agentio.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

fun <T> generateFlow(
    seed: T,
    nextFunction: suspend (T) -> T?,
): Flow<T> = flow {
    var current: T = seed
    do {
        emit(current)
        val next = nextFunction(current) ?: break
        current = next
    } while (true)
}

suspend fun <T> generateList(
    seed: T,
    nextFunction: suspend (T) -> T?,
): List<T> = generateFlow(seed, nextFunction).toList()
