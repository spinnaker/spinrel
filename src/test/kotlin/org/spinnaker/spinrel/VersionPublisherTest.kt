package org.spinnaker.spinrel

import com.google.cloud.storage.Storage
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import com.google.common.io.CharStreams
import com.google.common.jimfs.Jimfs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import io.mockk.verifyAll
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.set
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import strikt.api.expectThat
import strikt.assertions.isEqualTo

@ExtendWith(MockKExtension::class)
class VersionPublisherTest {

    private lateinit var versionPublisher: VersionPublisher

    private lateinit var filesystem: FileSystem
    private lateinit var repositoriesDir: Path

    private lateinit var cloudStorage: GoogleCloudStorage

    @MockK(relaxUnitFun = true)
    private lateinit var containerRegistry: ContainerRegistry

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
        versionPublisher = VersionPublisher(
            cloudStorage,
            containerRegistry,
            object : SpinnakerServiceRegistry {
                override val byServiceName: Map<String, SpinnakerServiceInfo>
                    get() = serviceRegistry.associateBy { it.serviceName }
            },
            object : ContainerTagGenerator {
                override fun generateTagsForVersion(version: String) =
                    containerSuffixes.map { "$version$it" }.toSet()
            },
            repositoriesDir
        )
    }

    @AfterEach
    fun tearDown() {
        filesystem.close()
    }

    @Test
    fun `publishVersion writes BOM to GCS`() {
        val inputBom = createMinimalBomWithVersion("1.2.3")
        val bomPath = inputBom.write()
        versionPublisher.publish(bomPath)

        val storedBom = Bom.readFromString(cloudStorage.readUtf8String("bom/1.2.3.yml"))

        expectThat(storedBom).isEqualTo(inputBom.copy(version = "1.2.3"))
    }

    @Test
    fun `publishVersion writes BOM to GCS with multiple spinnaker versions`() {
        val inputBom = createMinimalBomWithVersion("999")
        val bomPath = inputBom.write()
        versionPublisher.publish(bomPath, additionalVersions = setOf("1.9", "master-latest-validated"))

        val bom999 = Bom.readFromString(cloudStorage.readUtf8String("bom/999.yml"))
        expectThat(bom999).isEqualTo(inputBom.copy(version = "999"))

        val bom19 = Bom.readFromString(cloudStorage.readUtf8String("bom/1.9.yml"))
        expectThat(bom19).isEqualTo(inputBom.copy(version = "1.9"))

        val bomMasterLatestValidated =
            Bom.readFromString(cloudStorage.readUtf8String("bom/master-latest-validated.yml"))
        expectThat(bomMasterLatestValidated).isEqualTo(inputBom.copy(version = "master-latest-validated"))
    }

    @Test
    fun `tags containers in GCR`() {
        val bomPath = createMinimalBomWithVersion("9.8.7").withServiceVersion("front50", "1.3.22").write()
        containerSuffixes.add("")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        versionPublisher.publish(bomPath)

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-9.8.7")
        }
    }

    @Test
    fun `tags containers in GCR with multiple suffixes`() {
        val bomPath = createMinimalBomWithVersion("9.8.7").withServiceVersion("front50", "1.3.22").write()
        containerSuffixes.addAll(setOf("", "-foo"))
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        versionPublisher.publish(bomPath)

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-9.8.7")
            containerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-9.8.7-foo")
        }
    }

    @Test
    fun `tags containers in GCR with multiple services`() {
        val bomPath = createMinimalBomWithVersion("9.8.7")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        containerSuffixes.add("")
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        versionPublisher.publish(bomPath)

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-9.8.7")
            containerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-9.8.7")
        }
    }

    @Test
    fun `tags containers in GCR with multiple versions`() {
        val bomPath = createMinimalBomWithVersion("123").withServiceVersion("front50", "1.3.22").write()
        containerSuffixes.add("")
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50")))

        versionPublisher.publish(bomPath, additionalVersions = setOf("456"))

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-123")
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-456")
        }
    }

    @Test
    fun `tags containers in GCR with multiple services, suffixes, and versions`() {
        val bomPath = createMinimalBomWithVersion("123")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        containerSuffixes.addAll(setOf("", "-foo"))
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        versionPublisher.publish(bomPath, additionalVersions = setOf("456"))

        verifyAll {
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-123")
            containerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-123-foo")
            containerRegistry.addTag("front50", existingTag = "1.3.22", newTag = "spinnaker-456")
            containerRegistry.addTag("front50", existingTag = "1.3.22-foo", newTag = "spinnaker-456-foo")
            containerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-123")
            containerRegistry.addTag("deck", existingTag = "9.8-foo", newTag = "spinnaker-123-foo")
            containerRegistry.addTag("deck", existingTag = "9.8", newTag = "spinnaker-456")
            containerRegistry.addTag("deck", existingTag = "9.8-foo", newTag = "spinnaker-456-foo")
        }
    }

    @Test
    fun `doesn't tag container missing from serviceRegistry`() {
        val bomPath = createMinimalBomWithVersion("9.8.7")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        containerSuffixes.add("")
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        versionPublisher.publish(bomPath)

        verify(exactly = 0) {
            containerRegistry.addTag("deck", any(), any())
        }
    }

    @Test
    fun `publishes simple profile`() {
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        val profileContent = "some stuff\n!!!\nhi how are you?? \uD83C\uDF61" // That's a dango, my friends.

        writeRepositoryFile("front50/halconfig/myconfig.yml", profileContent)

        versionPublisher.publish(bomPath)

        val storedContent = cloudStorage.readUtf8String("front50/1.3.22/myconfig.yml")

        expectThat(storedContent).isEqualTo(profileContent)
    }

    @Test
    fun `won't publish if not found in serviceRegistry`() {
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()

        writeRepositoryFile("front50/myconfig.yml", "content")

        versionPublisher.publish(bomPath)

        val halconfigBlobs = cloudStorage.list(Storage.BlobListOption.prefix("front50/"))
        expectThat(halconfigBlobs).isEmpty()
    }

    @Test
    fun `publishes files under repository name, not service name (but uses service name in path)`() {

        val serviceName = "front50"
        val repositoryName = "front50-repo"

        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion(serviceName, "1.3.22")
            .write()
        serviceRegistry.add(SpinnakerServiceInfo(serviceName, repositoryName))

        writeRepositoryFile("$repositoryName/halconfig/$repositoryName.yml", "$repositoryName content")
        writeRepositoryFile("$serviceName/halconfig/$serviceName.yml", "$serviceName content")

        versionPublisher.publish(bomPath)

        // the service name is used for the directory, but the filename is the same as that on disk, so in this case is
        // the repository name
        val storedContent = cloudStorage.readUtf8String("$serviceName/1.3.22/$repositoryName.yml")

        expectThat(storedContent).isEqualTo("$repositoryName content")

        val halconfigBlobs = cloudStorage.list(Storage.BlobListOption.prefix("$repositoryName/"))
        expectThat(halconfigBlobs).isEmpty()
    }

    @Test
    fun `publishes multiple files in a single repository`() {
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        writeRepositoryFile("front50/halconfig/config1.yml", "config1 content")
        writeRepositoryFile("front50/halconfig/config2.yml", "config2 content")

        versionPublisher.publish(bomPath)

        val storedContent1 = cloudStorage.readUtf8String("front50/1.3.22/config1.yml")
        expectThat(storedContent1).isEqualTo("config1 content")

        val storedContent2 = cloudStorage.readUtf8String("front50/1.3.22/config2.yml")
        expectThat(storedContent2).isEqualTo("config2 content")
    }

    @Test
    fun `publishes files in multiple repositories`() {
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .withServiceVersion("deck", "9.8")
            .write()
        serviceRegistry.addAll(setOf(SpinnakerServiceInfo("front50"), SpinnakerServiceInfo("deck")))

        writeRepositoryFile("front50/halconfig/front50.yml", "front50 content")
        writeRepositoryFile("deck/halconfig/deck.yml", "deck content")

        versionPublisher.publish(bomPath)

        val storedFront50Content = cloudStorage.readUtf8String("front50/1.3.22/front50.yml")
        expectThat(storedFront50Content).isEqualTo("front50 content")

        val storedDeckContent = cloudStorage.readUtf8String("deck/9.8/deck.yml")
        expectThat(storedDeckContent).isEqualTo("deck content")
    }

    @Test
    fun `publishes a directory of files`() {
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        writeRepositoryFile("front50/halconfig/configdir/file1.yml", "file1 content")
        writeRepositoryFile("front50/halconfig/configdir/file2.yml", "file2 content")
        writeRepositoryFile("front50/halconfig/configdir/subdir/file3.yml", "file3 content")
        writeRepositoryFile("front50/halconfig/configdir/subdir/file4.yml", "file4 content")

        versionPublisher.publish(bomPath)

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
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        val target = writeRepositoryFile("some/other/dir/otherFilename.yml", "symlinked content")
        val halconfigDir = createRepositoryDirectory("front50/halconfig")
        Files.createSymbolicLink(halconfigDir.resolve("myconfig.yml"), target)

        versionPublisher.publish(bomPath)

        val storedContent = cloudStorage.readUtf8String("front50/1.3.22/myconfig.yml")

        expectThat(storedContent).isEqualTo("symlinked content")
    }

    @Test
    fun `publishes a directory of files at symlink`() {
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()
        serviceRegistry.add(SpinnakerServiceInfo("front50"))

        val target = createRepositoryDirectory("some/other/dir/otherConfigDir")
        writeRepositoryFile("some/other/dir/otherConfigDir/file1.yml", "file1 content")
        writeRepositoryFile("some/other/dir/otherConfigDir/file2.yml", "file2 content")
        writeRepositoryFile("some/other/dir/otherConfigDir/subdir/file3.yml", "file3 content")
        writeRepositoryFile("some/other/dir/otherConfigDir/subdir/file4.yml", "file4 content")
        val halconfigDir = createRepositoryDirectory("front50/halconfig")
        Files.createSymbolicLink(halconfigDir.resolve("configdir"), target)

        versionPublisher.publish(bomPath)

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
        val bomPath = createMinimalBomWithVersion("1.2.3")
            .withServiceVersion("front50", "1.3.22")
            .write()
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

        versionPublisher.publish(bomPath)

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

    private fun Bom.withSpinnakerVersion(version: String) = this.copy(version = version)

    private fun Bom.withServiceVersion(service: String, version: String): Bom {
        return this.copy(services = services.plus(service to ServiceInfo(version = version)))
    }

    private fun Bom.write(): Path {
        return filesystem.getPath("/path/to/bom")
            .let { Files.createDirectories(it) }
            .resolve("mybom.yaml")
            .let { Files.write(it, toYaml().toByteArray(Charsets.UTF_8)) }
    }

    private fun writeRepositoryFile(path: String, content: String): Path =
        repositoriesDir.resolve(path)
            .also { Files.createDirectories(it.parent) }
            .also { Files.writeString(it, content) }

    private fun createRepositoryDirectory(path: String): Path =
        repositoriesDir.resolve(path).also { Files.createDirectories(it) }
}
