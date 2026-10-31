package com.tinhcd.myesalessfa.domain.repository

import com.tinhcd.myesalessfa.domain.DataResult
import com.tinhcd.myesalessfa.domain.model.TracePoint

/**
 * The working day's GPS trace (legacy ServiceBackground). Fixes are queued on
 * the phone and sent in batches, so a dead zone costs a delay, not the points.
 */
interface LocationTraceRepository {
    /** Seconds between fixes, from the server's settings. */
    suspend fun intervalSeconds(): Int

    suspend fun record(point: TracePoint)

    /**
     * Sends what is queued. A batch the server answered is removed whether it
     * kept every point or not: one it dropped will never be accepted later.
     * @return how many points are still waiting.
     */
    suspend fun flush(): DataResult<Int>
}
