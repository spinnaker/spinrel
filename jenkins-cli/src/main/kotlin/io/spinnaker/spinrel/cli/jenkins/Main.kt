package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dagger.BindsInstance
import dagger.Component
import io.spinnaker.spinrel.GcrProject
import io.spinnaker.spinrel.GcsBucket
import io.spinnaker.spinrel.GoogleApiHttpClientModule
import io.spinnaker.spinrel.GoogleAuthModule
import io.spinnaker.spinrel.GoogleCloudStorageModule
import io.spinnaker.spinrel.GoogleContainerRegistryModule
import io.spinnaker.spinrel.cli.ProductionConfigModule
import io.spinnaker.spinrel.cli.gcrProject
import io.spinnaker.spinrel.cli.gcsBucket
import javax.inject.Singleton

fun main(args: Array<String>) {
    Spinrel()
        .subcommands(FinishFlowBuildCommand(), PublishAdditionalVersionCommand())
        .main(args)
}

class Spinrel : CliktCommand() {
    private val gcrProject by option(help = "the GCR project containing the containers").gcrProject().default(
        GcrProject("spinnaker-marketplace")
    )
    private val gcsBucket by option(help = "the GCS bucket to which the BOM will be written").gcsBucket().default(
        GcsBucket("halconfig")
    )

    override fun run() {
        currentContext.obj = DaggerMainComponent.factory().create(gcrProject, gcsBucket)
    }
}

@Singleton
@Component(
    modules = [GoogleApiHttpClientModule::class, GoogleCloudStorageModule::class, GoogleContainerRegistryModule::class, ProductionConfigModule::class]
)
interface MainComponent {

    fun flowBuildFinisherComponentFactory(): FlowBuildFinisherComponent.Factory

    fun additionalVersionPublisherComponent(): AdditionalVersionPublisherComponent

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance gcrProject: GcrProject,
            @BindsInstance gcsBucket: GcsBucket
        ): MainComponent
    }
}
