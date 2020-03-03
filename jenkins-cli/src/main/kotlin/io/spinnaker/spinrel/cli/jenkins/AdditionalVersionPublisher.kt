package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.VersionPublisher
import java.nio.file.Path
import javax.inject.Inject
import mu.KotlinLogging

class AdditionalVersionPublisher @Inject constructor(private val versionPublisher: VersionPublisher) {

    private val logger = KotlinLogging.logger {}

    fun publish(bomFile: Path, version: String) {
        val bom = Bom.readFromFile(bomFile)
        logger.info { "Publishing Spinnaker version $version" }
        versionPublisher.publish(bom, version)
    }
}

@Subcomponent
interface AdditionalVersionPublisherComponent {
    fun additionalVersionPublisher(): AdditionalVersionPublisher
}

class PublishAdditionalVersionCommand :
    CliktCommand(
        name = "publish_additional_version",
        help = "publish a BOM that already exists in GCR/GCS to a new version"
    ) {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()
    private val additionalVersion by option(
        "--additional-version",
        help = "an additional version to publish (beyond the one listed in the BOM; can be set more than once)"
    ).required()

    val component by requireObject<MainComponent>()

    override fun run() {
        component.additionalVersionPublisherComponent().additionalVersionPublisher()
            .publish(bomFile, additionalVersion)
    }
}
