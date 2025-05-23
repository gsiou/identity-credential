package org.multipaz.document

import org.multipaz.cbor.annotation.CborSerializable
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import kotlin.concurrent.Volatile

/**
 * Implementation for [DocumentMetadata] suitable for simple use cases and testing.
 *
 * Generally, applications are encouraged to use implementations of their own.
 */
class SimpleDocumentMetadata private constructor(
    data: ByteString?,
    private val saveFn: suspend (data: ByteString) -> Unit
) : DocumentMetadata {
    @Volatile
    private var data: Data = if (data == null || data.isEmpty()) {
        Data()  // new document or SimpleDocumentMetadata never saved
    } else {
        Data.fromCbor(data.toByteArray())
    }

    override val provisioned get() = data.provisioned
    override val displayName get() = data.displayName
    override val typeDisplayName get() = data.typeDisplayName
    override val cardArt get() = data.cardArt
    override val issuerLogo get() = data.issuerLogo

    override suspend fun documentDeleted() {}

    suspend fun markAsProvisioned() {
        val lastData = data
        val newData = Data(
            provisioned = true,
            displayName = lastData.displayName,
            typeDisplayName = lastData.typeDisplayName,
            cardArt = lastData.cardArt,
            issuerLogo = lastData.issuerLogo,
        )
        data = newData
        saveFn(ByteString(data.toCbor()))
    }

    suspend fun setBasicProperties(
        displayName: String,
        typeDisplayName: String,
        cardArt: ByteString?,
        issuerLogo: ByteString?
    ) {
        val lastData = data
        val newData = Data(
            provisioned = lastData.provisioned,
            displayName = displayName,
            typeDisplayName = typeDisplayName,
            cardArt = cardArt,
            issuerLogo = issuerLogo,
        )
        data = newData
        saveFn(ByteString(data.toCbor()))
    }

    @CborSerializable
    data class Data(
        val provisioned: Boolean = false,
        val displayName: String? = null,
        val typeDisplayName: String? = null,
        val cardArt: ByteString? = null,
        val issuerLogo: ByteString? = null,
    ) {
        companion object
    }

    companion object {
        suspend fun create(
            documentId: String,
            serializedData: ByteString?,
            saveFn: suspend (data: ByteString) -> Unit
        ): SimpleDocumentMetadata {
            return SimpleDocumentMetadata(serializedData, saveFn)
        }
    }
}