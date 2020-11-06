package io.spinnaker.spinrel.cli

import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import io.spinnaker.spinrel.GcsBucket

fun RawOption.gcsBucket(): NullableOption<GcsBucket, GcsBucket> {
    return convert { GcsBucket(it) }
}
