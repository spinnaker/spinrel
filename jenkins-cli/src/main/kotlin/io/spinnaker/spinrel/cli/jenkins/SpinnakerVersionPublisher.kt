package io.spinnaker.spinrel.cli.jenkins

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.google.cloud.storage.StorageException
import dagger.BindsInstance
import dagger.Subcomponent
import io.spinnaker.spinrel.Bom
import io.spinnaker.spinrel.BomStorage
import io.spinnaker.spinrel.ReleaseInfo
import io.spinnaker.spinrel.SpinnakerServiceRegistry
import io.spinnaker.spinrel.VersionNumber
import io.spinnaker.spinrel.VersionPublisher
import io.spinnaker.spinrel.VersionsFile
import io.spinnaker.spinrel.VersionsFileStorage
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Creates a released version of Spinnaker.
 *
 * This command takes an existing BOM and:
 * * publishes the BOM to the `halconfig` bucket
 * * tags the appropriate containers with `spinnaker-1.2.3`
 * * updates the `versions.yml` file in the `halconfig` bucket
 * * tags the service versions on GitHub
 * * updates the `_changelogs` directory of the `spinnaker.io` repository
 */
class SpinnakerVersionPublisher @Inject constructor(
    private val httpClient: OkHttpClient,
    private val bomStorage: BomStorage,
    private val versionsFileStorage: VersionsFileStorage,
    private val versionPublisher: VersionPublisher,
    private val serviceRegistry: SpinnakerServiceRegistry,
    private val github: GitHubApi,
    private val gitHubCloner: GitHubCloner
) {

    private val logger = KotlinLogging.logger {}

    fun publish(sourceVersion: String, releaseInfo: ReleaseInfo) {

        val destinationVersion = releaseInfo.version

        logger.info { "Verifying that BOM version $destinationVersion doesn't already exist." }
        checkBomDoesNotExist(destinationVersion)

        logger.info { "Loading versions.yml file." }
        val versionsFile = versionsFileStorage.get()

        checkNoNewerVersionsExistOnBranch(versionsFile, destinationVersion)

        logger.info { "Making sure release changelog exists" }
        ensureChangelogExists(releaseInfo)

        logger.info { "Retrieving BOM for version $sourceVersion" }
        val bom = bomStorage.get(sourceVersion)

        logger.info { "Uploading BOM for $destinationVersion and tagging containers" }
        versionPublisher.publish(bom, destinationVersion.toString())

        logger.info { "Uploading updated versions.yml file" }
        val updatedVersionsFile = updateVersionsFile(versionsFile, releaseInfo)
        versionsFileStorage.put(updatedVersionsFile)

        logger.info { "Tagging service versions in Github" }
        tagVersionsOnGitHub(bom)

        logger.info { "Publishing the changelog" }
        publishChangelog(releaseInfo)
    }

    private fun checkBomDoesNotExist(version: VersionNumber) {
        try {
            bomStorage.get(version.toString())
            throw IllegalArgumentException("Spinnaker version $version already has a published BOM.")
        } catch (e: StorageException) {
            if (e.code != 404) {
                throw e
            }
        }
    }

    private fun ensureChangelogExists(releaseInfo: ReleaseInfo) {
        val request = Request.Builder().get().url(releaseInfo.changelogUrl).build()
        val response = httpClient.newCall(request).execute()
        if (response.code != 200) {
            throw IllegalArgumentException("Error retrieving changelog: $response")
        }
    }

    private fun updateVersionsFile(versionsFile: VersionsFile, releaseInfo: ReleaseInfo): VersionsFile {

        val destinationVersion = releaseInfo.version
        val updatedReleases = (
            versionsFile.releases
                .filter { !it.version.fromSameReleaseBranchAs(destinationVersion) } +
                releaseInfo
            )
        var updatedVersionsFile = versionsFile.copy(releases = updatedReleases)
        if (updatedVersionsFile.releases.map { it.version }.maxOrNull() == destinationVersion) {
            updatedVersionsFile = updatedVersionsFile.copy(latestSpinnaker = destinationVersion)
        }
        return updatedVersionsFile
    }

    private fun checkNoNewerVersionsExistOnBranch(
        versionsFile: VersionsFile,
        destinationVersion: VersionNumber
    ) {
        val sameBranchVersions = versionsFile.releases
            .map { it.version }
            .filter { it.fromSameReleaseBranchAs(destinationVersion) }
        if (sameBranchVersions.isNotEmpty() && destinationVersion <= sameBranchVersions.maxOrNull()!!) {
            throw IllegalArgumentException(
                "Version ${sameBranchVersions.maxOrNull()} already exists and is greater than " +
                    "destination version $destinationVersion"
            )
        }
    }

    private fun tagVersionsOnGitHub(bom: Bom) {
        bom.services.filter { serviceRegistry.byServiceName.containsKey(it.key) }
            .map { serviceRegistry.byServiceName[it.key]!!.repositoryName to it.value }
            .forEach { (repoName, versionInfo) ->
                val fullVersion = versionInfo.version
                val commit = versionInfo.commit
                if (commit == null || fullVersion == null || !fullVersion.contains('-')) {
                    return@forEach
                }
                val semver = fullVersion.subSequence(0, fullVersion.indexOf('-'))
                val tag = "version-$semver"
                val refId = "refs/tags/$tag"
                val repo = github.getRepository(repoName)
                if (repo.listTags().contains(tag)) {
                    return@forEach
                }
                logger.info { "Tagging $tag in repo $repoName" }
                repo.createRef(refId, commit)
            }
    }

    private fun publishChangelog(releaseInfo: ReleaseInfo) {
        val version = releaseInfo.version
        Files.createTempDirectory("spinrel-spinnaker.io-clone-").deleteAfterUse { repoDir ->
            repoDir.toFile().deleteRecursively()
            val changelogsDir = repoDir.resolve("_changelogs")
            logger.info { "Cloning spinnaker.io repository" }
            gitHubCloner.clone(repoDir, "spinnaker.io").use { git ->
                val newChangelog = changelogsDir.resolve("$version-changelog.md")
                Files.write(newChangelog, getChangelogFileContents(releaseInfo))
                deprecateOldVersions(changelogsDir, version)
                git.add().addFilepattern(".").call()
                git.commit().setMessage("doc(changelog): Spinnaker Version $version").call()
                logger.info { "Pushing changes to github" }
                git.push().call()
            }
        }
    }

    private fun getChangelogFileContents(releaseInfo: ReleaseInfo): ByteArray {
        val version = releaseInfo.version
        val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss Z").withZone(ZoneOffset.UTC)
        val timestamp = formatter.format(releaseInfo.lastUpdate)
        var contents =
            """
          ---
          title: Version ${version.major}.${version.minor}
          changelog_title: Version $version
          date: $timestamp
          tags: changelogs ${version.major}.${version.minor}
          version: $version
          ---

            """.trimIndent()
        for (patch in version.patch downTo 0) {
            val patchVersion = VersionNumber(version.major, version.minor, patch)
            contents += "<script src=\"${releaseInfo.changelogUrl}.js?file=$patchVersion.md\"></script>\n"
        }
        return contents.toByteArray(Charsets.UTF_8)
    }

    private fun deprecateOldVersions(changelogsDir: Path, version: VersionNumber) {
        if (version.patch == 0) {
            return
        }
        for (patch in version.patch - 1 downTo 0) {
            val oldVersion = VersionNumber(version.major, version.minor, patch)
            val oldChangelog = changelogsDir.resolve("$oldVersion-changelog.md")
            if (Files.isRegularFile(oldChangelog)) {
                val lines = Files.readAllLines(oldChangelog)
                val updatedLines = lines.map { line ->
                    if (line.startsWith("tags: ") && !line.contains("deprecated")) {
                        return@map "$line deprecated"
                    } else {
                        return@map line
                    }
                }.toList()
                if (lines != updatedLines) {
                    Files.write(oldChangelog, updatedLines)
                }
            }
        }
    }
}

private fun <R> Path.deleteAfterUse(block: (Path) -> R): R {
    try {
        return block(this)
    } finally {
        this.toFile().deleteRecursively()
    }
}

private fun VersionNumber.fromSameReleaseBranchAs(other: VersionNumber): Boolean {
    return this.major == other.major && this.minor == other.minor
}

@Subcomponent(modules = [GitHubModule::class])
interface SpinnakerVersionPublisherComponent {
    fun spinnakerVersionPublisher(): SpinnakerVersionPublisher

    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance @GitHubToken gitHubToken: String,
            @BindsInstance @GitHubRepositoryOwner gitHubRepoOwner: String
        ): SpinnakerVersionPublisherComponent
    }
}

class PublishSpinnakerCommand :
    CliktCommand(name = "publish_spinnaker", help = "publish an official Spinnaker release") {

    private val sourceVersion by option(
        "--source-version",
        help = "the existing version that will be published as --destination-version"
    ).required()
    private val destinationVersion by option(
        "--destination-version",
        help = "the version that will be published"
    )
        .convert { VersionNumber.parse(it) }
        .required()
    private val minimumHalyardVersion by option(
        "--minimum-halyard-version",
        help = "the minimum version of Halyard for this release"
    ).required()
    private val changelogUrl by option(
        "--changelog-url",
        help = "the path to the GitHub gist changelog for this release"
    ).required()
    private val releaseAlias by option(
        "--release-alias",
        help = "the alias for the release"
    ).required()
    private val gitHubRepositoryOwner by option(
        "--github-repository-owner",
        help = "the user or organization that owns the github repositories"
    ).default("spinnaker")

    private val component by requireObject<MainComponent>()

    override fun run() {
        val githubToken = System.getenv("GITHUB_TOKEN")
            ?: throw UsageError("You must set a GitHub token in the GITHUB_TOKEN environment variable")
        val releaseInfo =
            ReleaseInfo(destinationVersion, releaseAlias, changelogUrl, minimumHalyardVersion, Instant.now())
        val spinnakerVersionPublisher = component.spinnakerVersionPublisherComponent()
            .create(githubToken, gitHubRepositoryOwner)
            .spinnakerVersionPublisher()

        spinnakerVersionPublisher.publish(sourceVersion, releaseInfo)
    }
}
