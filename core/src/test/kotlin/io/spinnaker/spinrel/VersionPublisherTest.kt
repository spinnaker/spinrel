package io.spinnaker.spinrel

import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import io.mockk.verifyAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import strikt.api.expectThat
import strikt.assertions.isEqualTo

@ExtendWith(MockKExtension::class)
class VersionPublisherTest {

    private lateinit var versionPublisher: VersionPublisher

    private lateinit var bomStorage: BomStorage

    @MockK(relaxUnitFun = true)
    private lateinit var dockerRegistry: DockerRegistry

    private lateinit var containerSuffixes: MutableSet<String>
    private lateinit var serviceRegistry: MutableSet<SpinnakerServiceInfo>

    @BeforeEach
    fun setUp() {

        containerSuffixes = mutableSetOf()
        serviceRegistry = mutableSetOf()

        val storage = LocalStorageHelper.getOptions().service
        bomStorage = BomStorage(GoogleCloudStorage(storage, GcsBucket("gcsBucket")))
        versionPublisher = VersionPublisher(
            bomStorage,
            DockerRegistryFactory { dockerRegistry },
            object : SpinnakerServiceRegistry {
                override val byServiceName: Map<String, SpinnakerServiceInfo>
                    get() = serviceRegistry.associateBy { it.serviceName }
            },
            object : ContainerTagGenerator {
                override fun generateTagsForVersion(version: String) =
                    containerSuffixes.map { "$version$it" }.toSet()
            }
        )
    }

    @Test
    fun `publishVersion writes BOM to GCS`() {
        val bom = createMinimalBomWithVersion("1.2.3")
        versionPublisher.publish(bom, "4.5.6")

        val storedBom = bomStorage.get("4.5.6")

        expectThat(storedBom).isEqualTo(bom.copy(version = "4.5.6"))
    }

    @Test
    fun `tags containers in GCR`() {
        val bom = createMinimalBomWithVersion("9.8.7").withServiceVersion("front50", "1.3.22")
        containerSuffixes.add("")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        versionPublisher.publish(bom, "1.2.3")

        verifyAll {
            dockerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-1.2.3")
        }
    }

    @Test
    fun `tags containers in GCR with multiple suffixes`() {
        val bom = createMinimalBomWithVersion("9.8.7").withServiceVersion("front50", "1.3.22")
        containerSuffixes.addAll(setOf("", "-foo"))
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        versionPublisher.publish(bom, "1.2.3")

        verifyAll {
            dockerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-1.2.3")
            dockerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-1.2.3-foo")
        }
    }

    @Test
    fun `tags containers in GCR with multiple services`() {
        val bom = createMinimalBomWithVersion("9.8.7")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
        containerSuffixes.add("")
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        versionPublisher.publish(bom, "1.2.3")

        verifyAll {
            dockerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-1.2.3")
            dockerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-1.2.3")
        }
    }

    @Test
    fun `doesn't tag container missing from serviceRegistry`() {
        val bom = createMinimalBomWithVersion("9.8.7")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
        containerSuffixes.add("")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        versionPublisher.publish(bom, "1.2.3")

        verify(exactly = 0) {
            dockerRegistry.addTag("deck", any(), any())
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
}
