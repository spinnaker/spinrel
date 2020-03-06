package io.spinnaker.spinrel

import java.nio.ByteBuffer
import javax.inject.Inject

class BomStorage @Inject constructor(private val storage: GoogleCloudStorage) {

    fun get(version: String) = Bom.readFromString(storage.readUtf8String("bom/$version.yml"))

    fun put(bom: Bom) {
        val gcsPath = "bom/${bom.version}.yml"
        val writer = storage.writer(gcsPath) { blobInfo -> blobInfo.setContentType("application/x-yaml") }
        writer.use {
            it.write(ByteBuffer.wrap(bom.toYaml().toByteArray(Charsets.UTF_8)))
        }
    }
}
