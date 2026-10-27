package com.shilapi.xcertplay

/** Latest state wins even if old event commands are still queued. */
internal class NightModeSyncState {
    data class Request(val owner: Any, val night: Boolean, val generation: Long)
    private var owner: Any? = null
    private var night = false
    private var generation = 0L
    private var inFlight = false
    private var attempts = 0
    private var due = 0L
    private var confirmed = false
    @Synchronized fun observe(session: Any?, value: Boolean, now: Long, force: Boolean = false) {
        if (session === owner && value == night && !force) return
        owner = session; night = value; generation++; inFlight = false; attempts = 0; due = now; confirmed = false
    }
    @Synchronized fun next(now: Long): Request? {
        val session = owner ?: return null
        if (inFlight || now < due || attempts >= (if (confirmed) 3 else 5)) return null
        inFlight = true
        return Request(session, night, generation)
    }
    @Synchronized fun current(request: Request) = owner === request.owner && generation == request.generation
    @Synchronized fun complete(request: Request, sent: Boolean, now: Long) {
        if (!current(request)) return
        inFlight = false; attempts++; confirmed = sent
        due = now + if (!sent) 2000 else if (attempts == 1) 1000 else 3000
    }
}
