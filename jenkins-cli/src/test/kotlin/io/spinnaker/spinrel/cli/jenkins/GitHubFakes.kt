package io.spinnaker.spinrel.cli.jenkins

import org.eclipse.jgit.api.Git
import java.io.IOException
import java.nio.file.Path

internal class FakeGitHubApi : GitHubApi {

    val tagsByRepo = mutableMapOf<String, MutableSet<String>>()

    fun createRepository(name: String): GitHubRepo {
        val tags = mutableSetOf<String>()
        tagsByRepo.putIfAbsent(name, tags)
        return FakeGitHubRepo(tags)
    }

    override fun getRepository(name: String): GitHubRepo {
        val tags = tagsByRepo[name] ?: throw IOException("no such repo $name")
        return FakeGitHubRepo(tags)
    }
}

internal class FakeGitHubRepo(private val tags: MutableSet<String>) : GitHubRepo {
    override fun listTags(): Set<String> {
        return tags.toSet()
    }

    override fun createRef(name: String, sha: String) {
        val tag = name.removePrefix("refs/tags/")
        if (tag == name) {
            throw IOException("invalid tag")
        }
        if (tags.contains(tag)) {
            throw IOException("already has tag $tag")
        }
        tags.add(tag)
    }
}

internal class FakeGitHubCloner : GitHubCloner {

    private val repositories = mutableMapOf<String, Path>()

    fun createRepo(path: Path, name: String) {
        Git.init().setDirectory(path.toFile()).call()
        repositories[name] = path
    }

    override fun clone(cloneInto: Path, repoName: String): Git {
        val repoPath = repositories[repoName] ?: throw IllegalStateException()
        return Git.cloneRepository().setURI(repoPath.toString()).setDirectory(cloneInto.toFile()).call()
    }
}
