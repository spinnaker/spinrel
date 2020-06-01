package io.spinnaker.spinrel

import java.time.Instant
import org.junit.jupiter.api.Test
import strikt.api.expect
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class VersionsFileTest {

    @Test
    fun `all fields parsed`() {
        val versionsYaml = """
            latestHalyard: 1.35.0
            latestSpinnaker: 1.19.6
            versions:
            - version: 1.17.10
              alias: LlamaLlama
              changelog: https://gist.github.com/spinnaker-release/d020714e9190763f27e35701e14c6bc1
              minimumHalyardVersion: '1.17'
              lastUpdate: 1585939940343
            - version: 1.18.9
              alias: Longmire
              changelog: https://gist.github.com/spinnaker-release/306d7e241272980642e918f64ed91fe3
              minimumHalyardVersion: '1.29'
              lastUpdate: 1586973668800
            - version: 1.19.6
              alias: Gilmore Girls A Year in the Life
              changelog: https://gist.github.com/spinnaker-release/cc4410d674679c5765246a40f28e3cad
              minimumHalyardVersion: '1.32'
              lastUpdate: 1587657219292
            illegalVersions:
            - version: 1.2.0
              reason: Broken apache config makes the UI unreachable
            - version: 1.4.0
              reason: UI does not load
        """.trimIndent()

        val versions = VersionsFile.readFromString(versionsYaml)

        expect {
            that(versions.latestHalyard).isEqualTo("1.35.0")
            that(versions.latestSpinnaker).isEqualTo("1.19.6")
            that(versions.releases).isEqualTo(
                listOf(
                    ReleaseInfo(
                        "1.17.10",
                        "LlamaLlama",
                        "https://gist.github.com/spinnaker-release/d020714e9190763f27e35701e14c6bc1",
                        "1.17",
                        Instant.ofEpochMilli(1585939940343)),
                    ReleaseInfo(
                        "1.18.9",
                        "Longmire",
                        "https://gist.github.com/spinnaker-release/306d7e241272980642e918f64ed91fe3",
                        "1.29",
                        Instant.ofEpochMilli(1586973668800)),
                    ReleaseInfo(
                        "1.19.6",
                        "Gilmore Girls A Year in the Life",
                        "https://gist.github.com/spinnaker-release/cc4410d674679c5765246a40f28e3cad",
                        "1.32",
                        Instant.ofEpochMilli(1587657219292))
                )
            )
            that(versions.illegalVersions).isEqualTo(
                listOf(
                    IllegalVersion("1.2.0", "Broken apache config makes the UI unreachable"),
                    IllegalVersion("1.4.0", "UI does not load")
                )
            )
        }
    }

    @Test
    fun `round trip bom`() {
        val input = VersionsFile(
            latestHalyard = "1.35.0",
            latestSpinnaker = "1.19.6",
            releases = listOf(
                ReleaseInfo(
                    "1.17.10",
                    "LlamaLlama",
                    "https://gist.github.com/spinnaker-release/d020714e9190763f27e35701e14c6bc1",
                    "1.17",
                    Instant.ofEpochMilli(1585939940343))),
            illegalVersions = listOf(
                IllegalVersion("1.2.0", "Broken apache config makes the UI unreachable"),
                IllegalVersion("1.4.0", "UI does not load")
            )
        )

        val output = VersionsFile.readFromString(input.toYaml())

        expectThat(output).isEqualTo(input)
    }
}
