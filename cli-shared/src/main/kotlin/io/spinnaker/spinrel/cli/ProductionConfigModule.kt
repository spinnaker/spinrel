package io.spinnaker.spinrel.cli

import dagger.Binds
import dagger.Module
import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.DefaultContainerTagGenerator
import io.spinnaker.spinrel.DefaultSpinnakerServiceRegistry
import io.spinnaker.spinrel.GoogleAuthModule
import io.spinnaker.spinrel.SpinnakerServiceRegistry

@Module(includes = [GoogleAuthModule::class])
interface ProductionConfigModule {

    @Binds
    fun bindContainerTagGenerator(containerTagGenerator: DefaultContainerTagGenerator): ContainerTagGenerator

    @Binds
    fun bindSpinnakerServiceRegistry(spinnakerServiceRegistry: DefaultSpinnakerServiceRegistry):
        SpinnakerServiceRegistry
}
