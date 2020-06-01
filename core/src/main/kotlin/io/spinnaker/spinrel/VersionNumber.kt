package io.spinnaker.spinrel

import com.google.common.collect.ComparisonChain
import kotlinx.serialization.Serializable

@Serializable
data class VersionNumber(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<VersionNumber> {

    override fun toString(): String {
        return "$major.$minor.$patch"
    }

    override fun compareTo(other: VersionNumber): Int {
        return ComparisonChain.start()
            .compare(this.major, other.major)
            .compare(this.minor, other.minor)
            .compare(this.patch, other.patch)
            .result()
    }

    companion object {

        fun parse(versionStr: String): VersionNumber {
            val components = versionStr.split('.')
            if (components.size != 3) {
                throw IllegalArgumentException("Invalid version number '$versionStr'")
            }
            try {
                val ints = components.map { it.toInt() }
                return VersionNumber(ints[0], ints[1], ints[2])
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Invalid version number '$versionStr'")
            }
        }
    }
}
