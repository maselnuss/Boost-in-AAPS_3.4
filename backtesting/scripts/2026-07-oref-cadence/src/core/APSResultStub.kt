package app.aaps.core.interfaces.aps

/**
 * oref references APSResult only for the algorithm tag it stamps on its result. The full type
 * carries Android and JSON dependencies that play no part in the dose calculation, so only the
 * enum is reproduced here.
 */
object APSResult {
    enum class Algorithm { SMB, AMA, AUTO_ISF, BOOST, BOOST_V2, BOOST_V3, BOOST_V5, NONE, UNKNOWN }
}
