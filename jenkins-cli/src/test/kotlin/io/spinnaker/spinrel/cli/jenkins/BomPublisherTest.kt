package io.spinnaker.spinrel.cli.jenkins

import com.google.common.jimfs.Jimfs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verifyAll
import io.spinnaker.spinrel.ArtifactSources
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.VersionPublisher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path

@ExtendWith(MockKExtension::class)
class BomPublisherTest {

    private lateinit var bomPublisher: BomPublisher

    private lateinit var filesystem: FileSystem

    @MockK(relaxUnitFun = true)
    private lateinit var versionPublisher: VersionPublisher

    @BeforeEach
    fun setUp() {

        filesystem = Jimfs.newFileSystem("spinfs")

        bomPublisher = BomPublisher(versionPublisher)
    }

    @AfterEach
    fun tearDown() {
        filesystem.close()
    }

    @Test
    fun `calls publishers`() {
        val inputBom = createMinimalBomWithVersion("1.2.3")
        val bomPath = inputBom.write()
        bomPublisher.publish(bomPath)

        verifyAll {
            versionPublisher.publish(inputBom, "1.2.3")
        }
    }

    @Test
    fun `calls publishers with additional version`() {
        val inputBom = createMinimalBomWithVersion("1.2.3")
        val bomPath = inputBom.write()
        bomPublisher.publish(bomPath, additionalVersions = setOf("10111"))

        verifyAll {
            versionPublisher.publish(inputBom, "1.2.3")
            versionPublisher.publish(inputBom, "10111")
        }
    }

    private fun createMinimalBomWithVersion(version: String): Bom {
        return Bom(
            artifactSources = ArtifactSources("debianRepository", "dockerRegistry", "gitPrefix", "googleImageProject"),
            dependencies = mapOf(),
            services = mapOf(),
            timestamp = "timestamp",
            version = version
        )
    }

    private fun Bom.write(): Path {
        return filesystem.getPath("/path/to/bom")
            .let { Files.createDirectories(it) }
            .resolve("mybom.yaml")
            .let { Files.write(it, toYaml().toByteArray(Charsets.UTF_8)) }
    }
}
