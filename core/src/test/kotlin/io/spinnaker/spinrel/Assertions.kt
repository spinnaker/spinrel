package io.spinnaker.spinrel

import com.google.api.gax.paging.Page
import strikt.api.Assertion
import java.nio.channels.Channel

internal fun <T : Channel> Assertion.Builder<T>.isClosed() =
    assert("is closed") {
        if (it.isOpen) fail() else pass()
    }

internal fun <T : Page<E>, E> Assertion.Builder<T>.isEmpty() =
    assert("is empty") {
        if (it.hasNextPage() || it.values.iterator().hasNext()) fail() else pass()
    }
