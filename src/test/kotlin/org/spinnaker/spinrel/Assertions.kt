package org.spinnaker.spinrel

import com.google.api.gax.paging.Page
import java.nio.channels.Channel
import strikt.api.Assertion

internal fun <T : Page<E>, E> Assertion.Builder<T>.isEmpty() =
    assert("is empty") {
        if (it.hasNextPage() || it.values.iterator().hasNext()) fail() else pass()
    }

internal fun <T : Channel> Assertion.Builder<T>.isClosed() =
    assert("is closed") {
        if (it.isOpen) fail() else pass()
    }
