package io.spinnaker.spinrel

import com.charleskorn.kaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

@Serializable
data class Bom(
    val artifactSources: ArtifactSources,
    val dependencies: Map<String, DependencyInfo>,
    val services: Map<String, ServiceInfo>,
    val timestamp: String,
    val version: String
) {

    fun toYaml(): String {
        return Yaml.default.stringify(serializer(), this)
    }

    companion object {
        fun readFromFile(bomPath: Path): Bom {
            logger.info { "Loading BOM from $bomPath" }
            val fileContents = String(Files.readAllBytes(bomPath), Charsets.UTF_8) // Java 11: Files.readString()
            return readFromString(fileContents)
        }

        fun readFromString(string: String): Bom {
            return Yaml.default.parse(serializer(), string)
        }
    }
}

@Serializable
data class ArtifactSources(
    val debianRepository: String,
    val dockerRegistry: String,
    val gitPrefix: String,
    val googleImageProject: String
)

@Serializable
data class DependencyInfo(val version: String)

@Serializable
data class ServiceInfo(val commit: String? = null, val version: String? = null)
