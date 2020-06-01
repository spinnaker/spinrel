package io.spinnaker.spinrel

import java.nio.ByteBuffer
import javax.inject.Inject

class VersionsFileStorage @Inject constructor(private val storage: GoogleCloudStorage) {

    fun get() = VersionsFile.readFromString(storage.readUtf8String("versions.yml"))

    fun put(versionsFile: VersionsFile) {
        val gcsPath = "versions.yml"
        val writer = storage.writer(gcsPath) { blobInfo -> blobInfo.setContentType("application/x-yaml") }
        writer.use {
            it.write(ByteBuffer.wrap(versionsFile.toYaml().toByteArray(Charsets.UTF_8)))
        }
    }
}
