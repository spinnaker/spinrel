package io.spinnaker.spinrel.cli

import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.DefaultContainerTagGenerator
import io.spinnaker.spinrel.DefaultSpinnakerServiceRegistry
import io.spinnaker.spinrel.SpinnakerServiceRegistry

@dagger.Module
interface ProductionConfigModule {

    @dagger.Binds
    fun bindContainerTagGenerator(containerTagGenerator: DefaultContainerTagGenerator): ContainerTagGenerator

    @dagger.Binds
    fun bindSpinnakerServiceRegistry(spinnakerServiceRegistry: DefaultSpinnakerServiceRegistry):
            SpinnakerServiceRegistry
}
