package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.VersionPublisher
import mu.KotlinLogging
import java.nio.file.Path
import javax.inject.Inject

class BomPublisher @Inject constructor(private val versionPublisher: VersionPublisher) {

    private val logger = KotlinLogging.logger {}

    fun publish(bomFile: Path, additionalVersions: Set<String> = setOf()) {
        val bom = Bom.readFromFile(bomFile)
        (additionalVersions + bom.version).forEach { version ->
            logger.info { "Publishing Spinnaker version $version" }
            versionPublisher.publish(bom, version)
        }
    }
}

@Subcomponent
interface BomPublisherComponent {
    fun bomPublisher(): BomPublisher
}

class BomPublisherCommand :
    CliktCommand(name = "publish_bom", help = "publish an already-built version of Spinnaker") {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()
    private val additionalVersions by option(
        "--additional-version",
        help = "an additional version to publish (beyond the one listed in the BOM; can be set more than once)"
    ).multiple()

    private val component by requireObject<MainComponent>()

    override fun run() {
        component.bomPublisherComponent().bomPublisher().publish(bomFile, additionalVersions.toSet())
    }
}
