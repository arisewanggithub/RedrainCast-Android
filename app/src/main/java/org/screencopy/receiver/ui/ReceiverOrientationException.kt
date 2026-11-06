package org.screencopy.receiver.ui

import org.screencopy.receiver.media.PlaybackException

class ReceiverOrientationException(cause: Exception? = null) :
    PlaybackException("Receiver presentation orientation could not be requested", "UNSUPPORTED_CONFIG") {
    init { if (cause != null) initCause(cause) }
}
