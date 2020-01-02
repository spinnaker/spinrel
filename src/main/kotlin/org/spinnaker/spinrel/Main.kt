package org.spinnaker.spinrel

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

fun main(args: Array<String>) {
    Spinrel()
        .subcommands(CopyContainersCommand(), PublishVersionCommand())
        .main(args)
}

class Spinrel : CliktCommand() {
    private val gcrProject by option(help = "the GCR project containing the containers").gcrProject().default(
        DEFAULT_GCR_PROJECT
    )
    private val gcsBucket by option(help = "the GCS bucket to which the BOM will be written").gcsBucket().default(
        DEFAULT_GCS_BUCKET
    )

    override fun run() {
        context.obj = DaggerMainComponent.factory().create(gcrProject, gcsBucket)
    }
}

@Module
interface ProductionConfigModule {

    @Binds
    fun bindContainerTagGenerator(containerTagGenerator: DefaultContainerTagGenerator): ContainerTagGenerator

    @Binds
    fun bindSpinnakerServiceRegistry(spinnakerServiceRegistry: DefaultSpinnakerServiceRegistry):
            SpinnakerServiceRegistry
}

@Component(
    modules = [GoogleCloudStorageModule::class, GoogleContainerRegistryModule::class, ProductionConfigModule::class]
)
interface MainComponent {

    fun versionPublisherComponentFactory(): VersionPublisherComponent.Factory

    fun containerCopierComponent(): ContainerCopierComponent

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance gcrProject: GcrProject,
            @BindsInstance gcsBucket: GcsBucket
        ): MainComponent
    }
}
