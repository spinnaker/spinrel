package io.spinnaker.spinrel.cli.jenkins

import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import com.google.common.jimfs.Jimfs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import io.mockk.verifyAll
import io.spinnaker.spinrel.ArtifactSources
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.ContainerRegistry
import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.GcsBucket
import io.spinnaker.spinrel.GoogleCloudStorage
import io.spinnaker.spinrel.HalconfigProfilePublisher
import io.spinnaker.spinrel.ServiceInfo
import io.spinnaker.spinrel.SpinnakerServiceInfo
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import strikt.api.expectThat
import strikt.assertions.isEqualTo

@ExtendWith(MockKExtension::class)
class FlowBuildFinisherTest {

    private lateinit var flowBuildFinisher: FlowBuildFinisher

    private lateinit var filesystem: FileSystem
    private lateinit var repositoriesDir: Path

    private lateinit var cloudStorage: GoogleCloudStorage

    @MockK(relaxUnitFun = true)
    private lateinit var containerRegistry: ContainerRegistry

    @MockK(relaxUnitFun = true)
    private lateinit var halconfigProfilePublisher: HalconfigProfilePublisher

    private lateinit var containerSuffixes: MutableSet<String>
    private lateinit var serviceRegistry: MutableSet<SpinnakerServiceInfo>

    @BeforeEach
    fun setUp() {

        containerSuffixes = mutableSetOf()
        serviceRegistry = mutableSetOf()

        filesystem = Jimfs.newFileSystem("spinfs")
        repositoriesDir = filesystem.getPath("/path/to/repositories").also { Files.createDirectories(it) }

        val storage = LocalStorageHelper.getOptions().service
        cloudStorage = GoogleCloudStorage(storage, GcsBucket("gcsBucket"))
        flowBuildFinisher = FlowBuildFinisher(
            cloudStorage,
            containerRegistry,
            object : SpinnakerServiceRegistry {
                override val byServiceName: Map<String, SpinnakerServiceInfo>
                    get() = serviceRegistry.associateBy { it.serviceName }
            },
            object : ContainerTagGenerator {
                override fun generateTagsForVersion(version: String) =
                    containerSuffixes.map { "$version$it" }.toSet()
            },
            halconfigProfilePublisher,
            repositoriesDir
        )
    }

    @AfterEach
    fun tearDown() {
        filesystem.close()
    }

    @Test
    fun `calls halconfigProfilePublisher`() {
        val inputBom = createMinimalBomWithVersion("1.2.3")
        val bomPath = inputBom.write()
        flowBuildFinisher.finishBuild(bomPath)

        verifyAll {
            halconfigProfilePublisher.publish(repositoriesDir, inputBom)
        }
    }

    @Test
    fun `publishVersion writes BOM to GCS`() {
        val inputBom = createMinimalBomWithVersion("1.2.3")
        val bomPath = inputBom.write()
        flowBuildFinisher.finishBuild(bomPath)

        val storedBom = Bom.readFromString(cloudStorage.readUtf8String("bom/1.2.3.yml"))

        expectThat(storedBom).isEqualTo(inputBom.copy(version = "1.2.3"))
    }

    @Test
    fun `publishVersion writes BOM to GCS with multiple spinnaker versions`() {
        val inputBom = createMinimalBomWithVersion("999")
        val bomPath = inputBom.write()
        flowBuildFinisher.finishBuild(bomPath, additionalVersions = setOf("1.9", "master-latest-validated"))

        val bom999 = Bom.readFromString(cloudStorage.readUtf8String("bom/999.yml"))
        expectThat(bom999).isEqualTo(inputBom.copy(version = "999"))

        val bom19 = Bom.readFromString(cloudStorage.readUtf8String("bom/1.9.yml"))
        expectThat(bom19).isEqualTo(inputBom.copy(version = "1.9"))

        val bomMasterLatestValidated =
            Bom.readFromString(cloudStorage.readUtf8String("bom/master-latest-validated.yml"))
        expectThat(bomMasterLatestValidated).isEqualTo(inputBom.copy(version = "master-latest-validated"))
    }

    @Test
    fun `tags containers in GCR`() {
        val bomPath = createMinimalBomWithVersion("9.8.7").withServiceVersion("front50", "1.3.22").write()
        containerSuffixes.add("")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        flowBuildFinisher.finishBuild(bomPath)

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-9.8.7")
        }
    }

    @Test
    fun `tags containers in GCR with multiple suffixes`() {
        val bomPath = createMinimalBomWithVersion("9.8.7").withServiceVersion("front50", "1.3.22").write()
        containerSuffixes.addAll(setOf("", "-foo"))
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        flowBuildFinisher.finishBuild(bomPath)

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-9.8.7")
            containerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-9.8.7-foo")
        }
    }

    @Test
    fun `tags containers in GCR with multiple services`() {
        val bomPath = createMinimalBomWithVersion("9.8.7")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        containerSuffixes.add("")
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        flowBuildFinisher.finishBuild(bomPath)

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-9.8.7")
            containerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-9.8.7")
        }
    }

    @Test
    fun `tags containers in GCR with multiple versions`() {
        val bomPath = createMinimalBomWithVersion("123").withServiceVersion("front50", "1.3.22").write()
        containerSuffixes.add("")
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50")))

        flowBuildFinisher.finishBuild(bomPath, additionalVersions = setOf("456"))

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-123")
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-456")
        }
    }

    @Test
    fun `tags containers in GCR with multiple services, suffixes, and versions`() {
        val bomPath = createMinimalBomWithVersion("123")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        containerSuffixes.addAll(setOf("", "-foo"))
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        flowBuildFinisher.finishBuild(bomPath, additionalVersions = setOf("456"))

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-123")
            containerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-123-foo")
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-456")
            containerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-456-foo")
            containerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-123")
            containerRegistry.addTag("deck", existingTag = "9.8-foo", newTag = "spinnaker-123-foo")
            containerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-456")
            containerRegistry.addTag("deck", existingTag = "9.8-foo", newTag = "spinnaker-456-foo")
        }
    }

    @Test
    fun `doesn't tag container missing from serviceRegistry`() {
        val bomPath = createMinimalBomWithVersion("9.8.7")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        containerSuffixes.add("")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        flowBuildFinisher.finishBuild(bomPath)

        verify(exactly = 0) {
            containerRegistry.addTag("deck", any(), any())
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

    private fun Bom.withServiceVersion(service: String, version: String): Bom {
        return this.copy(services = services.plus(service to ServiceInfo(version = version)))
    }

    private fun Bom.write(): Path {
        return filesystem.getPath("/path/to/bom")
            .let { Files.createDirectories(it) }
            .resolve("mybom.yaml")
            .let { Files.write(it, toYaml().toByteArray(Charsets.UTF_8)) }
    }
}
