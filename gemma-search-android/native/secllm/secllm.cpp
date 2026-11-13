// The secretary's local language model (llama.cpp — a GGUF file such as Ternary-Bonsai-4B) behind a small JNI:
// a model loaded once and kept, the long system prompts of each kind of request read once and kept in the model's
// cache (a sequence of their own), so each new notification costs only its own tokens; the answer constrained by a
// GBNF grammar (valid JSON of the asked schema), greedy for JSON, sampled for free text; cancellable.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

using Clock = std::chrono::steady_clock;

struct Prefix {
    std::vector<llama_token> tokens;
    int seq = -1;
    long long used = 0;
};

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0, n_batch = 0, n_slots = 0, work = 0;
    std::vector<Prefix> prefixes;
    long long clock = 0;
    std::atomic<bool> cancel{false};
    std::mutex lock;
    // the last request's timing, for the log and the benchmark
    int last_prefix_new = 0, last_suffix = 0, last_prompt = 0, last_out = 0;
    double last_prefill_ms = 0, last_decode_ms = 0;
    bool last_prefix_hit = false;
};

// From UTF-16 (not GetStringUTFChars: its modified UTF-8 writes an emoji as two 3-byte halves the tokenizer would
// not know); a lone surrogate becomes U+FFFD.
std::string to_utf8(JNIEnv *env, jstring s) {
    std::string out;
    if (!s) return out;
    const jsize n = env->GetStringLength(s);
    const jchar *c = env->GetStringChars(s, nullptr);
    if (!c) return out;
    out.reserve(n * 2);
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = c[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) out += (char) cp;
        else if (cp < 0x800) { out += (char) (0xC0 | (cp >> 6)); out += (char) (0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18)); out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, c);
    return out;
}

// Java's modified UTF-8 cannot carry 4-byte sequences (emoji): build the string from UTF-16 instead.
jstring from_utf8(JNIEnv *env, const std::string &s) {
    std::vector<jchar> u16;
    u16.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp;
        int len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 14) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 30) { cp = c & 0x07; len = 4; }
        else { i++; u16.push_back(0xFFFD); continue; }
        if (i + len > s.size()) { u16.push_back(0xFFFD); break; }
        bool ok = true;
        for (int k = 1; k < len; k++) {
            unsigned char cc = (unsigned char) s[i + k];
            if ((cc >> 6) != 2) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { u16.push_back(0xFFFD); i++; continue; }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            u16.push_back((jchar) (0xD800 + (cp >> 10)));
            u16.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        } else {
            u16.push_back((jchar) cp);
        }
        i += len;
    }
    return env->NewString(u16.data(), (jsize) u16.size());
}

std::vector<llama_token> tokenize(const Engine &e, const std::string &text) {
    int n = -llama_tokenize(e.vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> out(std::max(n, 0));
    if (n > 0) llama_tokenize(e.vocab, text.c_str(), (int32_t) text.size(), out.data(), n, false, true);
    return out;
}

std::string piece(const Engine &e, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(e.vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::vector<char> big(-n);
        n = llama_token_to_piece(e.vocab, t, big.data(), (int32_t) big.size(), 0, false);
        return std::string(big.data(), std::max(n, 0));
    }
    return std::string(buf, n);
}

// Decode tokens into a sequence from position `pos`, logits only for the last one. False when cancelled/failed.
bool feed(Engine &e, const std::vector<llama_token> &toks, int seq, int pos, bool logits_last) {
    llama_batch batch = llama_batch_init(e.n_batch, 0, 1);
    bool ok = true;
    for (size_t at = 0; at < toks.size() && ok; at += e.n_batch) {
        if (e.cancel.load()) { ok = false; break; }
        const size_t n = std::min((size_t) e.n_batch, toks.size() - at);
        batch.n_tokens = 0;
        for (size_t i = 0; i < n; i++) {
            const int k = batch.n_tokens++;
            batch.token[k] = toks[at + i];
            batch.pos[k] = pos + (int) (at + i);
            batch.n_seq_id[k] = 1;
            batch.seq_id[k][0] = seq;
            batch.logits[k] = logits_last && at + i == toks.size() - 1;
        }
        ok = llama_decode(e.ctx, batch) == 0;
    }
    llama_batch_free(batch);
    return ok;
}

// The cached sequence for this prefix (read now if it was not there): its slot, or -1.
int prefix_slot(Engine &e, const std::vector<llama_token> &toks) {
    llama_memory_t mem = llama_get_memory(e.ctx);
    for (auto &p : e.prefixes) {
        if (p.seq >= 0 && p.tokens == toks) {
            p.used = ++e.clock;
            e.last_prefix_hit = true;
            return p.seq;
        }
    }
    // the least recently used slot gives way
    Prefix *slot = &e.prefixes[0];
    for (auto &p : e.prefixes) if (p.used < slot->used) slot = &p;
    llama_memory_seq_rm(mem, slot->seq, -1, -1);
    slot->tokens.clear();
    if (!feed(e, toks, slot->seq, 0, false)) {
        llama_memory_seq_rm(mem, slot->seq, -1, -1);
        return -1;
    }
    slot->tokens = toks;
    slot->used = ++e.clock;
    e.last_prefix_new = (int) toks.size();
    return slot->seq;
}

double ms_since(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeLoad(
        JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads, jint n_slots, jboolean kv_q8) {
    static std::once_flag once;
    std::call_once(once, [] { llama_backend_init(); });
    const std::string path = to_utf8(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // the file stays the model: pages the system can drop and read back
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = (uint32_t) (n_slots + 1);
    cp.kv_unified = true;  // all sequences share the context: cached prefixes and the request in hand
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    if (kv_q8) {
        cp.type_k = GGML_TYPE_Q8_0;
        cp.type_v = GGML_TYPE_Q8_0;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    }
    Engine *e = new Engine();
    // a long reading stops at once when cancelled
    cp.abort_callback = [](void *data) { return reinterpret_cast<Engine *>(data)->cancel.load(); };
    cp.abort_callback_data = e;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        delete e;
        llama_model_free(model);
        return 0;
    }
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = (int) llama_n_ctx(ctx);
    e->n_batch = 512;
    e->n_slots = n_slots;
    e->work = n_slots;  // the last sequence: the request in hand
    e->prefixes.resize(n_slots);
    for (int i = 0; i < n_slots; i++) e->prefixes[i].seq = i;
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeFree(JNIEnv *, jclass, jlong h) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    e->cancel = true;
    std::lock_guard<std::mutex> g(e->lock);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeCancel(JNIEnv *, jclass, jlong h) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (e) e->cancel = true;
}

/**
 * prefix: kept in the cache for the next request of the same kind (the system prompt and the start of the user
 * turn); suffix: the rest of the prompt. grammar: GBNF (empty — free text). temperature 0 — greedy.
 * Returns the answer; null when cancelled or the context is too small.
 */
JNIEXPORT jstring JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeGenerate(
        JNIEnv *env, jclass, jlong h, jstring jprefix, jstring jsuffix, jstring jgrammar, jint max_tokens,
        jfloat temperature, jint seed) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    std::lock_guard<std::mutex> g(e->lock);
    e->cancel = false;
    e->last_prefix_new = 0;
    e->last_prefix_hit = false;
    const std::string prefix = to_utf8(env, jprefix), suffix = to_utf8(env, jsuffix), grammar = to_utf8(env, jgrammar);
    llama_memory_t mem = llama_get_memory(e->ctx);
    const auto t0 = Clock::now();

    const std::vector<llama_token> ptoks = tokenize(*e, prefix);
    const std::vector<llama_token> stoks = tokenize(*e, suffix);
    if (stoks.empty() || (int) (ptoks.size() + stoks.size()) + max_tokens > e->n_ctx) return nullptr;

    llama_memory_seq_rm(mem, e->work, -1, -1);
    int pos = 0;
    if (!ptoks.empty()) {
        const int slot = prefix_slot(*e, ptoks);
        if (slot < 0) return nullptr;
        llama_memory_seq_cp(mem, slot, e->work, 0, (llama_pos) ptoks.size());
        pos = (int) ptoks.size();
    }
    if (!feed(*e, stoks, e->work, pos, true)) {
        llama_memory_seq_rm(mem, e->work, -1, -1);
        return nullptr;
    }
    pos += (int) stoks.size();
    e->last_prompt = (int) (ptoks.size() + stoks.size());
    e->last_suffix = (int) stoks.size();
    e->last_prefill_ms = ms_since(t0);

    // the grammar is asked only about the token the model chose (one check, not 150 000) and only when it refuses
    // that one does it filter them all — as llama.cpp's own sampler does
    llama_sampler *gs = nullptr;
    if (!grammar.empty()) {
        gs = llama_sampler_init_grammar(e->vocab, grammar.c_str(), "root");
        if (!gs) {
            llama_memory_seq_rm(mem, e->work, -1, -1);
            return from_utf8(env, std::string("\x01grammar"));
        }
    }
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist((uint32_t) seed));
    }
    const int n_vocab = llama_vocab_n_tokens(e->vocab);
    std::vector<llama_token_data> cur(n_vocab);
    auto fill = [&](llama_token_data_array &arr) {
        const float *logits = llama_get_logits_ith(e->ctx, -1);
        for (int i = 0; i < n_vocab; i++) cur[i] = llama_token_data{i, logits[i], 0.0f};
        arr = llama_token_data_array{cur.data(), (size_t) n_vocab, -1, false};
    };

    const auto t1 = Clock::now();
    std::string out;
    int n_out = 0;
    bool cancelled = false;
    llama_batch one = llama_batch_init(1, 0, 1);
    for (; n_out < max_tokens; n_out++) {
        if (e->cancel.load()) { cancelled = true; break; }
        llama_token_data_array arr;
        fill(arr);
        llama_sampler_apply(chain, &arr);
        llama_token t = arr.data[arr.selected].id;
        if (gs) {
            llama_token_data single{t, 1.0f, 0.0f};
            llama_token_data_array one_arr{&single, 1, -1, false};
            llama_sampler_apply(gs, &one_arr);
            if (single.logit == -INFINITY) {
                // refused: the grammar asked about the 64 likeliest first (the best allowed one is nearly always
                // there — for greedy that is exact), about all only when none of them fits
                fill(arr);
                const size_t k = std::min((size_t) 64, cur.size());
                std::partial_sort(cur.begin(), cur.begin() + k, cur.end(),
                                  [](const llama_token_data &x, const llama_token_data &y) { return x.logit > y.logit; });
                llama_token_data_array top{cur.data(), k, -1, true};
                llama_sampler_apply(gs, &top);
                bool any = false;
                for (size_t i = 0; i < k && !any; i++) any = top.data[i].logit != -INFINITY;
                if (any) {
                    llama_sampler_apply(chain, &top);
                    t = top.data[top.selected].id;
                } else {
                    fill(arr);
                    llama_sampler_apply(gs, &arr);
                    llama_sampler_apply(chain, &arr);
                    t = arr.data[arr.selected].id;
                }
            }
            llama_sampler_accept(gs, t);
        }
        llama_sampler_accept(chain, t);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        out += piece(*e, t);
        one.n_tokens = 1;
        one.token[0] = t;
        one.pos[0] = pos++;
        one.n_seq_id[0] = 1;
        one.seq_id[0][0] = e->work;
        one.logits[0] = true;
        if (llama_decode(e->ctx, one) != 0) {
            cancelled = e->cancel.load();  // the abort callback stopped it mid-step
            break;
        }
    }
    if (gs) llama_sampler_free(gs);
    llama_batch_free(one);
    llama_sampler_free(chain);
    llama_memory_seq_rm(mem, e->work, -1, -1);
    e->last_out = n_out;
    e->last_decode_ms = ms_since(t1);
    if (cancelled) return nullptr;
    return from_utf8(env, out);
}

/** The last request: "prompt tokens, of them read now, prefill ms, answer tokens, decode ms, prefix hit". */
JNIEXPORT jstring JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeStats(JNIEnv *env, jclass, jlong h) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    char buf[200];
    snprintf(buf, sizeof(buf), "%d %d %.1f %d %.1f %d", e->last_prompt, e->last_prefix_new + e->last_suffix,
             e->last_prefill_ms, e->last_out, e->last_decode_ms, e->last_prefix_hit ? 1 : 0);
    return env->NewStringUTF(buf);
}

/** Tokens in this text (for keeping prompts within the context). */
JNIEXPORT jint JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeCount(JNIEnv *env, jclass, jlong h, jstring text) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (!e) return -1;
    return (jint) tokenize(*e, to_utf8(env, text)).size();
}

JNIEXPORT jint JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeContext(JNIEnv *, jclass, jlong h) {
    Engine *e = reinterpret_cast<Engine *>(h);
    return e ? e->n_ctx : 0;
}

/** A string from the model file's metadata ("general.name", "tokenizer.chat_template"...); null if absent. */
JNIEXPORT jstring JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeMeta(JNIEnv *env, jclass, jlong h, jstring key) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (!e) return nullptr;
    const std::string k = to_utf8(env, key);
    const int32_t n = llama_model_meta_val_str(e->model, k.c_str(), nullptr, 0);
    if (n < 0) return nullptr;
    std::vector<char> buf(n + 1);
    llama_model_meta_val_str(e->model, k.c_str(), buf.data(), buf.size());
    return from_utf8(env, std::string(buf.data(), n));
}

/** The id of a special token written as text ("<|im_start|>"), or -1 when the vocabulary has no such one token. */
JNIEXPORT jint JNICALL Java_io_github_teoplaydor_semsearch_secretary_LocalLlm_nativeSpecial(JNIEnv *env, jclass, jlong h, jstring text) {
    Engine *e = reinterpret_cast<Engine *>(h);
    if (!e) return -1;
    const std::vector<llama_token> t = tokenize(*e, to_utf8(env, text));
    return t.size() == 1 && llama_vocab_get_attr(e->vocab, t[0]) & LLAMA_TOKEN_ATTR_CONTROL ? t[0] : -1;
}

}  // extern "C"
