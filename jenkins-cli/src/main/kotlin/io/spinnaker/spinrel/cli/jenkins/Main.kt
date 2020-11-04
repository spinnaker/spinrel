package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dagger.BindsInstance
import dagger.Component
import io.spinnaker.spinrel.GcsBucket
import io.spinnaker.spinrel.GoogleApiHttpClientModule
import io.spinnaker.spinrel.GoogleCloudStorageModule
import io.spinnaker.spinrel.GoogleDockerRegistryModule
import io.spinnaker.spinrel.cli.ProductionConfigModule
import io.spinnaker.spinrel.cli.gcsBucket
import javax.inject.Singleton

fun main(args: Array<String>) {
    Spinrel()
        .subcommands(
            BomPublisherCommand(),
            ProfilePublisherCommand(),
            PublishAdditionalVersionCommand(),
            PublishSpinnakerCommand()
        )
        .main(args)
}

class Spinrel : CliktCommand() {
    private val gcsBucket by option(help = "the GCS bucket to which the BOM will be written").gcsBucket().default(
        GcsBucket("halconfig")
    )

    override fun run() {
        currentContext.obj = DaggerMainComponent.factory().create(gcsBucket)
    }
}

@Singleton
@Component(
    modules = [
        GoogleApiHttpClientModule::class,
        GoogleCloudStorageModule::class,
        GoogleDockerRegistryModule::class,
        OkHttpClientModule::class,
        ProductionConfigModule::class
    ]
)
interface MainComponent {

    fun additionalVersionPublisherComponent(): AdditionalVersionPublisherComponent

    fun bomPublisherComponent(): BomPublisherComponent

    fun profilePublisherComponent(): ProfilePublisherComponent

    fun spinnakerVersionPublisherComponent(): SpinnakerVersionPublisherComponent.Factory

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance gcsBucket: GcsBucket): MainComponent
    }
}
