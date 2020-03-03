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
import io.spinnaker.spinrel.HalconfigProfilePublisher
import io.spinnaker.spinrel.SourceRoot
import io.spinnaker.spinrel.VersionPublisher
import java.nio.file.Path
import javax.inject.Inject
import mu.KotlinLogging

class FlowBuildFinisher @Inject constructor(
    private val profilePublisher: HalconfigProfilePublisher,
    private val versionPublisher: VersionPublisher,
    @SourceRoot private val repositoriesDir: Path
) {

    private val logger = KotlinLogging.logger {}

    fun finishBuild(bomFile: Path, additionalVersions: Set<String> = setOf()) {
        val bom = Bom.readFromFile(bomFile)
        profilePublisher.publish(repositoriesDir, bom)
        (additionalVersions + bom.version).forEach { version ->
            logger.info { "Publishing Spinnaker version $version" }
            versionPublisher.publish(bom, version)
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
