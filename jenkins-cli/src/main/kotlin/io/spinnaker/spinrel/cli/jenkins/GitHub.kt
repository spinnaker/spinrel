package io.spinnaker.spinrel.cli.jenkins

import dagger.Binds
import dagger.Module
import dagger.Provides
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Qualifier
import kotlin.annotation.AnnotationTarget.FIELD
import kotlin.annotation.AnnotationTarget.FUNCTION
import kotlin.annotation.AnnotationTarget.PROPERTY_GETTER
import kotlin.annotation.AnnotationTarget.PROPERTY_SETTER
import kotlin.annotation.AnnotationTarget.VALUE_PARAMETER
import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LsRemoteCommand
import org.eclipse.jgit.api.PullCommand
import org.eclipse.jgit.api.PushCommand
import org.eclipse.jgit.api.SubmoduleAddCommand
import org.eclipse.jgit.api.SubmoduleUpdateCommand
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder

// This interface really only exists because the GitHub library we're using has a fluent interface interface that is
// very difficult to mock or fake.
interface GitHubApi {

    fun getRepository(name: String): GitHubRepo
}

interface GitHubRepo {

    fun listTags(): Set<String>

    fun createRef(name: String, sha: String)
}

/**
 * Clones a GitHub repository and returns the JGit interface for interacting with it.
 */
interface GitHubCloner {

    /**
     * Clone a Git a GitHub repository and returns the JGit interface for interacting with it.
     *
     * This repository must be closed, just like a repository from a normal [Git.cloneRepository]
     * call.
     */
    fun clone(cloneInto: Path, repoName: String): Git
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class GitHubToken

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(FIELD, VALUE_PARAMETER, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
annotation class GitHubRepositoryOwner

@Module
internal interface GitHubModule {

    @Binds fun bindsGithub(github: GitHubApiImpl): GitHubApi
    @Binds fun bindsGithubCloner(github: GitHubClonerImpl): GitHubCloner

    companion object {
        @Provides
        fun provideGitHub(@GitHubToken gitHubToken: String): GitHub =
            GitHubBuilder().withOAuthToken(gitHubToken).build()
    }
}

internal class GitHubApiImpl @Inject constructor(
    private val gitHub: GitHub,
    @GitHubRepositoryOwner private val repoOwner: String
) : GitHubApi {

    override fun getRepository(name: String): GitHubRepo {

        val repo = gitHub.getRepository("$repoOwner/$name")

        return object : GitHubRepo {

            override fun listTags(): Set<String> {
                return repo.listTags().map { it.name }.toSet()
            }

            override fun createRef(name: String, sha: String) {
                repo.createRef(name, sha)
            }
        }
    }
}

internal class GitHubClonerImpl @Inject constructor(
    @GitHubRepositoryOwner private val repositoryOwner: String,
    @GitHubToken private val gitHubToken: String
) : GitHubCloner {

    override fun clone(cloneInto: Path, repoName: String): Git {
        val credentialsProvider = UsernamePasswordCredentialsProvider(gitHubToken, "")
        val unwrappedGit = Git.cloneRepository()
            .setURI("https://github.com/$repositoryOwner/$repoName.git")
            .setCredentialsProvider(credentialsProvider)
            .setDirectory(cloneInto.toFile())
            .call()
        return CredentialedGit(unwrappedGit.repository, credentialsProvider)
    }
}

/**
 * A wrapper around the [Git] class that adds credentials to [TransportCommands][org.eclipse.jgit.api.TransportCommand].
 */
private class CredentialedGit(repo: Repository, private val credentialsProvider: CredentialsProvider) : Git(repo) {

    override fun fetch(): FetchCommand = super.fetch().setCredentialsProvider(credentialsProvider)

    override fun lsRemote(): LsRemoteCommand = super.lsRemote().setCredentialsProvider(credentialsProvider)

    override fun pull(): PullCommand = super.pull().setCredentialsProvider(credentialsProvider)

    override fun push(): PushCommand = super.push().setCredentialsProvider(credentialsProvider)

    override fun submoduleAdd(): SubmoduleAddCommand = super.submoduleAdd().setCredentialsProvider(credentialsProvider)

    override fun submoduleUpdate(): SubmoduleUpdateCommand =
        super.submoduleUpdate().setCredentialsProvider(credentialsProvider)
}
