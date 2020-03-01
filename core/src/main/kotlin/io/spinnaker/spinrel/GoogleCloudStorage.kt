package io.spinnaker.spinrel

import com.google.api.gax.paging.Page
import com.google.cloud.WriteChannel
import com.google.cloud.storage.Blob
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import dagger.Module
import dagger.Provides
import javax.inject.Inject

class GcsBucket(val name: String) {
    override fun toString() = name
}

class GoogleCloudStorage @Inject constructor(
    private val storage: Storage,
    val bucket: GcsBucket
) {

    fun list(vararg options: Storage.BlobListOption): Page<Blob> = storage.list(bucket.name, *options)

    fun readAllBytes(path: String, vararg options: Storage.BlobSourceOption): ByteArray =
        storage.readAllBytes(bucket.name, path, *options)

    fun readUtf8String(path: String, vararg options: Storage.BlobSourceOption): String =
        String(readAllBytes(path, *options), Charsets.UTF_8)

    fun writer(path: String, populateBlob: (BlobInfo.Builder) -> Unit): WriteChannel =
        BlobInfo.newBuilder(BlobId.of(bucket.name, path))
            .also { blobInfo -> populateBlob(blobInfo) }
            .let { blobInfo -> storage.writer(blobInfo.build()) }
}

@Module
object GoogleCloudStorageModule {
    @Provides
    fun providerStorage(): Storage = StorageOptions.getDefaultInstance().service
}
