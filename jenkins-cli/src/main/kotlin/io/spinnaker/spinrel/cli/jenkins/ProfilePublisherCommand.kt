package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.HalconfigProfilePublisher

@Subcomponent
interface ProfilePublisherComponent {
    fun profilePublisher(): HalconfigProfilePublisher
}

class ProfilePublisherCommand :
    CliktCommand(name = "publish_profiles", help = "publish halconfig profiles to GCS") {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()
    private val sourceRoot by option("--source-root", help = "the directory containing the git repositories").path(
        canBeFile = false,
        mustBeReadable = true
    ).required()

    private val component by requireObject<MainComponent>()

    override fun run() {
        val bom = Bom.readFromFile(bomFile)
        component.profilePublisherComponent().profilePublisher().publish(sourceRoot, bom)
    }
}
