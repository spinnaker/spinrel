package io.spinnaker.spinrel

import com.google.cloud.storage.Storage
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import com.google.common.io.ByteStreams
import com.google.common.io.CharStreams
import com.google.common.jimfs.Jimfs
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.isEqualTo

class HalconfigProfilePublisherTest {

    private lateinit var profilePublisher: HalconfigProfilePublisher

    private lateinit var filesystem: FileSystem
    private lateinit var repositoriesDir: Path

    private lateinit var cloudStorage: GoogleCloudStorage

    private lateinit var containerSuffixes: MutableSet<String>
    private lateinit var serviceRegistry: MutableSet<SpinnakerServiceInfo>

    @BeforeEach
    fun setUp() {

        containerSuffixes = mutableSetOf()
        serviceRegistry = mutableSetOf()

        filesystem = Jimfs.newFileSystem("spinfs")
        repositoriesDir = filesystem.getPath("/path/to/repositories").also { Files.createDirectories(it) }

        val storage = LocalStorageHelper.getOptions().service
        cloudStorage = GoogleCloudStorage(storage, GcsBucket("gcsBucket"))
        profilePublisher = HalconfigProfilePublisher(
            cloudStorage,
            object : SpinnakerServiceRegistry {
                override val byServiceName: Map<String, SpinnakerServiceInfo>
                    get() = serviceRegistry.associateBy { it.serviceName }
            }
        )
    }

    @AfterEach
    fun tearDown() {
        filesystem.close()
    }

    @Test
    fun `publishes simple profile`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        val profileContent = "some stuff\n!!!\nhi how are you?? \uD83C\uDF61" // That's a dango, my friends.

        writeRepositoryFile("front50/halconfig/myconfig.yml", profileContent)

        profilePublisher.publish(repositoriesDir, bom)

        val storedContent = cloudStorage.readUtf8String("front50/1.3.22/myconfig.yml")

        expectThat(storedContent).isEqualTo(profileContent)
    }

    @Test
    fun `won't publish if not found in serviceRegistry`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")

        writeRepositoryFile("front50/myconfig.yml", "content")

        profilePublisher.publish(repositoriesDir, bom)

        val halconfigBlobs = cloudStorage.list(Storage.BlobListOption.prefix("front50/"))
        expectThat(halconfigBlobs).isEmpty()
    }

    @Test
    fun `publishes files under repository name, not service name (but uses service name in path)`() {

        val serviceName = "front50"
        val repositoryName = "front50-repo"

        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion(serviceName, "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo(serviceName, repositoryName))

        writeRepositoryFile("$repositoryName/halconfig/$repositoryName.yml", "$repositoryName content")
        writeRepositoryFile("$serviceName/halconfig/$serviceName.yml", "$serviceName content")

        profilePublisher.publish(repositoriesDir, bom)

        // the service name is used for the directory, but the filename is the same as that on disk, so in this case is
        // the repository name
        val storedContent = cloudStorage.readUtf8String("$serviceName/1.3.22/$repositoryName.yml")

        expectThat(storedContent).isEqualTo("$repositoryName content")

        val halconfigBlobs = cloudStorage.list(Storage.BlobListOption.prefix("$repositoryName/"))
        expectThat(halconfigBlobs).isEmpty()
    }

    @Test
    fun `publishes multiple files in a single repository`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        writeRepositoryFile("front50/halconfig/config1.yml", "config1 content")
        writeRepositoryFile("front50/halconfig/config2.yml", "config2 content")

        profilePublisher.publish(repositoriesDir, bom)

        val storedContent1 = cloudStorage.readUtf8String("front50/1.3.22/config1.yml")
        expectThat(storedContent1).isEqualTo("config1 content")

        val storedContent2 = cloudStorage.readUtf8String("front50/1.3.22/config2.yml")
        expectThat(storedContent2).isEqualTo("config2 content")
    }

    @Test
    fun `publishes files in multiple repositories`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        writeRepositoryFile("front50/halconfig/front50.yml", "front50 content")
        writeRepositoryFile("deck/halconfig/deck.yml", "deck content")

        profilePublisher.publish(repositoriesDir, bom)

        val storedFront50Content = cloudStorage.readUtf8String("front50/1.3.22/front50.yml")
        expectThat(storedFront50Content).isEqualTo("front50 content")

        val storedDeckContent = cloudStorage.readUtf8String("deck/9.8/deck.yml")
        expectThat(storedDeckContent).isEqualTo("deck content")
    }

    @Test
    fun `publishes a directory of files`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        writeRepositoryFile("front50/halconfig/configdir/file1.yml", "file1 content")
        writeRepositoryFile("front50/halconfig/configdir/file2.yml", "file2 content")
        writeRepositoryFile("front50/halconfig/configdir/subdir/file3.yml", "file3 content")
        writeRepositoryFile("front50/halconfig/configdir/subdir/file4.yml", "file4 content")

        profilePublisher.publish(repositoriesDir, bom)

        cloudStorage.readAllBytes("front50/1.3.22/configdir.tar.gz").also {
            val tarIn = TarArchiveInputStream(ByteArrayInputStream(it))
            var nextTarEntry = tarIn.nextTarEntry
            val fileContents: MutableMap<String, String> = HashMap()
            while (nextTarEntry != null) {
                if (!nextTarEntry.isDirectory) {
                    fileContents[nextTarEntry.name] = CharStreams.toString(InputStreamReader(tarIn, Charsets.UTF_8))
                }
                nextTarEntry = tarIn.nextTarEntry
            }

            expectThat(fileContents)
                .isEqualTo(
                    mutableMapOf(
                        "file1.yml" to "file1 content",
                        "file2.yml" to "file2 content",
                        "subdir/file3.yml" to "file3 content",
                        "subdir/file4.yml" to "file4 content"
                    )
                )
        }
    }

    @Test
    fun `publishes simple profile at symlink`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        val target = writeRepositoryFile("some/other/dir/otherFilename.yml", "symlinked content")
        val halconfigDir = createRepositoryDirectory("front50/halconfig")
        Files.createSymbolicLink(halconfigDir.resolve("myconfig.yml"), target)

        profilePublisher.publish(repositoriesDir, bom)

        val storedContent = cloudStorage.readUtf8String("front50/1.3.22/myconfig.yml")

        expectThat(storedContent).isEqualTo("symlinked content")
    }

    @Test
    fun `publishes a directory of files at symlink`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        val target = createRepositoryDirectory("some/other/dir/otherConfigDir")
        writeRepositoryFile("some/other/dir/otherConfigDir/file1.yml", "file1 content")
        writeRepositoryFile("some/other/dir/otherConfigDir/file2.yml", "file2 content")
        writeRepositoryFile("some/other/dir/otherConfigDir/subdir/file3.yml", "file3 content")
        writeRepositoryFile("some/other/dir/otherConfigDir/subdir/file4.yml", "file4 content")
        val halconfigDir = createRepositoryDirectory("front50/halconfig")
        Files.createSymbolicLink(halconfigDir.resolve("configdir"), target)

        profilePublisher.publish(repositoriesDir, bom)

        cloudStorage.readAllBytes("front50/1.3.22/configdir.tar.gz").also {
            val tarIn = TarArchiveInputStream(ByteArrayInputStream(it))
            var nextTarEntry = tarIn.nextTarEntry
            val fileContents: MutableMap<String, String> = HashMap()
            while (nextTarEntry != null) {
                if (!nextTarEntry.isDirectory) {
                    fileContents[nextTarEntry.name] = CharStreams.toString(InputStreamReader(tarIn, Charsets.UTF_8))
                }
                nextTarEntry = tarIn.nextTarEntry
            }

            expectThat(fileContents)
                .isEqualTo(
                    mutableMapOf(
                        "file1.yml" to "file1 content",
                        "file2.yml" to "file2 content",
                        "subdir/file3.yml" to "file3 content",
                        "subdir/file4.yml" to "file4 content"
                    )
                )
        }
    }

    @Test
    fun `publishes a directory of files at a symlink with included symlinks`() {
        val bom = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        // This directory structure has:
        // * configdir is a symlink to a directory, which contains
        // * a regular file
        // * a symlink to another file
        // * a symlink to another directory containing regular files
        // but which should resolve to the exact same directory structure as `publishes a directory of files`
        // configdir -> somedir/targetDir
        // somedir/targetDir/file1.yml
        //                   file2.yml -> ../subtargetDir/file2target.yml
        //         targetDir/subdir -> ../otherTargetDir
        //         subtargetDir/file2target.yml
        //         otherTargetDir/file3.yml
        //         otherTargetDir/file4.yml

        val targetDir = createRepositoryDirectory("somedir/targetDir")
        writeRepositoryFile("somedir/targetDir/file1.yml", "file1 content")
        val file2Target = writeRepositoryFile("somedir/subtargetDir/file2target.yml", "file2 content")
        Files.createSymbolicLink(targetDir.resolve("file2.yml"), file2Target)
        val otherTargetDir = createRepositoryDirectory("somedir/otherTargetDir")
        Files.createSymbolicLink(targetDir.resolve("subdir"), otherTargetDir)
        writeRepositoryFile("somedir/subtargetDir/file2target.yml", "file2 content")
        writeRepositoryFile("somedir/otherTargetDir/file3.yml", "file3 content")
        writeRepositoryFile("somedir/otherTargetDir/file4.yml", "file4 content")
        val halconfigDir = createRepositoryDirectory("front50/halconfig")
        Files.createSymbolicLink(halconfigDir.resolve("configdir"), targetDir)

        profilePublisher.publish(repositoriesDir, bom)

        cloudStorage.readAllBytes("front50/1.3.22/configdir.tar.gz").also {
            val tarIn = TarArchiveInputStream(ByteArrayInputStream(it))
            var nextTarEntry = tarIn.nextTarEntry
            val fileContents: MutableMap<String, String> = HashMap()
            while (nextTarEntry != null) {
                if (!nextTarEntry.isDirectory) {
                    fileContents[nextTarEntry.name] = CharStreams.toString(InputStreamReader(tarIn, Charsets.UTF_8))
                }
                nextTarEntry = tarIn.nextTarEntry
            }

            expectThat(fileContents)
                .isEqualTo(
                    mutableMapOf(
                        "file1.yml" to "file1 content",
                        "file2.yml" to "file2 content",
                        "subdir/file3.yml" to "file3 content",
                        "subdir/file4.yml" to "file4 content"
                    )
                )
        }
    }

    private fun createMinimalBomWithVersion(version: String): Bom {
        return Bom(
            artifactSources = ArtifactSources("debianRepository", "dockerRegistry", "gitPrefix", "googleImageProject"),
            dependencies = mapOf(),
            services = mapOf(),
            timestamp = "timestamp",
            version = version
        )
    }

    private fun Bom.withServiceVersion(service: String, version: String): Bom {
        return this.copy(services = services.plus(service to ServiceInfo(version = version)))
    }

    private fun writeRepositoryFile(path: String, content: String): Path =
        repositoriesDir.resolve(path)
            .also { Files.createDirectories(it.parent) }
            .also { // TODO(plumpy): in Java 11, use Files.writeString
                ByteStreams.copy(
                    ByteArrayInputStream(
                        content.toByteArray(Charsets.UTF_8)
                    ),
                    Files.newOutputStream(it)
                )
            }

    private fun createRepositoryDirectory(path: String): Path =
        repositoriesDir.resolve(path).also { Files.createDirectories(it) }
}
