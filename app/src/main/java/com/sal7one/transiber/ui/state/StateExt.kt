package com.sal7one.transiber.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * The one empty-list fold, shared by every purely list-backed shelf (presets,
 * job queue, work gallery — three ports repeated it verbatim): an empty list
 * is first-class [ScreenState.Empty], anything else is [ScreenState.Ready].
 */
fun <T> listScreenState(list: List<T>): ScreenState<List<T>> =
    if (list.isEmpty()) ScreenState.Empty else ScreenState.Ready(list)

/**
 * The one sharing convention: WhileSubscribed(5s) so screens stop work when
 * nobody watches but survive a rotation (config change < 5s gap), with an
 * explicit initial ScreenState — Loading unless the caller knows better.
 */
fun <T> kotlinx.coroutines.flow.Flow<ScreenState<T>>.stateInScreen(
    scope: CoroutineScope,
    initial: ScreenState<T> = ScreenState.Loading,
): StateFlow<ScreenState<T>> = stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
