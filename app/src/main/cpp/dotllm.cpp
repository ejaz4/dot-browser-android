// Dot's on-device language model bridge: llama.cpp behind a small JNI surface.
//
// One Engine per loaded model. All calls for an engine come from a single Kotlin thread,
// except stop(), which only flips an atomic flag.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "DotLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    mtmd_context * vision = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token> cached;   // tokens currently in the KV cache (text-only prompts)
    llama_pos n_past = 0;
    int n_batch = 512;
    std::atomic<bool> stop{false};
    std::vector<std::string> stops;     // extra stop strings from the chat template
};

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

std::string jstring_to_string(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's NewStringUTF wants modified UTF-8, which breaks emoji; hand bytes over instead.
jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// Length of the longest prefix of s that is complete UTF-8.
size_t valid_utf8_prefix(const std::string & s) {
    size_t i = 0, n = s.size(), last_ok = 0;
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
        if (i + len > n) break;
        i += len;
        last_ok = i;
    }
    return last_ok;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, t, big.data(), (int32_t) big.size(), 0, false);
        return big.substr(0, std::max(0, n));
    }
    return std::string(buf, n);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> out(std::max(0, n));
    if (n > 0) llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, true, true);
    return out;
}

void clear_cache(Engine * e) {
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
    e->n_past = 0;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_co_dothq_browser_ai_Llama_nativeInit(JNIEnv *, jclass) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGI("llama.cpp ready: %s", llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_co_dothq_browser_ai_Llama_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_co_dothq_browser_ai_Llama_nativeMediaMarker(JNIEnv * env, jclass) {
    return env->NewStringUTF(mtmd_default_marker());
}

JNIEXPORT jlong JNICALL
Java_co_dothq_browser_ai_Llama_nativeLoad(JNIEnv * env, jclass, jstring jmodel, jstring jmmproj,
                                          jint n_ctx, jint n_threads) {
    std::string path = jstring_to_string(env, jmodel);
    auto * e = new Engine();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // pages stay reclaimable by the OS
    e->model = llama_model_load_from_file(path.c_str(), mp);
    if (!e->model) {
        LOGE("Could not load model %s", path.c_str());
        delete e;
        return 0;
    }
    e->vocab = llama_model_get_vocab(e->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) {
        LOGE("Could not create a context");
        llama_model_free(e->model);
        delete e;
        return 0;
    }
    e->n_batch = (int) llama_n_batch(e->ctx);

    try {
        e->templates = common_chat_templates_init(e->model, "");
    } catch (const std::exception & ex) {
        LOGW("No usable chat template (%s); falling back to ChatML", ex.what());
        e->templates = common_chat_templates_init(e->model, "chatml");
    }

    std::string mmproj = jstring_to_string(env, jmmproj);
    if (!mmproj.empty()) {
        mtmd_context_params vp = mtmd_context_params_default();
        vp.use_gpu = false;
        vp.n_threads = n_threads;
        vp.print_timings = false;
        vp.warmup = false;
        vp.image_max_tokens = 576;  // phones: keep screenshots affordable
        e->vision = mtmd_init_from_file(mmproj.c_str(), e->model, vp);
        if (!e->vision) LOGW("Could not load the vision projector; screen vision disabled");
    }
    LOGI("Loaded %s (ctx %d, %d threads, vision %s)", path.c_str(), n_ctx, n_threads, e->vision ? "on" : "off");
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jboolean JNICALL
Java_co_dothq_browser_ai_Llama_nativeHasVision(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    return e && e->vision && mtmd_support_vision(e->vision);
}

JNIEXPORT jint JNICALL
Java_co_dothq_browser_ai_Llama_nativeContextSize(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    return e ? (jint) llama_n_ctx(e->ctx) : 0;
}

JNIEXPORT jint JNICALL
Java_co_dothq_browser_ai_Llama_nativeCountTokens(JNIEnv * env, jclass, jlong handle, jstring jtext) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return 0;
    std::string text = jstring_to_string(env, jtext);
    return -llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
}

// Renders a conversation with the model's own chat template (Jinja), thinking disabled.
JNIEXPORT jstring JNICALL
Java_co_dothq_browser_ai_Llama_nativeFormat(JNIEnv * env, jclass, jlong handle,
                                            jobjectArray jroles, jobjectArray jcontents) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return env->NewStringUTF("");
    common_chat_templates_inputs in;
    jsize n = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n; i++) {
        common_chat_msg m;
        m.role = jstring_to_string(env, (jstring) env->GetObjectArrayElement(jroles, i));
        m.content = jstring_to_string(env, (jstring) env->GetObjectArrayElement(jcontents, i));
        in.messages.push_back(std::move(m));
    }
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = false;
    std::string prompt;
    try {
        auto params = common_chat_templates_apply(e->templates.get(), in);
        prompt = params.prompt.str();
        e->stops = params.additional_stops;
    } catch (const std::exception & ex) {
        LOGW("Jinja template failed (%s), using the legacy formatter", ex.what());
        in.use_jinja = false;
        try {
            auto params = common_chat_templates_apply(e->templates.get(), in);
            prompt = params.prompt.str();
        } catch (...) {
            prompt.clear();
            for (auto & m : in.messages) prompt += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
            prompt += "<|im_start|>assistant\n";
        }
    }
    return env->NewStringUTF(prompt.c_str());
}

JNIEXPORT void JNICALL
Java_co_dothq_browser_ai_Llama_nativeStop(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e) e->stop.store(true);
}

/*
 * Generates a reply to [prompt]. Tokens stream to callback.onToken(byte[]) (UTF-8); returning
 * false stops early. If [rgba] is given, it's an image (width x height) placed where the media
 * marker appears in the prompt. Returns tokens generated, or a negative error.
 */
JNIEXPORT jint JNICALL
Java_co_dothq_browser_ai_Llama_nativeGenerate(JNIEnv * env, jclass, jlong handle, jstring jprompt,
                                              jintArray jpixels, jint width, jint height,
                                              jint max_tokens, jfloat temperature, jobject callback) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;
    e->stop.store(false);
    std::string prompt = jstring_to_string(env, jprompt);
    const int n_ctx = (int) llama_n_ctx(e->ctx);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)Z");
    jmethodID onProgress = env->GetMethodID(cbClass, "onPromptProgress", "(F)V");

    const bool with_image = jpixels && e->vision;
    if (with_image) {
        // ---- Image + text: evaluate through mtmd (no cache reuse) ----
        clear_cache(e);
        jsize count = env->GetArrayLength(jpixels);
        std::vector<jint> argb(count);
        env->GetIntArrayRegion(jpixels, 0, count, argb.data());
        std::vector<unsigned char> rgb((size_t) width * height * 3);
        for (size_t i = 0; i < (size_t) width * height; i++) {
            uint32_t p = (uint32_t) argb[i];
            rgb[i * 3] = (p >> 16) & 0xFF;
            rgb[i * 3 + 1] = (p >> 8) & 0xFF;
            rgb[i * 3 + 2] = p & 0xFF;
        }
        mtmd_bitmap * bitmap = mtmd_bitmap_init((uint32_t) width, (uint32_t) height, rgb.data());
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        const mtmd_bitmap * bitmaps[] = {bitmap};
        int32_t res = mtmd_tokenize(e->vision, chunks, &text, bitmaps, 1);
        mtmd_bitmap_free(bitmap);
        if (res != 0) {
            mtmd_input_chunks_free(chunks);
            LOGE("mtmd_tokenize failed: %d", res);
            return -3;
        }
        if ((int) mtmd_helper_get_n_pos(chunks) + max_tokens >= n_ctx) {
            mtmd_input_chunks_free(chunks);
            return -2;
        }
        if (onProgress) env->CallVoidMethod(callback, onProgress, 0.1f);
        llama_pos new_past = 0;
        res = mtmd_helper_eval_chunks(e->vision, e->ctx, chunks, 0, 0, e->n_batch, true, &new_past);
        mtmd_input_chunks_free(chunks);
        if (res != 0) {
            clear_cache(e);
            return -4;
        }
        e->n_past = new_past;
        e->cached.clear();  // positions don't map to plain tokens; next call starts fresh
    } else {
        // ---- Text only: reuse whatever prefix is already in the cache ----
        std::vector<llama_token> tokens = tokenize(e->vocab, prompt);
        if ((int) tokens.size() + max_tokens >= n_ctx) return -2;
        size_t common = 0;
        while (common < e->cached.size() && common < tokens.size() && e->cached[common] == tokens[common]) common++;
        if (common == tokens.size() && common > 0) common--;  // always re-decode the last token for fresh logits
        if (common < e->cached.size()) {
            // Hybrid / recurrent models can't drop a partial sequence: start over.
            if (!llama_memory_seq_rm(llama_get_memory(e->ctx), 0, (llama_pos) common, -1)) {
                clear_cache(e);
                common = 0;
            }
        }
        e->cached.resize(common);
        e->n_past = (llama_pos) common;
        size_t total = tokens.size() - common;
        for (size_t i = common; i < tokens.size(); i += e->n_batch) {
            if (e->stop.load()) { clear_cache(e); return 0; }
            int32_t n = (int32_t) std::min<size_t>(e->n_batch, tokens.size() - i);
            llama_batch batch = llama_batch_get_one(tokens.data() + i, n);
            if (llama_decode(e->ctx, batch) != 0) {
                clear_cache(e);
                return -4;
            }
            e->n_past += n;
            e->cached.insert(e->cached.end(), tokens.begin() + i, tokens.begin() + i + n);
            if (onProgress && total > 0) env->CallVoidMethod(callback, onProgress, (float) (i + n - common) / (float) total);
        }
    }

    // ---- Sampling ----
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.08f, 0.0f, 0.0f));
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.92f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;   // bytes not yet sent (incomplete UTF-8)
    std::string tail;      // recent text, for stop strings
    int generated = 0;
    for (; generated < max_tokens; generated++) {
        if (e->stop.load()) break;
        if (e->n_past + 1 >= n_ctx) break;
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        llama_sampler_accept(smpl, tok);

        std::string piece = token_to_piece(e->vocab, tok);
        pending += piece;
        tail += piece;
        if (tail.size() > 64) tail.erase(0, tail.size() - 64);

        bool hit_stop = false;
        for (auto & s : e->stops) {
            if (!s.empty() && tail.find(s) != std::string::npos) { hit_stop = true; break; }
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, batch) != 0) break;
        e->n_past += 1;
        if (!with_image) e->cached.push_back(tok);

        if (hit_stop) break;
        size_t ok = valid_utf8_prefix(pending);
        if (ok > 0) {
            jbyteArray bytes = to_bytes(env, pending.substr(0, ok));
            jboolean keep = env->CallBooleanMethod(callback, onToken, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ok);
            if (!keep) break;
        }
    }
    if (!pending.empty()) {
        jbyteArray bytes = to_bytes(env, pending);
        env->CallBooleanMethod(callback, onToken, bytes);
        env->DeleteLocalRef(bytes);
    }
    llama_sampler_free(smpl);
    return generated;
}

JNIEXPORT void JNICALL
Java_co_dothq_browser_ai_Llama_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    if (e->vision) mtmd_free(e->vision);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // extern "C"
