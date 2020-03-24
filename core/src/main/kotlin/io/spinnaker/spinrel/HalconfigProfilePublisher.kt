package io.spinnaker.spinrel

import com.google.common.io.ByteStreams
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

class HalconfigProfilePublisher @Inject constructor(
    private val cloudStorage: GoogleCloudStorage,
    private val serviceRegistry: SpinnakerServiceRegistry
) {

    private val logger = KotlinLogging.logger {}

    fun publish(repositoriesRoot: Path, bom: Bom) {
        data class ServiceData(val serviceInfo: SpinnakerServiceInfo, val version: String)
        bom.services
            .filter { (serviceName, _) -> serviceRegistry.byServiceName.containsKey(serviceName) }
            .filter { (_, serviceInfo) -> serviceInfo.version != null }
            .map { (serviceName, serviceInfo) ->
                ServiceData(
                    serviceRegistry.byServiceName[serviceName]!!,
                    serviceInfo.version!!
                )
            }
            .forEach {
                val halconfigDir =
                    repositoriesRoot.resolve(it.serviceInfo.repositoryName).resolve(it.serviceInfo.halconfigDir)
                if (Files.isDirectory(halconfigDir)) {
                    publishFiles(it.serviceInfo.serviceName, it.version, halconfigDir)
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
        if (path == profileDir) return
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
}
