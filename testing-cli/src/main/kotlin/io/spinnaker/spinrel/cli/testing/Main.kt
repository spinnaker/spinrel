package io.spinnaker.spinrel.cli.testing

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import dagger.Component
import io.spinnaker.spinrel.cli.ProductionConfigModule

fun main(args: Array<String>) {
    Spinrel()
        .subcommands(CopyContainersCommand())
        .main(args)
}

class Spinrel : CliktCommand() {
    override fun run() {
        currentContext.obj = DaggerMainComponent.create()
    }
}

@Component(modules = [ProductionConfigModule::class])
interface MainComponent {

    fun containerCopierComponentFactory(): ContainerCopierComponent.Factory
}
