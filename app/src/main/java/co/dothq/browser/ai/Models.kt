package co.dothq.browser.ai

/** One downloadable file, pinned to a commit and verified by SHA-256 after download. */
data class ModelFile(val repo: String, val commit: String, val name: String, val bytes: Long, val sha256: String) {
    val url get() = "https://huggingface.co/$repo/resolve/$commit/$name"
}

/**
 * A model Dot can run entirely on the phone. [speed] and [smarts] are 1–5, for the picker.
 * [vision] is an optional projector that lets the model look at screenshots.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val maker: String,
    val tagline: String,
    val params: String,
    val speed: Int,
    val smarts: Int,
    val minRamGb: Int,
    val contextTokens: Int,
    val file: ModelFile,
    val vision: ModelFile? = null,
    val licence: String,
) {
    val totalBytes get() = file.bytes + (vision?.bytes ?: 0)
}

object ModelCatalog {
    private const val Q = "Q4_K_M"

    val ALL = listOf(
        ModelSpec(
            "lfm2.5-350m", "LFM2.5 350M", "Liquid AI",
            "Instant. Great for summaries and quick answers; too small to drive pages reliably.",
            "350M", speed = 5, smarts = 1, minRamGb = 2, contextTokens = 4096,
            file = ModelFile("LiquidAI/LFM2.5-350M-GGUF", "657e078c94084481950a2d555a941481f715536b",
                "LFM2.5-350M-$Q.gguf", 229_312_224, "7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4"),
            licence = "LFM Open License",
        ),
        ModelSpec(
            "qwen3.5-0.8b", "Qwen3.5 0.8B", "Alibaba Qwen",
            "Tiny and quick, and it can see your screen. Handles simple tasks.",
            "0.8B", speed = 5, smarts = 2, minRamGb = 3, contextTokens = 8192,
            file = ModelFile("unsloth/Qwen3.5-0.8B-GGUF", "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
                "Qwen3.5-0.8B-$Q.gguf", 532_517_120, "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"),
            vision = ModelFile("unsloth/Qwen3.5-0.8B-GGUF", "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0",
                "mmproj-F16.gguf", 204_987_232, "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453"),
            licence = "Apache 2.0",
        ),
        ModelSpec(
            "lfm2.5-1.2b", "LFM2.5 1.2B", "Liquid AI",
            "Built for phones: very fast for its size, a capable everyday assistant.",
            "1.2B", speed = 4, smarts = 2, minRamGb = 3, contextTokens = 8192,
            file = ModelFile("LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "8ed288026e23958ad9dfa92d53ed773a8eee7125",
                "LFM2.5-1.2B-Instruct-$Q.gguf", 730_895_168, "b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5"),
            licence = "LFM Open License",
        ),
        ModelSpec(
            "gemma3-1b", "Gemma 3 1B", "Google",
            "Small and friendly, good at writing and summarising.",
            "1B", speed = 4, smarts = 2, minRamGb = 3, contextTokens = 8192,
            file = ModelFile("unsloth/gemma-3-1b-it-GGUF", "f0b45be0aac41bd6a100a4b5734cad5f67255bfb",
                "gemma-3-1b-it-$Q.gguf", 806_058_272, "8270790f3ab69fdfe860b7b64008d9a19986d8df7e407bb018184caa08798ebd"),
            licence = "Gemma Terms of Use",
        ),
        ModelSpec(
            "qwen3.5-2b", "Qwen3.5 2B", "Alibaba Qwen",
            "The sweet spot. Quick, sees your screen, and handles most browsing tasks.",
            "2B", speed = 3, smarts = 3, minRamGb = 4, contextTokens = 8192,
            file = ModelFile("unsloth/Qwen3.5-2B-GGUF", "f6d5376be1edb4d416d56da11e5397a961aca8ae",
                "Qwen3.5-2B-$Q.gguf", 1_280_835_840, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"),
            vision = ModelFile("unsloth/Qwen3.5-2B-GGUF", "f6d5376be1edb4d416d56da11e5397a961aca8ae",
                "mmproj-F16.gguf", 668_227_264, "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7"),
            licence = "Apache 2.0",
        ),
        ModelSpec(
            "smollm3-3b", "SmolLM3 3B", "Hugging Face",
            "Fully open (data and training), solid reasoning and multilingual.",
            "3B", speed = 3, smarts = 3, minRamGb = 6, contextTokens = 8192,
            file = ModelFile("ggml-org/SmolLM3-3B-GGUF", "4965cb60b150737b68a0408c36aeefb65078f894",
                "SmolLM3-$Q.gguf", 1_915_305_312, "8334b850b7bd46238c16b0c550df2138f0889bf433809008cc17a8b05761863e"),
            licence = "Apache 2.0",
        ),
        ModelSpec(
            "phi4-mini", "Phi-4 mini", "Microsoft",
            "Strong at maths and step-by-step reasoning.",
            "3.8B", speed = 2, smarts = 4, minRamGb = 6, contextTokens = 8192,
            file = ModelFile("unsloth/Phi-4-mini-instruct-GGUF", "78eb92a46fc37e6b524df991ed9aca9bc6aa7b80",
                "Phi-4-mini-instruct-$Q.gguf", 2_491_874_272, "88c00229914083cd112853aab84ed51b87bdf6b9ce42f532d8c85c7c63b1730a"),
            licence = "MIT",
        ),
        ModelSpec(
            "qwen3-4b-2507", "Qwen3 4B Instruct", "Alibaba Qwen",
            "The most dependable at following instructions and driving pages step by step.",
            "4B", speed = 2, smarts = 4, minRamGb = 6, contextTokens = 8192,
            file = ModelFile("unsloth/Qwen3-4B-Instruct-2507-GGUF", "a06e946bb6b655725eafa393f4a9745d460374c9",
                "Qwen3-4B-Instruct-2507-$Q.gguf", 2_497_281_120, "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"),
            licence = "Apache 2.0",
        ),
        ModelSpec(
            "qwen3.5-4b", "Qwen3.5 4B", "Alibaba Qwen",
            "Smart and sees your screen. Best results, if your phone has the memory.",
            "4B", speed = 2, smarts = 5, minRamGb = 8, contextTokens = 8192,
            file = ModelFile("unsloth/Qwen3.5-4B-GGUF", "e87f176479d0855a907a41277aca2f8ee7a09523",
                "Qwen3.5-4B-$Q.gguf", 2_740_937_888, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"),
            vision = ModelFile("unsloth/Qwen3.5-4B-GGUF", "e87f176479d0855a907a41277aca2f8ee7a09523",
                "mmproj-F16.gguf", 672_423_616, "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864"),
            licence = "Apache 2.0",
        ),
        ModelSpec(
            "gemma4-e2b", "Gemma 4 E2B", "Google",
            "Google's newest on-device model: thoughtful, multilingual, sees your screen.",
            "5B (2B active)", speed = 2, smarts = 5, minRamGb = 8, contextTokens = 8192,
            file = ModelFile("unsloth/gemma-4-E2B-it-GGUF", "0314792d7f1f7e229411f620751375812bb9faf2",
                "gemma-4-E2B-it-$Q.gguf", 3_106_738_272, "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8"),
            vision = ModelFile("unsloth/gemma-4-E2B-it-GGUF", "0314792d7f1f7e229411f620751375812bb9faf2",
                "mmproj-F16.gguf", 985_654_080, "140be8d7849741f88c50757d529b84373ee8e27052cc2236855b537f4a8215fa"),
            licence = "Apache 2.0",
        ),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    /** The best model this phone can comfortably run. */
    fun recommended(totalRamGb: Double): ModelSpec = when {
        totalRamGb >= 11 -> byId("qwen3.5-4b")!!
        totalRamGb >= 5.5 -> byId("qwen3.5-2b")!!
        totalRamGb >= 3.5 -> byId("lfm2.5-1.2b")!!
        else -> byId("lfm2.5-350m")!!
    }

    fun formatBytes(b: Long): String = when {
        b >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", b / 1e9)
        else -> "${b / 1_000_000} MB"
    }
}
