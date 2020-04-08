package io.spinnaker.spinrel

import javax.inject.Inject

interface ContainerTagGenerator {
    /**
     * Given a specific version number, generates all the container tags that should exist for that version.
     *
     * While the ordering of this list is not meaningful, it is guaranteed to be stable.
     */
    fun generateTagsForVersion(version: String): Set<String>
}

class DefaultContainerTagGenerator @Inject constructor() :
    ContainerTagGenerator {

    private val tagSuffixes = setOf("", "-slim", "-ubuntu")

    override fun generateTagsForVersion(version: String): Set<String> = tagSuffixes.map { "$version$it" }.toSet()
}
