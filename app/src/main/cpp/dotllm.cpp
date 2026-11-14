// Dot's on-device language model bridge: llama.cpp behind a small JNI surface.
//
// One Engine per loaded model. All calls for an engine come from a single Kotlin thread,
// except stop(), which only flips an atomic flag.
//
// Two ways to generate:
//  - nativeGenerate: a pre-rendered prompt in, plain text out (summaries, legacy agent).
//  - nativeChat: OpenAI-style messages and tools in. The model's own chat template renders
//    them, a grammar keeps tool calls well-formed, and its own format is parsed back into
//    content and tool calls.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <functional>
#include <set>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
#include "sampling.h"
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

// Java strings arrive as modified UTF-8, which mangles emoji; take raw UTF-8 bytes instead.
std::string jbytes_to_string(JNIEnv * env, jbyteArray b) {
    if (!b) return {};
    jsize n = env->GetArrayLength(b);
    std::string out((size_t) n, '\0');
    env->GetByteArrayRegion(b, 0, n, reinterpret_cast<jbyte *>(out.data()));
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

std::string token_to_piece(const llama_vocab * vocab, llama_token t, bool special) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, special);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab, t, big.data(), (int32_t) big.size(), 0, special);
        return big.substr(0, std::max(0, n));
    }
    return std::string(buf, n);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special, bool parse_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, parse_special);
    std::vector<llama_token> out(std::max(0, n));
    if (n > 0) llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data(), n, add_special, parse_special);
    return out;
}

// Template text may contain control tokens; text that came from users or web pages may not.
// Tokenizing those parts without special-token parsing means a page can't forge a turn.
std::vector<llama_token> tokenize_prompt(const llama_vocab * vocab, const common_chat_prompt & prompt) {
    std::vector<llama_token> out;
    bool first = true;
    for (const auto & part : prompt.parts) {
        if (part.text.empty()) continue;
        auto t = tokenize(vocab, part.text, first, !part.is_input);
        out.insert(out.end(), t.begin(), t.end());
        first = false;
    }
    return out;
}

void clear_cache(Engine * e) {
    llama_memory_clear(llama_get_memory(e->ctx), true);
    e->cached.clear();
    e->n_past = 0;
}

common_params_sampling base_sampling(float temperature) {
    common_params_sampling sp;
    sp.temp = temperature <= 0.01f ? 0.0f : temperature;
    sp.top_k = 40;
    sp.top_p = 0.92f;
    sp.min_p = 0.05f;
    sp.penalty_last_n = 64;
    sp.penalty_repeat = 1.08f;
    sp.no_perf = true;
    return sp;
}

/*
 * Puts the prompt in the KV cache: text reuses whatever prefix is already cached; an image goes
 * through mtmd (placed where the media marker appears in [text]). Returns 0 or a negative error.
 */
int eval_prompt(JNIEnv * env, Engine * e, const std::vector<llama_token> & tokens, const std::string & text,
                jintArray jpixels, jint width, jint height, int max_tokens, jobject callback, jmethodID onProgress,
                bool & with_image) {
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    with_image = jpixels && e->vision;
    if (with_image) {
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
        mtmd_input_text in{text.c_str(), text.size(), true, true};
        const mtmd_bitmap * bitmaps[] = {bitmap};
        int32_t res = mtmd_tokenize(e->vision, chunks, &in, bitmaps, 1);
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
        return 0;
    }

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
        if (e->stop.load()) { clear_cache(e); return 1; }
        int32_t n = (int32_t) std::min<size_t>(e->n_batch, tokens.size() - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data()) + i, n);
        if (llama_decode(e->ctx, batch) != 0) {
            clear_cache(e);
            return -4;
        }
        e->n_past += n;
        e->cached.insert(e->cached.end(), tokens.begin() + i, tokens.begin() + i + n);
        if (onProgress && total > 0) env->CallVoidMethod(callback, onProgress, (float) (i + n - common) / (float) total);
    }
    return 0;
}

/*
 * Samples up to [max_tokens], handing each piece to [on_piece] (return false to stop). Pieces of
 * [special_ok] tokens (a tool-call tag, say) are rendered as text so the parser can see them.
 */
int sample_loop(Engine * e, common_sampler * smpl, int max_tokens, bool with_image,
                const std::set<llama_token> & special_ok, const std::vector<std::string> & stops,
                const std::function<bool(const std::string &, llama_token)> & on_piece) {
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    std::string tail;  // recent text, for stop strings
    int generated = 0;
    for (; generated < max_tokens; generated++) {
        if (e->stop.load()) break;
        if (e->n_past + 1 >= n_ctx) break;
        llama_token tok = common_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;
        common_sampler_accept(smpl, tok, true);

        std::string piece = token_to_piece(e->vocab, tok, special_ok.count(tok) > 0);
        tail += piece;
        if (tail.size() > 64) tail.erase(0, tail.size() - 64);
        bool hit_stop = false;
        for (auto & s : stops) {
            if (!s.empty() && tail.find(s) != std::string::npos) { hit_stop = true; break; }
        }

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(e->ctx, batch) != 0) break;
        e->n_past += 1;
        if (!with_image) e->cached.push_back(tok);

        if (hit_stop) break;
        if (!on_piece(piece, tok)) { generated++; break; }
    }
    return generated;
}

common_chat_templates_inputs chat_inputs(const std::string & messages, const std::string & tools, int tool_choice) {
    common_chat_templates_inputs in;
    in.messages = common_chat_msgs_parse_oaicompat(common_json::parse(messages));
    if (!tools.empty()) in.tools = common_chat_tools_parse_oaicompat(common_json::parse(tools));
    in.tool_choice = tool_choice == 1 ? COMMON_CHAT_TOOL_CHOICE_REQUIRED
                   : tool_choice == 2 ? COMMON_CHAT_TOOL_CHOICE_NONE
                   : COMMON_CHAT_TOOL_CHOICE_AUTO;
    in.parallel_tool_calls = false;
    in.add_generation_prompt = true;
    in.use_jinja = true;
    in.enable_thinking = false;
    in.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
    return in;
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

/*
 * The tool-call format this model's template uses, e.g. "peg-native", or "" when the template
 * can't describe tools to the model (then the caller falls back to prompting for them).
 */
JNIEXPORT jstring JNICALL
Java_co_dothq_browser_ai_Llama_nativeToolFormat(JNIEnv * env, jclass, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return env->NewStringUTF("");
    try {
        auto in = chat_inputs(
            R"([{"role":"user","content":"What's the weather?"}])",
            R"([{"type":"function","function":{"name":"search","description":"Search the web.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}])",
            0);
        auto params = common_chat_templates_apply(e->templates.get(), in);
        if (params.format == COMMON_CHAT_FORMAT_CONTENT_ONLY) return env->NewStringUTF("");
        // A template that ignores tools won't mention the tool we passed.
        if (params.prompt.str().find("search") == std::string::npos) return env->NewStringUTF("");
        return env->NewStringUTF(common_chat_format_name(params.format));
    } catch (const std::exception & ex) {
        LOGW("Template can't do native tool calls: %s", ex.what());
        return env->NewStringUTF("");
    }
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

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)Z");
    jmethodID onProgress = env->GetMethodID(cbClass, "onPromptProgress", "(F)V");

    bool with_image = false;
    std::vector<llama_token> tokens;
    if (!(jpixels && e->vision)) tokens = tokenize(e->vocab, prompt, true, true);
    int res = eval_prompt(env, e, tokens, prompt, jpixels, width, height, max_tokens, callback, onProgress, with_image);
    if (res < 0) return res;
    if (res > 0) return 0;  // stopped

    auto sp = base_sampling(temperature);
    common_sampler * smpl = common_sampler_init(e->model, sp);
    if (!smpl) return -4;

    std::string pending;  // bytes not yet sent (incomplete UTF-8)
    int generated = sample_loop(e, smpl, max_tokens, with_image, {}, e->stops,
        [&](const std::string & piece, llama_token) {
            pending += piece;
            size_t ok = valid_utf8_prefix(pending);
            if (ok == 0) return true;
            jbyteArray bytes = to_bytes(env, pending.substr(0, ok));
            jboolean keep = env->CallBooleanMethod(callback, onToken, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ok);
            return (bool) keep;
        });
    if (!pending.empty()) {
        jbyteArray bytes = to_bytes(env, pending);
        env->CallBooleanMethod(callback, onToken, bytes);
        env->DeleteLocalRef(bytes);
    }
    common_sampler_free(smpl);
    return generated;
}

/*
 * A chat turn with native tool calling. [messages] and [tools] are OpenAI-style JSON (UTF-8).
 * tool_choice: 0 auto, 1 required, 2 none. While it runs, callback.onContent(byte[]) gets the
 * reply text so far (tool-call syntax already removed); at the end callback.onMessage(byte[])
 * gets {"format", "message": {content, tool_calls…}, "raw"}. Returns tokens generated, or a
 * negative error (-5: this template can't do tool calls).
 */
JNIEXPORT jint JNICALL
Java_co_dothq_browser_ai_Llama_nativeChat(JNIEnv * env, jclass, jlong handle, jbyteArray jmessages,
                                          jbyteArray jtools, jint tool_choice, jintArray jpixels,
                                          jint width, jint height, jint max_tokens, jfloat temperature,
                                          jobject callback) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;
    e->stop.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onContent = env->GetMethodID(cbClass, "onContent", "([B)Z");
    jmethodID onProgress = env->GetMethodID(cbClass, "onPromptProgress", "(F)V");
    jmethodID onMessage = env->GetMethodID(cbClass, "onMessage", "([B)V");

    common_chat_session session;
    try {
        auto in = chat_inputs(jbytes_to_string(env, jmessages), jbytes_to_string(env, jtools), tool_choice);
        session = common_chat_session(e->templates.get(), e->vocab, in);
    } catch (const std::exception & ex) {
        LOGW("Native tool calling unavailable: %s", ex.what());
        return -5;
    }

    bool with_image = false;
    std::vector<llama_token> tokens;
    std::string text;
    if (jpixels && e->vision) text = session.prompt().str();
    else tokens = tokenize_prompt(e->vocab, session.prompt());
    int res = eval_prompt(env, e, tokens, text, jpixels, width, height, max_tokens, callback, onProgress, with_image);
    if (res < 0) return res;
    if (res > 0) return 0;  // stopped

    auto sp = base_sampling(temperature);
    session.apply_sampling(sp);
    common_sampler * smpl = nullptr;
    try {
        smpl = common_sampler_init(e->model, sp);
    } catch (const std::exception & ex) {
        LOGW("Tool-call grammar rejected (%s); sampling without it", ex.what());
    }
    if (!smpl) {
        sp.grammar = {};
        sp.grammar_triggers.clear();
        smpl = common_sampler_init(e->model, sp);
    }
    if (!smpl) return -4;

    std::string raw;
    std::string sent;
    int generated = sample_loop(e, smpl, max_tokens, with_image, sp.preserved_tokens, session.additional_stops(),
        [&](const std::string & piece, llama_token tok) {
            raw += piece;
            common_chat_input chunk;
            chunk.append(piece, tok);
            std::string content;
            try {
                content = session.feed(chunk).content;
            } catch (const std::exception &) {
                return true;  // a half-written tool call can fail to parse; keep going
            }
            content.resize(valid_utf8_prefix(content));
            if (content == sent) return true;
            sent = content;
            jbyteArray bytes = to_bytes(env, content);
            jboolean keep = env->CallBooleanMethod(callback, onContent, bytes);
            env->DeleteLocalRef(bytes);
            return (bool) keep;
        });
    common_sampler_free(smpl);

    common_json out = common_json::object();
    out["format"] = std::string(common_chat_format_name(session.format()));
    out["raw"] = raw;
    try {
        out["message"] = session.finish().to_json_oaicompat();
    } catch (const std::exception & ex) {
        LOGW("Couldn't parse the reply (%s)", ex.what());
        common_chat_msg m;
        m.role = "assistant";
        m.content = raw;
        out["message"] = m.to_json_oaicompat();
    }
    std::string json;
    try {
        json = out.dump_safe();
    } catch (...) {
        json = R"({"format":"","raw":"","message":{"role":"assistant","content":""}})";
    }
    jbyteArray bytes = to_bytes(env, json);
    env->CallVoidMethod(callback, onMessage, bytes);
    env->DeleteLocalRef(bytes);
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
