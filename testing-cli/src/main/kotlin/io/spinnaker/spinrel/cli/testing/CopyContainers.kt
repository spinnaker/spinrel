package io.spinnaker.spinrel.cli.testing

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import java.nio.file.Path
import javax.inject.Inject

class ContainerCopier @Inject constructor(
    private val docker: Docker,
    private val serviceRegistry: SpinnakerServiceRegistry,
    private val tagGenerator: ContainerTagGenerator
) {

    fun copyContainers(bomFile: Path, sourceRegistryArg: String?, destRegistry: String) {
        val bom = Bom.readFromFile(bomFile)

        val sourceRegistry = sourceRegistryArg ?: bom.artifactSources.dockerRegistry
        bom.services
            .filterValues { it.version != null }
            .filterKeys { serviceRegistry.byServiceName.containsKey(it) }
            .mapValues { (_, serviceInfo) -> serviceInfo.version!! }
            .forEach { (service, version) ->
                tagGenerator.generateTagsForVersion(version).forEach { tag ->
                    docker.copyContainer(
                        imageName = service,
                        sourceRegistry = sourceRegistry,
                        sourceTag = tag,
                        destRegistry = destRegistry,
                        destTag = tag
                    )
                }
            }
    }
}

@Subcomponent
interface ContainerCopierComponent {
    fun containerCopier(): ContainerCopier
}

class CopyContainersCommand :
    CliktCommand(
        name = "copy_containers",
        help = "copy containers for a release from the --source-registry to --destination-registry"
    ) {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()

    private val sourceRegistry by option(
        "--source-registry",
        help = "the docker registry containing the containers"
    )
    private val destinationRegistry by option(
        "--destination-registry",
        help = "the docker registry where containers will be copied"
    ).required()

    private val component by requireObject<MainComponent>()

    override fun run() =
        component.containerCopierComponent().containerCopier().copyContainers(bomFile, sourceRegistry, destinationRegistry)
}
