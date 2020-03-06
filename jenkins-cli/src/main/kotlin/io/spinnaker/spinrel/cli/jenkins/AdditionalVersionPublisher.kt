package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dagger.Subcomponent
import io.spinnaker.spinrel.BomStorage
import io.spinnaker.spinrel.VersionPublisher
import javax.inject.Inject
import mu.KotlinLogging

class AdditionalVersionPublisher @Inject constructor(
    private val bomStorage: BomStorage,
    private val versionPublisher: VersionPublisher
) {

    private val logger = KotlinLogging.logger {}

    fun publish(sourceVersion: String, destinationVersion: String) {
        val bom = bomStorage.get(sourceVersion)
        logger.info { "Publishing Spinnaker version $destinationVersion" }
        versionPublisher.publish(bom, destinationVersion)
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

    private val sourceVersion by option(
        "--source-version",
        help = "the version that will be copied to --destination-version"
    ).required()
    private val destinationVersion by option(
        "--destination-version",
        help = "the version that will be created"
    ).required()

    val component by requireObject<MainComponent>()

    override fun run() {
        component.additionalVersionPublisherComponent().additionalVersionPublisher()
            .publish(sourceVersion, destinationVersion)
    }
}
