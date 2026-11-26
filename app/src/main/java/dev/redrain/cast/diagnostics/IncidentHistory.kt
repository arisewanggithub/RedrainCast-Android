package dev.redrain.cast.diagnostics

/** Legacy v1 vocabulary and shared sampling interval; no capture or sampler state is retained. */
internal object IncidentHistory {
    const val HISTORY_SECONDS = 20
    const val EVENT_CAPACITY = 64
    const val SAMPLE_NS = 1_000_000_000L

    fun terminalFailure(kind: Int, stage: Int, details: Int, firstDetail: Long? = null): Boolean =
        stage == DiagnosticStage.FAILED.ordinal &&
            (kind == DiagnosticKind.SESSION.ordinal + 1 && details == 0 ||
                kind == DiagnosticKind.RECOVERY.ordinal + 1 &&
                (details == 1 && (firstDetail == DiagnosticPoint.UNSPECIFIED.ordinal.toLong() ||
                    firstDetail == DiagnosticPoint.RECOVERY.ordinal.toLong()) || details == 8 || details == 11))
}
