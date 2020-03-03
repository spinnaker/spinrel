package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dagger.BindsInstance
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.ContainerRegistry
import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.GoogleCloudStorage
import io.spinnaker.spinrel.HalconfigProfilePublisher
import io.spinnaker.spinrel.SourceRoot
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import java.nio.ByteBuffer
import java.nio.file.Path
import javax.inject.Inject
import mu.KotlinLogging

class FlowBuildFinisher @Inject constructor(
    private val cloudStorage: GoogleCloudStorage,
    private val containerRegistry: ContainerRegistry,
    private val serviceRegistry: SpinnakerServiceRegistry,
    private val tagGenerator: ContainerTagGenerator,
    private val profilePublisher: HalconfigProfilePublisher,
    @SourceRoot private val repositoriesDir: Path
) {

    private val logger = KotlinLogging.logger {}

    fun finishBuild(bomFile: Path, additionalVersions: Set<String> = setOf()) {
        val bom = Bom.readFromFile(bomFile)
        profilePublisher.publish(repositoriesDir, bom)
        (additionalVersions + bom.version).forEach { version ->
            logger.info { "Publishing Spinnaker version $version" }
            uploadBomToGcs(bom, version)
            tagContainers(bom, version)
        }
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

@Subcomponent
interface FlowBuildFinisherComponent {
    fun flowBuildFinisher(): FlowBuildFinisher

    @Subcomponent.Factory
    interface Factory {
        fun create(@BindsInstance @SourceRoot sourceRoot: Path): FlowBuildFinisherComponent
    }
}

class FinishFlowBuildCommand :
    CliktCommand(name = "finish_flow_build", help = "publish an already-built version of Spinnaker") {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()
    private val additionalVersions by option(
        "--additional-version",
        help = "an additional version to publish (beyond the one listed in the BOM; can be set more than once)"
    ).multiple()
    private val sourceRoot by option("--source-root", help = "the directory containing the git repositories").path(
        canBeFile = false,
        mustBeReadable = true
    ).required()

    val component by requireObject<MainComponent>()

    override fun run() {
        component.flowBuildFinisherComponentFactory().create(sourceRoot).flowBuildFinisher()
            .finishBuild(bomFile, additionalVersions.toSet())
    }
}
