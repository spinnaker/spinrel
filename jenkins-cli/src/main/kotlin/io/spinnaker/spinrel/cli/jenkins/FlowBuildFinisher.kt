package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.google.common.io.ByteStreams
import dagger.BindsInstance
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.ContainerRegistry
import io.spinnaker.spinrel.ContainerTagGenerator
import io.spinnaker.spinrel.GoogleCloudStorage
import io.spinnaker.spinrel.SourceRoot
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import java.nio.ByteBuffer
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import javax.inject.Inject
import mu.KotlinLogging
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

private const val RWXR_XR_X = 0b111101101
private const val RW_R__R__ = 0b111101101

class FlowBuildFinisher @Inject constructor(
    private val cloudStorage: GoogleCloudStorage,
    private val containerRegistry: ContainerRegistry,
    private val serviceRegistry: SpinnakerServiceRegistry,
    private val tagGenerator: ContainerTagGenerator,
    @SourceRoot private val repositoriesDir: Path
) {

    private val logger = KotlinLogging.logger {}

    fun finishBuild(bomFile: Path, additionalVersions: Set<String> = setOf()) {
        val bom = Bom.readFromFile(bomFile)
        publishProfiles(repositoriesDir, bom)
        (additionalVersions + bom.version).forEach { version ->
            logger.info { "Publishing Spinnaker version $version" }
            uploadBomToGcs(bom, version)
            tagContainers(bom, version)
        }
    }

    private fun publishProfiles(repositoriesRoot: Path, bom: Bom) {
        data class ServiceData(val serviceName: String, val repositoryName: String?, val version: String?)
        bom.services
            .map { (serviceName, serviceInfo) ->
                ServiceData(
                    serviceName,
                    serviceRegistry.byServiceName[serviceName]?.repositoryName,
                    serviceInfo.version
                )
            }
            .filter { it.repositoryName != null && it.version != null }
            .forEach {
                val halconfigDir = repositoriesRoot.resolve(it.repositoryName!!).resolve("halconfig")
                if (Files.isDirectory(halconfigDir)) {
                    publishFiles(it.serviceName, it.version!!, halconfigDir)
                }
            }
    }

    private fun publishFiles(service: String, version: String, halconfigDir: Path) {
        Files.list(halconfigDir).forEach { child ->
            when {
                Files.isRegularFile(child) -> publishProfileFile(service, version, child)
                Files.isDirectory(child) -> publishProfileDirectory(service, version, child)
            }
        }
    }

    private fun publishProfileFile(service: String, version: String, profileFile: Path) {
        logger.warn { "publishing $service profile ${profileFile.fileName}" }
        val fileChannel = Files.newByteChannel(profileFile)
        fileChannel.use {
            val gcsPath = "$service/$version/${profileFile.fileName}"
            logger.info { "Writing $gcsPath to GCS bucket ${cloudStorage.bucket}" }
            val writer = cloudStorage.writer(gcsPath) { blobInfo -> blobInfo.setContentType("application/x-yaml") }
            writer.use {
                ByteStreams.copy(fileChannel, writer)
            }
        }
    }

    private fun publishProfileDirectory(service: String, version: String, profileDir: Path) {
        logger.warn { "publishing $service profile ${profileDir.fileName}" }
        // It's called ".tar.gz" but it's not actually gzipped. 😭
        val gcsPath = "$service/$version/${profileDir.fileName}.tar.gz"
        logger.info { "Writing $gcsPath to GCS bucket ${cloudStorage.bucket}" }
        val writer = cloudStorage.writer(gcsPath) { blobInfo -> blobInfo.setContentType("application/x-tar") }
        writer.use { blobWriter ->
            TarArchiveOutputStream(WritableByteChannelOutputStream(blobWriter)).use { tarOut ->
                Files.walk(profileDir, FileVisitOption.FOLLOW_LINKS)
                    .forEach { path -> addPathToTar(tarOut, path, profileDir) }
            }
        }
    }

    private fun addPathToTar(
        tarOut: TarArchiveOutputStream,
        path: Path,
        profileDir: Path
    ) {
        if (path.equals(profileDir)) return
        val relativePath = profileDir.relativize(path)
        logger.info { "Adding $relativePath to tarball" }
        when {
            Files.isRegularFile(path) -> writeFileToTar(tarOut, path, relativePath)
            Files.isDirectory(path) -> writeDirectoryToTar(tarOut, path, relativePath)
        }
    }

    private fun writeDirectoryToTar(tarOut: TarArchiveOutputStream, directory: Path, relativePath: Path) {
        val tarEntry = createBasicTarEntry(directory, relativePath).apply {
            name += "/"
            mode = RWXR_XR_X
        }
        tarOut.putArchiveEntry(tarEntry)
        tarOut.closeArchiveEntry()
    }

    private fun writeFileToTar(tarOut: TarArchiveOutputStream, file: Path, relativePath: Path) {
        val tarEntry = createBasicTarEntry(file, relativePath).apply {
            size = Files.size(file)
            mode = RW_R__R__
        }
        tarOut.putArchiveEntry(tarEntry)
        ByteStreams.copy(Files.newInputStream(file), tarOut)
        tarOut.closeArchiveEntry()
    }

    private fun createBasicTarEntry(file: Path, relativePath: Path) =
        TarArchiveEntry(relativePath.toString()).apply {
            modTime = Date(Files.getLastModifiedTime(file).toMillis())
            userName = "nobody"
            groupName = "nobody"
        }

    private fun uploadBomToGcs(bom: Bom, version: String) {
        val gcsPath = "bom/$version.yml"
        logger.info { "Writing $gcsPath to GCS bucket ${cloudStorage.bucket}" }
        val versionedBom = bom.copy(version = version)
        val writer = cloudStorage.writer(gcsPath) { blobInfo -> blobInfo.setContentType("application/x-yaml") }
        writer.use {
            it.write(ByteBuffer.wrap(versionedBom.toYaml().toByteArray(Charsets.UTF_8)))
        }
    }

    private fun tagContainers(bom: Bom, spinnakerVersion: String) {
        bom.services
            .filterValues { it.version != null }
            .filterKeys { serviceRegistry.byServiceName.containsKey(it) }
            .mapValues { (_, serviceInfo) -> serviceInfo.version!! }
            .forEach { (service, serviceVersion) ->
                logger.info { "Tagging $service version $serviceVersion as Spinnaker version $spinnakerVersion" }
                tagGenerator.generateTagsForVersion(serviceVersion)
                    .zip(tagGenerator.generateTagsForVersion("spinnaker-$spinnakerVersion"))
                    .forEach { (serviceVersionTag, spinnakerVersionTag) ->
                        containerRegistry.addTag(service, existingTag = serviceVersionTag, newTag = spinnakerVersionTag)
                    }
            }
    }
}

@Subcomponent
interface FlowBuildFinisherComponent {
    fun flowBuildFinisher(): FlowBuildFinisher

    @Subcomponent.Factory
    interface Factory {
        fun create(@BindsInstance @SourceRoot sourceRoot: Path): FlowBuildFinisherComponent
    }
}

class FinishFlowBuildCommand :
    CliktCommand(name = "finish_flow_build", help = "publish an already-built version of Spinnaker") {

    private val bomFile by option("--bom", help = "the path to the BOM file").path(
        canBeDir = false,
        mustBeReadable = true
    ).required()
    private val additionalVersions by option(
        "--additional-version",
        help = "an additional version to publish (beyond the one listed in the BOM; can be set more than once)"
    ).multiple()
    private val sourceRoot by option("--source-root", help = "the directory containing the git repositories").path(
        canBeFile = false,
        mustBeReadable = true
    ).required()

    val component by requireObject<MainComponent>()

    override fun run() {
        component.flowBuildFinisherComponentFactory().create(sourceRoot).flowBuildFinisher()
            .finishBuild(bomFile, additionalVersions.toSet())
    }
}
