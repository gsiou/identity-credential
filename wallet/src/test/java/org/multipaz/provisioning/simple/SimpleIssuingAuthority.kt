package org.multipaz.wallet.provisioning.simple

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.RawCbor
import org.multipaz.provisioning.DocumentCondition
import org.multipaz.provisioning.DocumentConfiguration
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialData
import org.multipaz.provisioning.CredentialRequest
import org.multipaz.provisioning.DocumentState
import org.multipaz.provisioning.IssuingAuthority
import org.multipaz.provisioning.Proofing
import org.multipaz.provisioning.Registration
import org.multipaz.provisioning.RequestCredentials
import org.multipaz.provisioning.evidence.EvidenceResponse
import org.multipaz.provisioning.evidence.fromCbor
import org.multipaz.provisioning.evidence.toCbor
import org.multipaz.crypto.EcPublicKey
import org.multipaz.provisioning.CredentialConfiguration
import org.multipaz.provisioning.RegistrationResponse
import org.multipaz.provisioning.IssuingAuthorityNotification
import org.multipaz.provisioning.fromDataItem
import org.multipaz.provisioning.toDataItem
import org.multipaz.storage.StorageEngine
import org.multipaz.util.Logger
import org.multipaz.mrtd.MrtdAccessData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.multipaz.provisioning.IssuingAuthorityConfiguration
import java.lang.UnsupportedOperationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * An simple implementation of an [IssuingAuthority].
 *
 * @param storageEngine the [StorageEngine] to use for persisting issuer state.
 */
abstract class SimpleIssuingAuthority(
    private val storageEngine: StorageEngine,
    private val emitOnStateChanged: suspend (documentId: String) -> Unit
) : IssuingAuthority {

    companion object {
        private const val TAG = "SimpleIssuingAuthority"
    }

    private val internalNotifications = MutableSharedFlow<IssuingAuthorityNotification>()

    override suspend fun collect(collector: FlowCollector<IssuingAuthorityNotification>) {
        internalNotifications.collect(collector)
    }

    // This can be changed to simulate proofing and requesting CPOs being slow.
    protected var delayForProofingAndIssuance: Duration = 1.seconds

    // If issuing authority has NFC card access data (such as PIN or CAN code -
    // probably from already-collected evidence) return it here. This is required
    // to avoid scanning passport/id card MRZ strip with camera.
    abstract fun getMrtdAccessData(
        collectedEvidence: Map<String, EvidenceResponse>): MrtdAccessData?

    abstract fun getProofingGraphRoot(
        registrationResponse: RegistrationResponse
    ): SimpleIssuingAuthorityProofingGraph.Node

    // Collected evidence responses are keyed by graph node ids.
    abstract fun checkEvidence(collectedEvidence: Map<String, EvidenceResponse>): Boolean
    abstract fun generateDocumentConfiguration(collectedEvidence: Map<String, EvidenceResponse>): DocumentConfiguration

    abstract fun createCredentialConfiguration(collectedEvidence: MutableMap<String, EvidenceResponse>): CredentialConfiguration

    abstract fun createPresentationData(presentationFormat: CredentialFormat,
                                        documentConfiguration: DocumentConfiguration,
                                        authenticationKey: EcPublicKey): ByteArray

    abstract fun developerModeRequestUpdate(currentConfiguration: DocumentConfiguration): DocumentConfiguration

    private data class SimpleCredentialRequest(
        val authenticationKey: EcPublicKey,
        val format: CredentialFormat,
        val data: ByteArray,
        val deadline: Instant,
    ) {

        companion object {
            fun fromCbor(encodedData: ByteArray): SimpleCredentialRequest {
                val map = Cbor.decode(encodedData)
                return SimpleCredentialRequest(
                    map["authenticationKey"].asCoseKey.ecPublicKey,
                    CredentialFormat.valueOf(map["format"].asTstr),
                    map["data"].asBstr,
                    Instant.fromEpochMilliseconds(map["deadline"].asNumber)
                )
            }
        }

        fun toCbor(): ByteArray {
            return Cbor.encode(
                buildCborMap {
                    put("authenticationKey", authenticationKey.toCoseKey().toDataItem())
                    put("format", format.name)
                    put("data", data)
                    put("deadline", deadline.toEpochMilliseconds())
                }
            )
        }
    }

    // The document as seen from the issuer's perspective
    private data class IssuerDocument(
        val registrationResponse: RegistrationResponse,
        var proofingDeadline: Instant,
        var state: DocumentCondition,
        var collectedEvidence: MutableMap<String, EvidenceResponse>,
        var documentConfiguration: DocumentConfiguration?,
        var simpleCredentialRequests: MutableList<SimpleCredentialRequest>
    ) {
        companion object {
            fun fromCbor(encodedData: ByteArray): IssuerDocument {
                val map = Cbor.decode(encodedData)

                val registrationResponse = RegistrationResponse.fromDataItem(map["registrationResponse"])

                val stateAsInt = map["state"].asNumber.toInt()
                val state = DocumentCondition.entries.firstOrNull {it.ordinal == stateAsInt}
                    ?: throw IllegalArgumentException("Unknown state with value $stateAsInt")

                val collectedEvidence = mutableMapOf<String, EvidenceResponse>()
                val evidenceMap = map["collectedEvidence"].asMap
                for (evidenceId in evidenceMap.keys) {
                    collectedEvidence[evidenceId.asTstr] =
                        EvidenceResponse.fromCbor(Cbor.encode(evidenceMap[evidenceId]!!))
                }

                val credentialRequests = mutableListOf<SimpleCredentialRequest>()
                for (credentialRequestDataItem in map["credentialRequests"].asArray) {
                    credentialRequests.add(
                        SimpleCredentialRequest.fromCbor(Cbor.encode(credentialRequestDataItem))
                    )
                }

                val documentConfiguration: DocumentConfiguration? =
                    map.getOrNull("documentConfiguration")?.let {
                        DocumentConfiguration.fromDataItem(it)
                    }

                return IssuerDocument(
                    registrationResponse,
                    Instant.fromEpochMilliseconds(map["proofingDeadline"].asNumber),
                    state,
                    collectedEvidence,
                    documentConfiguration,
                    credentialRequests,
                )
            }
        }
        fun toCbor(): ByteArray {
            return Cbor.encode(
                buildCborMap {
                    put("registrationResponse", registrationResponse.toDataItem())
                    put("proofingDeadline", proofingDeadline.toEpochMilliseconds())
                    put("state", state.ordinal.toLong())
                    putCborMap("collectedEvidence") {
                        collectedEvidence.forEach() { evidence ->
                            put(evidence.key, RawCbor(evidence.value.toCbor()))
                        }
                    }
                    putCborArray("credentialRequests") {
                        simpleCredentialRequests.forEach() { cpoRequest ->
                            add(RawCbor(cpoRequest.toCbor()))
                        }
                    }
                    if (documentConfiguration != null) {
                        put("documentConfiguration", documentConfiguration!!.toDataItem())
                    }
                }
            )
        }
    }

    private fun issuerDocumentExists(documentId: String): Boolean {
        val encoded = storageEngine.get(documentId)
        return encoded != null
    }

    private fun loadIssuerDocument(documentId: String): IssuerDocument {
        val encoded = storageEngine.get(documentId)
        if (encoded == null) {
            throw Error("Unknown documentId")
        }
        return IssuerDocument.fromCbor(encoded)
    }

    private fun saveIssuerDocument(documentId: String, state: IssuerDocument) {
        storageEngine.put(documentId, state.toCbor())
    }

    private fun deleteIssuerDocument(documentId: String) {
        storageEngine.delete(documentId)
    }

    override suspend fun getState(documentId: String): DocumentState {
        val now = Clock.System.now()

        if (!issuerDocumentExists(documentId)) {
            return DocumentState(
                now,
                DocumentCondition.NO_SUCH_DOCUMENT,
                0,
                0,
            )
        }

        val issuerDocument = loadIssuerDocument(documentId)

        // Evaluate proofing, if deadline has passed...
        if (issuerDocument.state == DocumentCondition.PROOFING_PROCESSING) {
            if (now >= issuerDocument.proofingDeadline) {
                issuerDocument.state =
                    if (checkEvidence(issuerDocument.collectedEvidence)) {
                        DocumentCondition.CONFIGURATION_AVAILABLE
                    } else {
                        DocumentCondition.PROOFING_FAILED
                    }
                saveIssuerDocument(documentId, issuerDocument)
            }
        }

        // Calculate pending/available depending on deadline
        var numPendingCredentialRequests = 0
        var numAvailableCredentialRequests = 0
        for (cpoRequest in issuerDocument.simpleCredentialRequests) {
            if (now >= cpoRequest.deadline) {
                numAvailableCredentialRequests += 1
            } else {
                numPendingCredentialRequests += 1
            }
        }
        return DocumentState(
            now,
            issuerDocument.state,
            numPendingCredentialRequests,
            numAvailableCredentialRequests
        )
    }


    override suspend fun register(): Registration {
        val now = Clock.System.now()
        val documentId = "Document_${now}_${Random.nextInt()}"
        return SimpleIssuingAuthorityRegistrationFlow(this, documentId)
    }

    override suspend fun completeRegistration(registration: Registration) {
    }

    override suspend fun proof(documentId: String): Proofing {
        val issuerDocument = loadIssuerDocument(documentId)
        return SimpleIssuingAuthorityProofingFlow(
            this,
            documentId,
            getProofingGraphRoot(issuerDocument.registrationResponse)
        )
    }

    override suspend fun completeProof(proofing: Proofing) {
        proofing as SimpleIssuingAuthorityProofingFlow
        setProofingProcessing(proofing.documentId)
    }

    override suspend fun getDocumentConfiguration(documentId: String): DocumentConfiguration {
        val issuerDocument = loadIssuerDocument(documentId)
        check(issuerDocument.state == DocumentCondition.CONFIGURATION_AVAILABLE)
        issuerDocument.state = DocumentCondition.READY
        if (issuerDocument.documentConfiguration == null) {
            issuerDocument.documentConfiguration =
                generateDocumentConfiguration(issuerDocument.collectedEvidence)
        }
        saveIssuerDocument(documentId, issuerDocument)
        return issuerDocument.documentConfiguration!!
    }

    override suspend fun requestCredentials(documentId: String): RequestCredentials {
        val issuerDocument = loadIssuerDocument(documentId)
        check(issuerDocument.state == DocumentCondition.READY)

        val credentialConfiguration = createCredentialConfiguration(issuerDocument.collectedEvidence)
        return SimpleIssuingAuthorityRequestCredentialsFlow(
            this,
            documentId,
            credentialConfiguration)
    }

    override suspend fun completeRequestCredentials(requestCredentials: RequestCredentials) {
    }

    override suspend fun getCredentials(documentId: String): List<CredentialData> {
        val now = Clock.System.now()
        val validFrom = now
        val validUntil = now + 30.days
        val issuerDocument = loadIssuerDocument(documentId)
        val availableCPOs = mutableListOf<CredentialData>()
        val pendingCPOs = mutableListOf<SimpleCredentialRequest>()
        for (cpoRequest in issuerDocument.simpleCredentialRequests) {
            if (now >= cpoRequest.deadline) {
                availableCPOs.add(
                    CredentialData(
                    cpoRequest.authenticationKey,
                    validFrom,
                    validUntil,
                    cpoRequest.format,
                    cpoRequest.data,
                )
                )
            } else {
                pendingCPOs.add(cpoRequest)
            }
        }
        issuerDocument.simpleCredentialRequests = pendingCPOs
        saveIssuerDocument(documentId, issuerDocument)
        return availableCPOs
    }

    fun addDocumentId(documentId: String, response: RegistrationResponse) {
        // Initial state is PROOFING_REQUIRED
        saveIssuerDocument(
            documentId,
            IssuerDocument(
                response,
                Instant.fromEpochMilliseconds(0),
                DocumentCondition.PROOFING_REQUIRED,
                mutableMapOf(),           // collectedEvidence - initially empty
                null,  // no initial document configuration
                mutableListOf()           // cpoRequests - initially empty
            )
        )
    }

    fun setProofingProcessing(documentId: String) {
        val issuerDocument = loadIssuerDocument(documentId)
        issuerDocument.state = DocumentCondition.PROOFING_PROCESSING

        issuerDocument.proofingDeadline = Clock.System.now() + delayForProofingAndIssuance

        saveIssuerDocument(documentId, issuerDocument)
        CoroutineScope(Dispatchers.IO).launch {
            emitOnStateChanged(documentId)
        }
    }

    fun addCollectedEvidence(
        documentId: String, nodeId: String, evidenceResponse: EvidenceResponse
    ) {
        val issuerDocument = loadIssuerDocument(documentId)
        issuerDocument.collectedEvidence[nodeId] = evidenceResponse
        saveIssuerDocument(documentId, issuerDocument)
    }

    fun getMrtdAccessData(documentId: String): MrtdAccessData? {
        val issuerDocument = loadIssuerDocument(documentId)
        return getMrtdAccessData(issuerDocument.collectedEvidence)
    }

    private fun hasCpoRequestForAuthenticationKey(
        issuerDocument: IssuerDocument,
        authenticationKey: EcPublicKey
    ) : Boolean {
        for (cpoRequest in issuerDocument.simpleCredentialRequests) {
            if (cpoRequest.authenticationKey.equals(authenticationKey)) {
                return true
            }
        }
        return false
    }

    fun addCpoRequests(
        documentId: String,
        format: CredentialFormat,
        credentialRequests: List<CredentialRequest>
    ) {
        val now = Clock.System.now()

        val issuerDocument = loadIssuerDocument(documentId)
        for (request in credentialRequests) {
            // Skip if we already have a request for the authentication key
            if (hasCpoRequestForAuthenticationKey(issuerDocument,
                    request.secureAreaBoundKeyAttestation.publicKey)) {
                Logger.d(
                    TAG, "Already has cpoRequest for attestation with key " +
                        "${request.secureAreaBoundKeyAttestation.publicKey}")
                continue
            }
            val authenticationKey = request.secureAreaBoundKeyAttestation.publicKey
            val presentationData = createPresentationData(
                format,
                issuerDocument.documentConfiguration!!,
                authenticationKey
            )
            val simpleCredentialRequest = SimpleCredentialRequest(
                authenticationKey,
                format,
                presentationData,
                now + delayForProofingAndIssuance,
            )
            issuerDocument.simpleCredentialRequests.add(simpleCredentialRequest)
        }
        saveIssuerDocument(documentId, issuerDocument)

        CoroutineScope(Dispatchers.IO).launch {
            emitOnStateChanged(documentId)
        }
    }

    override suspend fun developerModeRequestUpdate(
        documentId: String,
        requestRemoteDeletion: Boolean,
        notifyApplicationOfUpdate: Boolean
    ) {
        if (requestRemoteDeletion) {
            deleteIssuerDocument(documentId)
            if (notifyApplicationOfUpdate) {
                emitOnStateChanged(documentId)
            }
            return
        }

        val document = loadIssuerDocument(documentId)
        document.documentConfiguration = developerModeRequestUpdate(document.documentConfiguration!!)
        document.state = DocumentCondition.CONFIGURATION_AVAILABLE
        document.simpleCredentialRequests.clear()
        saveIssuerDocument(documentId, document)
        if (notifyApplicationOfUpdate) {
            emitOnStateChanged(documentId)
        }
    }
}