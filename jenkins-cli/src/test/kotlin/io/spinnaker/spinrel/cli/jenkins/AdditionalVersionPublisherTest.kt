package io.spinnaker.spinrel.cli.jenkins

import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verifyAll
import io.spinnaker.spinrel.ArtifactSources
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.BomStorage
import io.spinnaker.spinrel.GcsBucket
import io.spinnaker.spinrel.GoogleCloudStorage
import io.spinnaker.spinrel.VersionPublisher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class AdditionalVersionPublisherTest {

    private lateinit var additionalVersionPublisher: AdditionalVersionPublisher

    private lateinit var bomStorage: BomStorage

    @MockK(relaxUnitFun = true)
    private lateinit var versionPublisher: VersionPublisher

    @BeforeEach
    fun setUp() {

        val storage = LocalStorageHelper.getOptions().service
        bomStorage = BomStorage(GoogleCloudStorage(storage, GcsBucket("gcsBucket")))

        additionalVersionPublisher = AdditionalVersionPublisher(bomStorage, versionPublisher)
    }

    @Test
    fun `calls publishers`() {
        val inputBom = createMinimalBomWithVersion("1.2.3")
        bomStorage.put(inputBom)
        additionalVersionPublisher.publish("1.2.3", "999.1")

        verifyAll {
            versionPublisher.publish(inputBom, "999.1")
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
}
