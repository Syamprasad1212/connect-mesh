package com.connectmesh.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NetworkEventLogger {
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val events = mutableListOf<String>()
    private val _eventsFlow = MutableStateFlow<List<String>>(emptyList())
    val eventsFlow: StateFlow<List<String>> = _eventsFlow.asStateFlow()

    @Synchronized
    fun log(event: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "$timestamp $event"
        events.add(0, entry)
        if (events.size > 200) {
            events.removeAt(events.size - 1)
        }
        _eventsFlow.value = events.toList()
    }

    @Synchronized
    fun clear() {
        events.clear()
        _eventsFlow.value = emptyList()
    }
}
