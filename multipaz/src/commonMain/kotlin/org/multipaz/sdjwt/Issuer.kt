package org.multipaz.sdjwt

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.X509CertChain

/**
 * Information about an issuer.
 *
 * @param iss a URL pointing to the issuer. At that URL there would typically be
 *        metadata about the issuer available, along with currently valid public keys. This
 *        parameter will be copied into the payload of the JWT.
 * @param alg the algorithm (e.g., [Algorithm.ESP256] used by the issuer to sign the SD-JWTs. This
 *        parameter will be copied into the header of the JWT.
 * @param kid a parameter further identifying the key, if necessary (e.g., when the
 *        iss URL points to a file with multiple keys. This parameter will be copied
 *        into the header of the JWT.
 * @param x5c a certificate chain, from the signer of the JWT up to some authority that
 *        the verifier of the JWT will likely trust. Optional.
 */
data class Issuer(
    val iss: String,
    val alg: Algorithm,
    val kid: String? = null,
    val x5c: X509CertChain? = null
) {
    init {
        require(alg.fullySpecified) { "Signing key for issuer must be fully specified" }
    }
}
