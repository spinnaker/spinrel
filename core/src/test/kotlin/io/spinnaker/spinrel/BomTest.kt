package io.spinnaker.spinrel

import com.google.common.jimfs.Jimfs
import org.junit.jupiter.api.Test
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import java.nio.file.Files

class BomTest {

    @Test
    fun `all fields parsed`() {
        val bomYaml =
            """
            artifactSources:
              debianRepository: https://dl.bintray.com/spinnaker-releases/debians
              dockerRegistry: gcr.io/spinnaker-marketplace
              gitPrefix: https://github.com/spinnaker
              googleImageProject: marketplace-spinnaker-release
            dependencies:
              consul:
                version: 0.7.5
              redis:
                version: 2:2.8.4-2
            services:
              clouddriver:
                commit: 8004330e7cdc090a921e26a1fe1ac904c4923dc9
                version: 6.5.0-20191224172815
              deck:
                commit: 81386a3c55608dd1c31a30eff1d46f7c39627f30
                version: 2.14.0-20191220202816
              defaultArtifact: {}
            timestamp: '2019-12-24 22:28:39'
            version: master-latest-unvalidated
            """.trimIndent()

        val bom = Bom.readFromString(bomYaml)

        expect {
            that(bom.artifactSources.debianRepository).isEqualTo("https://dl.bintray.com/spinnaker-releases/debians")
            that(bom.artifactSources.dockerRegistry).isEqualTo("gcr.io/spinnaker-marketplace")
            that(bom.artifactSources.gitPrefix).isEqualTo("https://github.com/spinnaker")
            that(bom.artifactSources.googleImageProject).isEqualTo("marketplace-spinnaker-release")
            that(bom.dependencies).isEqualTo(
                mapOf(
                    "consul" to DependencyInfo("0.7.5"),
                    "redis" to DependencyInfo("2:2.8.4-2")
                )
            )
            that(bom.services).isEqualTo(
                mapOf(
                    "clouddriver" to ServiceInfo(
                        "8004330e7cdc090a921e26a1fe1ac904c4923dc9",
                        "6.5.0-20191224172815"
                    ),
                    "deck" to ServiceInfo(
                        "81386a3c55608dd1c31a30eff1d46f7c39627f30",
                        "2.14.0-20191220202816"
                    ),
                    "defaultArtifact" to ServiceInfo()
                )
            )
            that(bom.timestamp).isEqualTo("2019-12-24 22:28:39")
            that(bom.version).isEqualTo("master-latest-unvalidated")
        }
    }

    @Test
    fun `minimal bom is parsed`() {
        val bomYaml =
            """
            artifactSources:
              debianRepository: requiredDebianRepository
              dockerRegistry: requiredDockerRegistry
              gitPrefix: requiredGitPrefix
              googleImageProject: requiredGoogleImageProject
            dependencies: {}
            services: {}
            timestamp: requiredTimestamp
            version: requiredVersion
            """.trimIndent()

        val bom = Bom.readFromString(bomYaml)

        expect {
            that(bom.artifactSources.debianRepository).isEqualTo("requiredDebianRepository")
            that(bom.artifactSources.dockerRegistry).isEqualTo("requiredDockerRegistry")
            that(bom.artifactSources.gitPrefix).isEqualTo("requiredGitPrefix")
            that(bom.artifactSources.googleImageProject).isEqualTo("requiredGoogleImageProject")
            that(bom.dependencies).isEmpty()
            that(bom.services).isEmpty()
            that(bom.timestamp).isEqualTo("requiredTimestamp")
            that(bom.version).isEqualTo("requiredVersion")
        }
    }

    @Test
    fun `read bom from file`() {
        val bomYaml =
            """
            artifactSources:
              debianRepository: requiredDebianRepository
              dockerRegistry: requiredDockerRegistry
              gitPrefix: requiredGitPrefix
              googleImageProject: requiredGoogleImageProject
            dependencies: {}
            services: {}
            timestamp: requiredTimestamp
            version: requiredVersion
            """.trimIndent()

        val bom = Jimfs.newFileSystem("spinfs").use { fs ->
            fs.getPath("/path/to/bom")
                .let { Files.createDirectories(it) }
                .resolve("mybom.yaml")
                .let { Files.write(it, bomYaml.toByteArray(Charsets.UTF_8)) }
                .let { Bom.readFromFile(it) }
        }

        expect {
            that(bom.artifactSources.debianRepository).isEqualTo("requiredDebianRepository")
            that(bom.artifactSources.dockerRegistry).isEqualTo("requiredDockerRegistry")
            that(bom.artifactSources.gitPrefix).isEqualTo("requiredGitPrefix")
            that(bom.artifactSources.googleImageProject).isEqualTo("requiredGoogleImageProject")
            that(bom.dependencies).isEmpty()
            that(bom.services).isEmpty()
            that(bom.timestamp).isEqualTo("requiredTimestamp")
            that(bom.version).isEqualTo("requiredVersion")
        }
    }

    @Test
    fun `round trip bom`() {
        val inputBom = Bom(
            artifactSources = ArtifactSources(
                "debianRepository",
                "dockerRegistry",
                "gitPrefix",
                "googleImageProject"
            ),
            dependencies = mapOf(
                "consul" to DependencyInfo("1.2.3"),
                "redis" to DependencyInfo("4.5.6")
            ),
            services = mapOf(
                "clouddriver" to ServiceInfo(
                    commit = "abcdef",
                    version = "999"
                ),
                "deck" to ServiceInfo(commit = "123456", version = "777")
            ),
            timestamp = "now",
            version = "master-20200110155013"
        )

        val outputBom = Bom.readFromString(inputBom.toYaml())

        expectThat(outputBom).isEqualTo(inputBom)
    }
}
