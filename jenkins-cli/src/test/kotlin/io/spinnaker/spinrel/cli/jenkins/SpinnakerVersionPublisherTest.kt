package io.spinnaker.spinrel.cli.jenkins

import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.verifyAll
import io.spinnaker.spinrel.ArtifactSources
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.BomStorage
import io.spinnaker.spinrel.GcsBucket
import io.spinnaker.spinrel.GoogleCloudStorage
import io.spinnaker.spinrel.ReleaseInfo
import io.spinnaker.spinrel.ServiceInfo
import io.spinnaker.spinrel.SpinnakerServiceInfo
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import io.spinnaker.spinrel.VersionNumber
import io.spinnaker.spinrel.VersionPublisher
import io.spinnaker.spinrel.VersionsFile
import io.spinnaker.spinrel.VersionsFileStorage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import strikt.api.expectCatching
import strikt.api.expectThat
import strikt.assertions.allBytes
import strikt.assertions.allLines
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import strikt.assertions.isRegularFile
import strikt.assertions.isSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

@ExtendWith(MockKExtension::class)
class SpinnakerVersionPublisherTest {

    private lateinit var spinnakerVersionPublisher: SpinnakerVersionPublisher

    private lateinit var httpClient: OkHttpClient
    private lateinit var mockWebServer: MockWebServer
    private lateinit var bomStorage: BomStorage
    private lateinit var versionsFileStorage: VersionsFileStorage
    private lateinit var serviceRegistry: MutableSet<SpinnakerServiceInfo>
    private lateinit var github: FakeGitHubApi
    private lateinit var changelogRepoOrigin: Path

    @MockK(relaxUnitFun = true)
    private lateinit var versionPublisher: VersionPublisher

    @BeforeEach
    fun setUp() {

        httpClient = OkHttpClient.Builder().build()

        mockWebServer = MockWebServer()
        mockWebServer.start()

        val storage = LocalStorageHelper.getOptions().service
        bomStorage = BomStorage(GoogleCloudStorage(storage, GcsBucket("gcsBucket")))
        versionsFileStorage = VersionsFileStorage(GoogleCloudStorage(storage, GcsBucket("gcsBucket")))
        github = FakeGitHubApi()
        changelogRepoOrigin = Files.createTempDirectory("changelogs-repo-")
        val githubCloner = createChangelogsRepo()

        serviceRegistry = mutableSetOf()
        val spinnakerServices = object : SpinnakerServiceRegistry {
            override val byServiceName: Map<String, SpinnakerServiceInfo>
                get() = serviceRegistry.associateBy { it.serviceName }
        }
        spinnakerVersionPublisher = SpinnakerVersionPublisher(
            httpClient,
            bomStorage,
            versionsFileStorage,
            versionPublisher,
            spinnakerServices,
            github,
            githubCloner
        )
    }

    @AfterEach
    fun tearDown() {
        changelogRepoOrigin?.toFile().deleteRecursively()
        mockWebServer.shutdown()
    }

    @Test
    fun `passes all preconditions`() {

        val (sourceVersion, newRelease) = configureSuccess()

        expectCatching { spinnakerVersionPublisher.publish(sourceVersion, newRelease) }
            .isSuccess()
    }

    @Test
    fun `BOM for destination version already exists`() {

        val (sourceVersion, newRelease) = configureSuccess()
        bomStorage.put(createMinimalBomWithVersion(newRelease.version.toString()))

        expectCatching { spinnakerVersionPublisher.publish(sourceVersion, newRelease) }
            .isFailure()
            .and { get { message!! }.contains("already has a published BOM") }
    }

    @Test
    fun `changelog is missing`() {

        val (sourceVersion, newRelease) = configureSuccess()

        // Remove the success call we just configured and replace it with a 404
        httpClient.newCall(Request.Builder().get().url(newRelease.changelogUrl).build()).execute()
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        expectCatching { spinnakerVersionPublisher.publish(sourceVersion, newRelease) }
            .isFailure()
            .and { get { message!! }.contains("404") }
    }

    @Test
    fun `changelog has error`() {

        val (sourceVersion, newRelease) = configureSuccess()

        // Remove the success call we just configured and replace it with a 500
        httpClient.newCall(Request.Builder().get().url(newRelease.changelogUrl).build()).execute()
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        expectCatching { spinnakerVersionPublisher.publish(sourceVersion, newRelease) }
            .isFailure()
            .and { get { message!! }.contains("500") }
    }

    @Test
    fun `versions file has newer patch version`() {

        var (sourceVersion, newRelease) = configureSuccess()

        newRelease = newRelease.copy(version = VersionNumber.parse("1.13.2"))
        val versionsFile = versionsFileStorage.get().copy(
            releases = listOf(
                createReleaseInfo().copy(version = VersionNumber.parse("1.13.3"))
            )
        )
        versionsFileStorage.put(versionsFile)

        expectCatching { spinnakerVersionPublisher.publish(sourceVersion, newRelease) }
            .isFailure()
            .and { get { message!! }.contains("Version 1.13.3 already exists") }
    }

    @Test
    fun `versions file with newer version on different branch is okay`() {

        var (sourceVersion, newRelease) = configureSuccess()

        newRelease = newRelease.copy(version = VersionNumber.parse("1.13.2"))
        val versionsFile = versionsFileStorage.get().copy(
            releases = listOf(
                createReleaseInfo().copy(version = VersionNumber.parse("1.13.1")),
                createReleaseInfo().copy(version = VersionNumber.parse("1.14.7"))
            )
        )
        versionsFileStorage.put(versionsFile)

        expectCatching { spinnakerVersionPublisher.publish(sourceVersion, newRelease) }
            .isSuccess()
    }

    @Test
    fun `versionPublisher is called`() {

        val (sourceVersion, newRelease) = configureSuccess()

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        verifyAll {
            versionPublisher.publish(bomStorage.get(sourceVersion), newRelease.version.toString())
        }
    }

    @Test
    fun `version is added to versions file`() {

        val (sourceVersion, newRelease) = configureSuccess()
        versionsFileStorage.put(createEmptyVersionsFile())

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        val updatedVersionsFile = versionsFileStorage.get()
        with(updatedVersionsFile) {
            expectThat(releases).containsExactly(newRelease)
            expectThat(latestSpinnaker).isEqualTo(newRelease.version)
        }
    }

    @Test
    fun `new release replaces versions in same branch`() {

        var (sourceVersion, newRelease) = configureSuccess()
        newRelease = newRelease.copy(version = VersionNumber.parse("1.13.2"))
        val existingVersionsFile = versionsFileStorage.get().copy(
            releases = listOf(
                createReleaseInfo().copy(version = VersionNumber.parse("1.12.8")),
                createReleaseInfo().copy(version = VersionNumber.parse("1.13.1")),
                createReleaseInfo().copy(version = VersionNumber.parse("1.14.7"))
            ),
            latestSpinnaker = VersionNumber.parse("1.14.7")
        )
        versionsFileStorage.put(existingVersionsFile)

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        val updatedVersionsFile = versionsFileStorage.get()
        with(updatedVersionsFile) {
            expectThat(releases).containsExactlyInAnyOrder(
                existingVersionsFile.releases[0],
                newRelease,
                existingVersionsFile.releases[2]
            )
            expectThat(latestSpinnaker).isEqualTo(VersionNumber.parse("1.14.7"))
        }
    }

    @Test
    fun `new release overwrites latestSpinnaker`() {

        var (sourceVersion, newRelease) = configureSuccess()
        newRelease = newRelease.copy(version = VersionNumber.parse("1.13.2"))
        val existingVersionsFile = versionsFileStorage.get().copy(
            releases = listOf(
                createReleaseInfo().copy(version = VersionNumber.parse("1.11.6")),
                createReleaseInfo().copy(version = VersionNumber.parse("1.12.9")),
                createReleaseInfo().copy(version = VersionNumber.parse("1.13.1"))
            )
        )
        versionsFileStorage.put(existingVersionsFile)

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        val updatedVersionsFile = versionsFileStorage.get()
        with(updatedVersionsFile) {
            expectThat(releases).containsExactlyInAnyOrder(
                existingVersionsFile.releases[0],
                existingVersionsFile.releases[1],
                newRelease
            )
            expectThat(latestSpinnaker).isEqualTo(VersionNumber.parse("1.13.2"))
        }
    }

    @Test
    fun `tags versions on github`() {

        val (sourceVersion, newRelease) = configureSuccess()
        val bomServices = mapOf(
            "echo" to ServiceInfo("myCommitSha1", "1.2.3-20200529"),
            "rosco" to ServiceInfo("myOtherCommitSha", "99.114.3-20200529")
        )
        serviceRegistry.add(SpinnakerServiceInfo("echo"))
        serviceRegistry.add(SpinnakerServiceInfo("rosco"))
        bomStorage.put(createMinimalBomWithVersion(sourceVersion).copy(services = bomServices))

        github.createRepository("echo")
        github.createRepository("rosco")

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        expectThat(github.getRepository("echo").listTags()).containsExactly("version-1.2.3")
        expectThat(github.getRepository("rosco").listTags()).containsExactly("version-99.114.3")
    }

    @Test
    fun `github tagger ignores services not in registry`() {

        val (sourceVersion, newRelease) = configureSuccess()
        val bomServices = mapOf(
            "echo" to ServiceInfo("myCommitSha1", "1.2.3-20200529"),
            "rosco" to ServiceInfo("myOtherCommitSha", "99.114.3-20200529")
        )
        serviceRegistry.add(SpinnakerServiceInfo("echo"))
        bomStorage.put(createMinimalBomWithVersion(sourceVersion).copy(services = bomServices))

        github.createRepository("echo")

        // `github.getRepository("rosco")` would throw if we try to call it...
        expectCatching {
            spinnakerVersionPublisher.publish(sourceVersion, newRelease)
        }.isSuccess()
        expectThat(github.getRepository("echo").listTags()).containsExactly("version-1.2.3")
    }

    @Test
    fun `github tagger ignores services without commit sha or version`() {

        val (sourceVersion, newRelease) = configureSuccess()
        val bomServices = mapOf(
            "echo" to ServiceInfo("myCommitSha1", "1.2.3-20200529"),
            "orca" to ServiceInfo(null, "8.8.8-888"),
            "rosco" to ServiceInfo("myOtherCommitSha", null)
        )
        serviceRegistry.add(SpinnakerServiceInfo("echo"))
        serviceRegistry.add(SpinnakerServiceInfo("orca"))
        serviceRegistry.add(SpinnakerServiceInfo("rosco"))
        bomStorage.put(createMinimalBomWithVersion(sourceVersion).copy(services = bomServices))

        github.createRepository("echo")

        // `github.getRepository()` would throw if we try to call it...
        expectCatching {
            spinnakerVersionPublisher.publish(sourceVersion, newRelease)
        }.isSuccess()
        expectThat(github.getRepository("echo").listTags()).containsExactly("version-1.2.3")
    }

    @Test
    fun `github tagger ignores services without a dash in the version`() {

        val (sourceVersion, newRelease) = configureSuccess()
        val bomServices = mapOf("echo" to ServiceInfo("myCommitSha1", "1.2.3"))
        serviceRegistry.add(SpinnakerServiceInfo("echo"))
        bomStorage.put(createMinimalBomWithVersion(sourceVersion).copy(services = bomServices))

        // `github.getRepository("echo")` would throw if we try to call it...
        expectCatching {
            spinnakerVersionPublisher.publish(sourceVersion, newRelease)
        }.isSuccess()
    }

    @Test
    fun `github tagger skips tags that already exist`() {

        val (sourceVersion, newRelease) = configureSuccess()
        val bomServices = mapOf("echo" to ServiceInfo("myCommitSha1", "1.2.3-20200529"))
        serviceRegistry.add(SpinnakerServiceInfo("echo"))
        bomStorage.put(createMinimalBomWithVersion(sourceVersion).copy(services = bomServices))

        github.createRepository("echo").createRef("refs/tags/version-1.2.3", "myCommitSha1")

        // `echoRepo.createRef()` would throw if we tried to create the already-existing tag
        expectCatching {
            spinnakerVersionPublisher.publish(sourceVersion, newRelease)
        }.isSuccess()
        expectThat(github.getRepository("echo").listTags()).containsExactly("version-1.2.3")
    }

    @Test
    fun `writes changelog file`() {

        val (sourceVersion, newRelease) = configureSuccess()

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        refreshOriginRepo()
        val changelogFile = changelogRepoOrigin.resolve("_changelogs").resolve("${newRelease.version}-changelog.md")
        expectThat(changelogFile).isRegularFile()
        expectThat(changelogFile).allLines().contains("version: ${newRelease.version}")
    }

    @Test
    fun `deprecates old versions`() {

        var (sourceVersion, newRelease) = configureSuccess()
        newRelease = newRelease.copy(version = VersionNumber.parse("1.2.2"))

        val patchZeroChangelog = writeChangelogFile(
            "1.2.0",
            """
            data
            more data
            tags: i like tags
            the end
            """.trimIndent()
        )
        val patchOneChangelog = writeChangelogFile(
            "1.2.1",
            """
            hello
            tags: tags4eva
            goodbyte
            """.trimIndent()
        )

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        refreshOriginRepo()
        expectThat(patchZeroChangelog).allLines().contains("tags: i like tags deprecated")
        expectThat(patchOneChangelog).allLines().contains("tags: tags4eva deprecated")
    }

    @Test
    fun `doesn't change old changelog file without tags`() {

        var (sourceVersion, newRelease) = configureSuccess()
        newRelease = newRelease.copy(version = VersionNumber.parse("1.2.1"))

        val previousChangelogContents =
            """
            no tags
            are
            here
            """.trimIndent()
        val previousChangelog = writeChangelogFile("1.2.0", previousChangelogContents)

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        refreshOriginRepo()
        expectThat(previousChangelog).allBytes().isEqualTo(previousChangelogContents.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `doesn't change already deprecated changelog file`() {

        var (sourceVersion, newRelease) = configureSuccess()
        newRelease = newRelease.copy(version = VersionNumber.parse("1.2.1"))

        val previousChangelogContents =
            """
            this file is already deprecated
            tags: foo bar deprecated baz
            """.trimIndent()
        val previousChangelog = writeChangelogFile("1.2.0", previousChangelogContents)

        spinnakerVersionPublisher.publish(sourceVersion, newRelease)

        refreshOriginRepo()
        expectThat(previousChangelog).allBytes().isEqualTo(previousChangelogContents.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `ignores missing changelog files`() {

        var (sourceVersion, newRelease) = configureSuccess()
        newRelease = newRelease.copy(version = VersionNumber.parse("1.2.20"))

        expectCatching {
            spinnakerVersionPublisher.publish(sourceVersion, newRelease)
        }.isSuccess()
    }

    data class ConfiguredParams(val sourceVersion: String, val releaseInfo: ReleaseInfo)

    private fun configureSuccess(): ConfiguredParams {

        val sourceVersion = "release-1.11.x-latest-validated"

        versionsFileStorage.put(createEmptyVersionsFile())
        bomStorage.put(createMinimalBomWithVersion(sourceVersion))

        val newRelease = createReleaseInfo().copy(
            version = VersionNumber.parse("1.11.3"),
            changelogUrl = createExistingChangelogUrl()
        )
        return ConfiguredParams(sourceVersion, newRelease)
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

    private fun createEmptyVersionsFile(): VersionsFile {
        return VersionsFile(
            latestHalyard = VersionNumber.parse("0.0.0"),
            latestSpinnaker = VersionNumber.parse("0.0.0")
        )
    }

    private fun createExistingChangelogUrl(): String {
        val url = mockWebServer.url("/spinnaker-release/d020714e9190763f27e35701e14c6bc1").toString()
        mockWebServer.enqueue(MockResponse())
        return url
    }

    private fun createReleaseInfo(): ReleaseInfo = ReleaseInfo(
        version = VersionNumber.parse("0.0.0"),
        alias = "SomeNetflixShow",
        changelogUrl = "https://github.com/foo/bar",
        minimumHalyardVersion = "1.22",
        // serialization only writes ms precision
        lastUpdate = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    )

    private fun createChangelogsRepo(): GitHubCloner {
        val githubCloner = FakeGitHubCloner()
        githubCloner.createRepo(changelogRepoOrigin, "spinnaker.io")
        val changelogdir = Files.createDirectory(changelogRepoOrigin.resolve("_changelogs"))
        // We have to add an empty file to make sure the directory exists.
        Files.createFile(changelogdir.resolve("empty.txt"))
        commitUpdatedChangelogs()
        return githubCloner
    }

    private fun writeChangelogFile(version: String, data: String): Path {
        val changelogPath = changelogRepoOrigin.resolve("_changelogs").resolve("$version-changelog.md")
        Files.write(changelogPath, data.toByteArray(Charsets.UTF_8))
        commitUpdatedChangelogs()
        return changelogPath
    }

    private fun commitUpdatedChangelogs() {
        Git.open(changelogRepoOrigin.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add updated changelogs").call()
        }
    }

    // If changes were pushed here during a test, we need to reset to HEAD to see them in the filesystem
    private fun refreshOriginRepo() {
        Git.open(changelogRepoOrigin.toFile()).use { git ->
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
        }
    }
}
