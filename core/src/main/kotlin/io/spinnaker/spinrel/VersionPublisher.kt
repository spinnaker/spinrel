package io.spinnaker.spinrel

import java.nio.ByteBuffer
import javax.inject.Inject
import mu.KotlinLogging

class VersionPublisher @Inject constructor(
    private val cloudStorage: GoogleCloudStorage,
    private val containerRegistry: ContainerRegistry,
    private val serviceRegistry: SpinnakerServiceRegistry,
    private val tagGenerator: ContainerTagGenerator
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Publish a version of Spinnaker using the data contained in {@code bom}, but overriding the version with {@code
     * version}.
     */
    fun publish(bom: Bom, version: String) {
        uploadBomToGcs(bom, version)
        tagContainers(bom, version)
    }

    private fun uploadBomToGcs(bom: Bom, version: String) {
        val gcsPath = "bom/$version.yml"
        logger.info { "Writing $gcsPath to GCS bucket ${cloudStorage.bucket}" }
        val versionedBom = bom.copy(version = version)
        val writer = cloudStorage.writer(gcsPath) { blobInfo -> blobInfo.setContentType("application/x-yaml") }
        writer.use {
            it.write(ByteBuffer.wrap(versionedBom.toYaml().toByteArray(Charsets.UTF_8)))
        }
    }

    private fun tagContainers(bom: Bom, spinnakerVersion: String) {
        bom.services
            .filterValues { it.version != null }
            .filterKeys { serviceRegistry.byServiceName.containsKey(it) }
            .mapValues { (_, serviceInfo) -> serviceInfo.version!! }
            .forEach { (service, serviceVersion) ->
                logger.info { "Tagging $service version $serviceVersion as Spinnaker version $spinnakerVersion" }
                tagGenerator.generateTagsForVersion(serviceVersion)
                    .zip(tagGenerator.generateTagsForVersion("spinnaker-$spinnakerVersion"))
                    .forEach { (serviceVersionTag, spinnakerVersionTag) ->
                        containerRegistry.addTag(service, existingTag = serviceVersionTag, newTag = spinnakerVersionTag)
                    }
            }
    }
}
