package io.spinnaker.spinrel

import dagger.Binds
import dagger.Module
import java.io.File
import java.io.IOException
import javax.inject.Inject
import mu.KotlinLogging

class GcrProject(val name: String) {
    override fun toString() = name
}

interface ContainerRegistry {
    fun addTag(service: String, existingTag: String, newTag: String)
}

class GoogleContainerRegistry @Inject constructor(val gcrProject: GcrProject, val docker: Docker) :
    ContainerRegistry {

    override fun addTag(service: String, existingTag: String, newTag: String) {
        docker.copyContainer(
            imageName = service,
            sourceRegistry = gcrProject.toString(), sourceTag = existingTag,
            destRegistry = gcrProject.toString(), destTag = newTag
        )
    }
}

class Docker @Inject constructor() {

    private val logger = KotlinLogging.logger {}

    // In theory this can be done instead with two HTTP calls (avoiding the pull), but it would require mucking around
    // with authentication headers. Maybe worth it?
    // https://dille.name/blog/2018/09/20/how-to-tag-docker-images-without-pulling-them/
    // https://cloud.google.com/run/docs/authenticating/developers
    fun copyContainer(
        imageName: String,
        sourceRegistry: String,
        sourceTag: String,
        destRegistry: String,
        destTag: String
    ) {
        val sourceUrl = "gcr.io/$sourceRegistry/$imageName:$sourceTag"
        val destUrl = "gcr.io/$destRegistry/$imageName:$destTag"
        runCommand("pull", sourceUrl)
        runCommand("tag", sourceUrl, destUrl)
        runCommand("push", destUrl)
    }

    private fun runCommand(vararg args: String) {
        val command = listOf("docker", *args)
        logger.info { "Running command ${command.joinToString(separator = " ")}" }
        val process = ProcessBuilder().command(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(File("/dev/null")) // In Java11, this can just be Redirect.DISCARD
            .start()
        val returnCode = process.waitFor()
        if (returnCode != 0) {
            throw IOException(
                "Docker command exited with return code $returnCode: ${command.joinToString(separator = " ")}"
            )
        }
    }
}

@Module
interface GoogleContainerRegistryModule {
    @Binds
    fun bindGoogleContainerRegistry(registry: GoogleContainerRegistry): ContainerRegistry
}
