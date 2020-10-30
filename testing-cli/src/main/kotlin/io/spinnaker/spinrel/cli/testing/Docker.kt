package io.spinnaker.spinrel.cli.testing

import io.spinnaker.spinrel.GcrProject
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import javax.inject.Inject

class Docker @Inject constructor() {

    private val logger = KotlinLogging.logger {}

    // In theory this can be done instead with two HTTP calls (avoiding the pull), but it would require mucking around
    // with authentication headers. Maybe worth it?
    // https://dille.name/blog/2018/09/20/how-to-tag-docker-images-without-pulling-them/
    // https://cloud.google.com/run/docs/authenticating/developers
    fun copyContainer(
        imageName: String,
        sourceProject: GcrProject,
        sourceTag: String,
        destProject: GcrProject,
        destTag: String
    ) {
        val sourceUrl = "gcr.io/$sourceProject/$imageName:$sourceTag"
        val destUrl = "gcr.io/$destProject/$imageName:$destTag"
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
