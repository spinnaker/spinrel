package io.spinnaker.spinrel

import javax.inject.Inject

data class SpinnakerServiceInfo(val serviceName: String, val repositoryName: String? = serviceName)

interface SpinnakerServiceRegistry {
    val byServiceName: Map<String, SpinnakerServiceInfo>
}

class DefaultSpinnakerServiceRegistry @Inject constructor() :
    SpinnakerServiceRegistry {

    private val serviceInfos = setOf(
        SpinnakerServiceInfo("clouddriver"),
        SpinnakerServiceInfo("deck"),
        SpinnakerServiceInfo("echo"),
        SpinnakerServiceInfo("fiat"),
        SpinnakerServiceInfo("front50"),
        SpinnakerServiceInfo("gate"),
        SpinnakerServiceInfo("igor"),
        SpinnakerServiceInfo("kayenta"),
        SpinnakerServiceInfo(
            "monitoring-daemon",
            repositoryName = "spinnaker-monitoring"
        ),
        SpinnakerServiceInfo("orca"),
        SpinnakerServiceInfo("rosco")
    )

    override val byServiceName by lazy {
        serviceInfos.associateBy { it.serviceName }
    }
}
