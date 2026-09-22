package org.scrib.transcriber

import android.content.Context
import java.io.File

object SherpaModel {

    const val ID = "sherpa-transducer"

    const val DISPLAY_NAME = "Sherpa"
    const val ENGINE_ID = "sherpa-onnx"

    const val ENCODER = "gigaam_v3_e2e_rnnt_encoder_int8.onnx"
    const val DECODER = "gigaam_v3_e2e_rnnt_decoder.onnx"
    const val JOINER = "gigaam_v3_e2e_rnnt_joint.onnx"
    const val TOKENS = "gigaam_v3_e2e_rnnt_tokens.txt"

    data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

    val FILES = listOf(
        ModelFile(ENCODER, 318_995_997L, "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1"),
        ModelFile(DECODER, 4_600_058L, "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
        ModelFile(JOINER, 2_712_896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
        ModelFile(TOKENS, 13_353L, "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d")
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.sizeBytes }

    private const val RELEASE_TAG = "model-gigaam-v3"
    private const val BASE_URL = "https://github.com/amidexe/govorun-lite/releases/download/$RELEASE_TAG"

    fun urlFor(name: String): String = "$BASE_URL/$name"

    fun dir(context: Context): File =
        File(ModelManager.modelsDir(context), ID).also { it.mkdirs() }

    fun isInstalled(context: Context): Boolean {
        val dir = dir(context)
        return FILES.all {
            val f = File(dir, it.name)
            f.exists() && f.length() == it.sizeBytes
        }
    }
}
