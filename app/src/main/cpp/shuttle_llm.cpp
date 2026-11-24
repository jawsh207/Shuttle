// The on-device language model, as seen from Kotlin (app.shuttle.assistant.llm.LlamaEngine).
//
// A thin JNI layer over llama.cpp: load a GGUF model, run a prompt and stream the reply back
// piece by piece, and free everything again. All prompt formatting, tool calling and
// conversation logic live in Kotlin; this file only turns text into tokens and tokens back
// into text.
//
// Two details matter on a phone:
// - Prefix reuse. Each turn's prompt is the whole conversation so far, so it mostly repeats
//   the previous one. The tokens already in the model's memory (KV cache) are kept and only
//   the new part is processed, which keeps replies quick as a chat grows.
// - UTF-8. A token can end in the middle of a multi-byte character (emoji, accents). Pieces
//   are held back until they form complete characters and sent to Kotlin as bytes, because
//   JNI's NewStringUTF can't take 4-byte characters.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "ShuttleLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    const llama_vocab * vocab = nullptr;
    std::vector<llama_token> cached;  // tokens currently held in the KV cache, in order
    int n_batch = 512;
    std::atomic<bool> abort{false};   // set by nativeCancel from the UI thread
};

// Checked by llama.cpp during every computation, so Stop works even while a long prompt is
// still being read, not only between words of the reply.
bool should_abort(void * data) {
    return static_cast<Engine *>(data)->abort.load();
}

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
    }
}

// Length of the longest prefix of `s` that ends on a complete UTF-8 character.
size_t complete_utf8(const std::string & s) {
    if (s.empty()) return 0;
    // Find where the last character starts (skip back over continuation bytes 10xxxxxx).
    size_t start = s.size() - 1;
    while (start > 0 && (static_cast<unsigned char>(s[start]) & 0xC0) == 0x80 && s.size() - start < 4) --start;
    const auto lead = static_cast<unsigned char>(s[start]);
    size_t need = 1;
    if ((lead & 0xE0) == 0xC0) need = 2;
    else if ((lead & 0xF0) == 0xE0) need = 3;
    else if ((lead & 0xF8) == 0xF0) need = 4;
    return (s.size() - start >= need) ? s.size() : start;
}

// Feeds tokens[from..] into the model at positions from.., in chunks of n_batch.
// Only the very last token asks for logits. Returns false on failure.
bool feed(Engine & e, const std::vector<llama_token> & tokens, size_t from) {
    for (size_t start = from; start < tokens.size(); start += e.n_batch) {
        const size_t end = std::min(tokens.size(), start + static_cast<size_t>(e.n_batch));
        llama_batch_ext_clear(e.batch);
        for (size_t i = start; i < end; ++i) {
            const int32_t idx = llama_batch_ext_add_token(e.batch, 0, tokens[i]);
            if (idx < 0) return false;
            const llama_pos pos = static_cast<llama_pos>(i);
            llama_batch_ext_set_pos(e.batch, idx, &pos);
            if (i + 1 == tokens.size()) llama_batch_ext_set_output_logits(e.batch, idx, true);
        }
        if (llama_process(e.ctx, LLAMA_PROCESS_TYPE_DECODE, e.batch) != 0) return false;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_shuttle_assistant_llm_LlamaEngine_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU only: no vendor GPU drivers on GrapheneOS-style builds
    llama_model * model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGE("could not load model");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("could not create context");
        llama_model_free(model);
        return 0;
    }

    auto * e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->batch = llama_batch_ext_init(ctx);
    llama_set_abort_callback(ctx, should_abort, e);
    e->n_batch = static_cast<int>(cp.n_batch);
    LOGI("model loaded, n_ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(e);
}

// Runs `prompt` (already formatted with the model's chat template) and streams the reply.
// `callback.onPiece(byte[])` gets each chunk of complete UTF-8 text and returns false to stop.
// With max_tokens = 0 it only reads the prompt into the cache (warming up for the next turn).
// Returns 0 when the model finished, -4 when it was stopped, or a negative code on failure:
// -1 tokenizing failed, -2 prompt too long for the context, -3 model failure.
JNIEXPORT jint JNICALL
Java_app_shuttle_assistant_llm_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jstring jprompt, jint max_tokens, jfloat temperature, jobject callback) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return -3;
    e->abort = false;

    jclass cls = env->GetObjectClass(callback);
    jmethodID on_piece = env->GetMethodID(cls, "onPiece", "([B)Z");

    const char * cprompt = env->GetStringUTFChars(jprompt, nullptr);
    const std::string prompt(cprompt);
    env->ReleaseStringUTFChars(jprompt, cprompt);

    // The template already contains every special token, so none are added here.
    int n = -llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    if (n <= 0) return -1;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(e->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n, false, true) < 0) {
        return -1;
    }
    const auto n_ctx = static_cast<size_t>(llama_n_ctx(e->ctx));
    if (tokens.size() + 16 >= n_ctx) return -2;

    // Keep the part of the cache that matches this prompt; always re-feed at least one token
    // so there are fresh logits to sample from.
    size_t keep = 0;
    while (keep < e->cached.size() && keep < tokens.size() && e->cached[keep] == tokens[keep]) ++keep;
    if (keep == tokens.size()) --keep;
    llama_memory_seq_rm(llama_get_memory(e->ctx), 0, static_cast<llama_pos>(keep), -1);
    e->cached.assign(tokens.begin(), tokens.begin() + static_cast<long>(keep));
    if (!feed(*e, tokens, keep)) {
        // A stopped or failed read leaves the cache half-written: start clean next time.
        e->cached.clear();
        llama_memory_clear(llama_get_memory(e->ctx), true);
        return e->abort ? -4 : -3;
    }
    e->cached = tokens;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;
    int result = 0;
    for (int i = 0; i < max_tokens; ++i) {
        if (e->abort) {
            result = -4;
            break;
        }
        const llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, tok)) break;

        char buf[256];
        const int len = llama_token_to_piece(e->vocab, tok, buf, sizeof(buf), 0, false);
        if (len > 0) pending.append(buf, static_cast<size_t>(len));

        const size_t ready = complete_utf8(pending);
        if (ready > 0) {
            jbyteArray bytes = env->NewByteArray(static_cast<jsize>(ready));
            env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(ready), reinterpret_cast<const jbyte *>(pending.data()));
            const jboolean go_on = env->CallBooleanMethod(callback, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !go_on) break;
        }

        if (e->cached.size() + 1 >= n_ctx) break;  // context full
        e->cached.push_back(tok);
        llama_batch_ext_clear(e->batch);
        const int32_t idx = llama_batch_ext_add_token(e->batch, 0, tok);
        const auto pos = static_cast<llama_pos>(e->cached.size() - 1);
        llama_batch_ext_set_pos(e->batch, idx, &pos);
        llama_batch_ext_set_output_logits(e->batch, idx, true);
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, e->batch) != 0) {
            // The last token isn't in the cache after a stop or failure; drop it from the list.
            e->cached.pop_back();
            llama_memory_seq_rm(llama_get_memory(e->ctx), 0, static_cast<llama_pos>(e->cached.size()), -1);
            result = e->abort ? -4 : -3;
            break;
        }
    }
    llama_sampler_free(smpl);
    return result;
}

// Asks a running nativeGenerate to stop as soon as possible. Safe from any thread.
JNIEXPORT void JNICALL
Java_app_shuttle_assistant_llm_LlamaEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (e) e->abort = true;
}

JNIEXPORT void JNICALL
Java_app_shuttle_assistant_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    if (e->batch) llama_batch_ext_free(e->batch);
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // extern "C"
