package io.spinnaker.spinrel.cli.testing

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dagger.BindsInstance
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.Docker
import io.spinnaker.spinrel.GcrProject
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import io.spinnaker.spinrel.cli.gcrProject
import java.nio.file.Path
import javax.inject.Inject

class ContainerCopier @Inject constructor(
    private val sourceProject: GcrProject,
    private val docker: Docker,
    private val serviceRegistry: SpinnakerServiceRegistry,
    private val tagGenerator: ContainerTagGenerator
) {

    fun copyContainers(bomFile: Path, destProject: GcrProject) {
        val bom = Bom.readFromFile(bomFile)

        bom.services
            .filterValues { it.version != null }
            .filterKeys { serviceRegistry.byServiceName.containsKey(it) }
            .mapValues { (_, serviceInfo) -> serviceInfo.version!! }
            .forEach { (service, version) ->
                tagGenerator.generateTagsForVersion(version).forEach { tag ->
                    val source = "gcr.io/$sourceProject/$service:$tag"
                    val dest = "gcr.io/$destProject/$service:$tag"
                    docker.runCommand("pull", source)
                    docker.runCommand("tag", source, dest)
                    docker.runCommand("push", dest)
                }
            }
    }
}

@Subcomponent
interface ContainerCopierComponent {
    fun containerCopier(): ContainerCopier

    @Subcomponent.Factory
    interface Factory {
        fun create(@BindsInstance gcrProject: GcrProject): ContainerCopierComponent
    }
}

class CopyContainersCommand :
    CliktCommand(
        name = "copy_containers",
        help = "copy containers for a release from the --source-project to --destination-project"
    ) {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()

    private val sourceProject by option(
        "--source-project"
        help = "the GCR project containing the containers"
    ).gcrProject().default(GcrProject("spinnaker-marketplace"))
    private val destinationProject by option(
        "--destination-project",
        help = "the GCR project where containers will be copied"
    ).gcrProject().required()

    val component by requireObject<MainComponent>()

    override fun run() =
        component.containerCopierComponentFactory().create(sourceProject).containerCopier().copyContainers(bomFile, destinationProject)
}
