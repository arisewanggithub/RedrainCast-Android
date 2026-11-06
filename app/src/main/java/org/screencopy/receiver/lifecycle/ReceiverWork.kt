package org.screencopy.receiver.lifecycle

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal object ReceiverWork {
    // Activity instances must share listener teardown/startup ordering.
    val executor: ExecutorService = Executors.newSingleThreadExecutor { action ->
        Thread(action, "ScreenCopy-lifecycle").apply { isDaemon = true }
    }
}
