package com.kuankuan.daigan.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Production startup closes this barrier before any screen or scheduled worker runs. */
object ApplicationReadiness {
    sealed interface State {
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    // Test applications do not execute RecordApplication; production closes it in onCreate.
    private val mutable = MutableStateFlow<State>(State.Ready)
    val state = mutable.asStateFlow()

    fun begin() { mutable.value = State.Loading }
    fun ready() { mutable.value = State.Ready }
    fun fail() { mutable.value = State.Failed("资料恢复尚未完成，当前资料已暂时锁定。请重试；原恢复文件会保留。") }

    suspend fun awaitReady() {
        check(mutable.first { it !is State.Loading } is State.Ready) { "资料恢复尚未完成" }
    }
}
