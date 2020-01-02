package org.spinnaker.spinrel

import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert

fun RawOption.gcrProject(): NullableOption<GcrProject, GcrProject> {
    return convert { GcrProject(it) }
}

fun RawOption.gcsBucket(): NullableOption<GcsBucket, GcsBucket> {
    return convert { GcsBucket(it) }
}
