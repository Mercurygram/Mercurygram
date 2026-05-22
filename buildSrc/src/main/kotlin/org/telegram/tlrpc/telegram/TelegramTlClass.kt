package org.telegram.tlrpc.telegram

data class TelegramTlClass(
    val constructor: UInt?,

    val packageName: String,
    val fullName: String,
    val name: String,

    val canSerialize: Boolean,
    val canDeserialize: Boolean,
    val canReadResponse: Boolean,

    val canStaticDeserialize: Boolean,
    val staticDeserializeCreations: List<String>,
    // Magics dispatched by this class's static `TLdeserialize`/`fromConstructor`.
    // `dispatchedMagicLiterals` are int-literal `case` labels (hex constants).
    // `dispatchedMagicNames` are parent-qualified class names extracted from
    // `<X>.constructor` field references (covers both `case X.constructor:`
    // dispatch and leaf-class identity checks `X.constructor != constructor`).
    // Both sets are empty for non-dispatcher classes.
    val dispatchedMagicLiterals: Set<UInt>,
    val dispatchedMagicNames: Set<String>
) {
    override fun toString(): String {
        return "$packageName.$fullName #${constructor?.toString(16)?.padStart(8, '0') ?: ""}"
    }
}