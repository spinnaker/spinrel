package io.spinnaker.spinrel

import com.charleskorn.kaml.Yaml
import java.time.Instant
import kotlinx.serialization.Decoder
import kotlinx.serialization.Encoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PrimitiveDescriptor
import kotlinx.serialization.PrimitiveKind
import kotlinx.serialization.SerialDescriptor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VersionsFile(
    val latestHalyard: String,
    val latestSpinnaker: String,
    @SerialName("versions")
    val releases: List<ReleaseInfo> = listOf(),
    val illegalVersions: List<IllegalVersion> = listOf()
) {

    fun toYaml(): String {
        return Yaml.default.stringify(serializer(), this)
    }

    companion object {
        fun readFromString(string: String): VersionsFile {
            return Yaml.default.parse(serializer(), string)
        }
    }
}

@Serializable
data class ReleaseInfo(
    val version: String,
    val alias: String,
    @SerialName("changelog")
    val changelogUrl: String,
    val minimumHalyardVersion: String,
    @Serializable(with = InstantSerializer::class)
    val lastUpdate: Instant
)

@Serializable
data class IllegalVersion(
    val version: String,
    val reason: String
)

private object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveDescriptor("EpochMillisInstant", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
}
