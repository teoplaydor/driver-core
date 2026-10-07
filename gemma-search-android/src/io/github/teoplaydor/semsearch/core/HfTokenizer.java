package io.github.teoplaydor.semsearch.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java implementation of the subset of Hugging Face {@code tokenizer.json}
 * needed for Gemma-family tokenizers: a BPE model with byte fallback, added
 * (special) tokens, Replace/Prepend/Unicode normalizers, Split/Metaspace
 * pre-tokenizers and a TemplateProcessing post-processor.
 *
 * Verified token-for-token against the reference Rust implementation
 * (see test/TokenizerParityTest.java).
 */
public final class HfTokenizer {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CACHE_VERSION = 2;

    private String[] idToToken = new String[0];
    private final HashMap<String, Integer> vocab = new HashMap<String, Integer>(1 << 19);
    private LongIntMap mergeRanks = new LongIntMap(16);
    private int[] mergeNewIds = new int[0];
    private final int[] byteIds = new int[256];
    private boolean byteFallback;
    private int unkId = -1;
    private boolean fuseUnk;

    private final TrieNode addedRoot = new TrieNode();
    private final List<String> addedContents = new ArrayList<String>();
    private final List<Integer> addedIds = new ArrayList<Integer>();
    private final List<Boolean> addedSpecial = new ArrayList<Boolean>();

    private Object normalizerCfg, preTokenizerCfg, postProcessorCfg;
    private int[] prefixIds = new int[0];
    private int[] suffixIds = new int[0];

    private HfTokenizer() {
        java.util.Arrays.fill(byteIds, -1);
    }

    // ------------------------------------------------------------------ loading

    /** Loads tokenizer.json, using (and refreshing) a compact binary cache if {@code cacheFile} is given. */
    public static HfTokenizer load(File json, File cacheFile) throws IOException {
        if (cacheFile != null && cacheFile.exists()) {
            try {
                HfTokenizer t = readCache(cacheFile, json);
                if (t != null) return t;
            } catch (IOException ignored) {
                // fall through to a fresh parse
            }
        }
        HfTokenizer t = new HfTokenizer();
        Reader r = new InputStreamReader(new BufferedInputStream(new FileInputStream(json), 1 << 16), UTF8);
        try {
            t.parse(new MiniJson(r));
        } finally {
            r.close();
        }
        t.finishSetup();
        if (cacheFile != null) {
            try {
                t.writeCache(cacheFile, json);
            } catch (IOException ignored) {
                cacheFile.delete();
            }
        }
        return t;
    }

    private void parse(MiniJson j) throws IOException {
        List<String[]> pendingMerges = null;
        List<Object[]> added = new ArrayList<Object[]>();
        j.beginObject();
        while (j.hasNext()) {
            String key = j.nextName();
            if ("added_tokens".equals(key)) {
                if (j.peek() == MiniJson.Token.NULL) { j.nextNull(); continue; }
                j.beginArray();
                while (j.hasNext()) {
                    Map<String, Object> a = MiniJson.obj(j.readValue());
                    added.add(new Object[]{MiniJson.str(a, "content", ""), (int) MiniJson.num(a, "id", -1),
                            MiniJson.bool(a, "special", false)});
                }
                j.endArray();
            } else if ("normalizer".equals(key)) {
                normalizerCfg = j.readValue();
            } else if ("pre_tokenizer".equals(key)) {
                preTokenizerCfg = j.readValue();
            } else if ("post_processor".equals(key)) {
                postProcessorCfg = j.readValue();
            } else if ("model".equals(key)) {
                j.beginObject();
                String unkToken = null;
                while (j.hasNext()) {
                    String mk = j.nextName();
                    if ("type".equals(mk)) {
                        String type = j.nextString();
                        if (!"BPE".equals(type)) throw new IOException("Unsupported tokenizer model: " + type);
                    } else if ("vocab".equals(mk)) {
                        j.beginObject();
                        while (j.hasNext()) {
                            String tok = j.nextName();
                            int id = j.nextInt();
                            vocab.put(tok, id);
                        }
                        j.endObject();
                    } else if ("merges".equals(mk)) {
                        j.beginArray();
                        boolean buffer = vocab.isEmpty();
                        if (buffer) pendingMerges = new ArrayList<String[]>();
                        List<int[]> tmp = new ArrayList<int[]>(1 << 19);
                        while (j.hasNext()) {
                            String a, b;
                            if (j.peek() == MiniJson.Token.STRING) {
                                String s = j.nextString();
                                int sp = s.indexOf(' ', 1);
                                if (sp < 0) continue;
                                a = s.substring(0, sp);
                                b = s.substring(sp + 1);
                            } else {
                                j.beginArray();
                                a = j.nextString();
                                b = j.nextString();
                                j.endArray();
                            }
                            if (buffer) {
                                pendingMerges.add(new String[]{a, b});
                            } else {
                                tmp.add(mergeTriple(a, b));
                            }
                        }
                        j.endArray();
                        if (!buffer) setMerges(tmp);
                    } else if ("byte_fallback".equals(mk)) {
                        byteFallback = j.nextBoolean();
                    } else if ("fuse_unk".equals(mk)) {
                        fuseUnk = j.nextBoolean();
                    } else if ("unk_token".equals(mk)) {
                        if (j.peek() == MiniJson.Token.NULL) j.nextNull(); else unkToken = j.nextString();
                    } else if ("continuing_subword_prefix".equals(mk) || "end_of_word_suffix".equals(mk)) {
                        Object v = j.readValue();
                        if (v != null && !"".equals(v)) throw new IOException("Unsupported BPE option " + mk);
                    } else {
                        j.skipValue();
                    }
                }
                j.endObject();
                if (unkToken != null) {
                    Integer u = vocab.get(unkToken);
                    if (u != null) unkId = u;
                }
            } else {
                j.skipValue();
            }
        }
        j.endObject();
        if (pendingMerges != null) {
            List<int[]> tmp = new ArrayList<int[]>(pendingMerges.size());
            for (String[] m : pendingMerges) tmp.add(mergeTriple(m[0], m[1]));
            setMerges(tmp);
        }
        for (Object[] a : added) {
            addAddedToken((String) a[0], (Integer) a[1], (Boolean) a[2]);
        }
    }

    private int[] mergeTriple(String a, String b) {
        Integer ia = vocab.get(a), ib = vocab.get(b), in = vocab.get(a + b);
        if (ia == null || ib == null || in == null) return null;
        return new int[]{ia, ib, in};
    }

    private void setMerges(List<int[]> triples) {
        mergeRanks = new LongIntMap(triples.size() * 2 + 16);
        mergeNewIds = new int[triples.size()];
        for (int rank = 0; rank < triples.size(); rank++) {
            int[] t = triples.get(rank);
            if (t == null) {
                mergeNewIds[rank] = -1;
                continue;
            }
            // Like the Rust implementation, a repeated pair keeps its last rank.
            mergeRanks.put(pairKey(t[0], t[1]), rank);
            mergeNewIds[rank] = t[2];
        }
    }

    private void addAddedToken(String content, int id, boolean special) {
        if (id < 0 || content.isEmpty()) return;
        vocab.put(content, id);
        addedContents.add(content);
        addedIds.add(id);
        addedSpecial.add(special);
        TrieNode n = addedRoot;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            TrieNode next = n.child(c);
            if (next == null) next = n.add(c);
            n = next;
        }
        n.tokenId = id;
    }

    private void finishSetup() throws IOException {
        int max = -1;
        for (Integer v : vocab.values()) max = Math.max(max, v);
        idToToken = new String[max + 1];
        for (Map.Entry<String, Integer> e : vocab.entrySet()) idToToken[e.getValue()] = e.getKey();
        for (int b = 0; b < 256; b++) {
            Integer id = vocab.get(String.format("<0x%02X>", b));
            byteIds[b] = id == null ? -1 : id;
        }
        validateConfig(normalizerCfg, "normalizer");
        validateConfig(preTokenizerCfg, "pre_tokenizer");
        setupPostProcessor();
    }

    private void setupPostProcessor() throws IOException {
        prefixIds = new int[0];
        suffixIds = new int[0];
        Map<String, Object> pp = findTemplate(postProcessorCfg);
        if (pp == null) return;
        List<Object> single = MiniJson.arr(pp.get("single"));
        Map<String, Object> specials = MiniJson.obj(pp.get("special_tokens"));
        List<Integer> pre = new ArrayList<Integer>(), suf = new ArrayList<Integer>();
        boolean seenSeq = false;
        if (single == null) return;
        for (Object item : single) {
            Map<String, Object> m = MiniJson.obj(item);
            if (m.containsKey("Sequence")) {
                seenSeq = true;
            } else if (m.containsKey("SpecialToken")) {
                String sid = MiniJson.str(MiniJson.obj(m.get("SpecialToken")), "id", null);
                List<Integer> ids = new ArrayList<Integer>();
                Map<String, Object> st = specials == null ? null : MiniJson.obj(specials.get(sid));
                if (st != null && st.get("ids") instanceof List) {
                    for (Object o : MiniJson.arr(st.get("ids"))) ids.add(((Number) o).intValue());
                } else if (vocab.containsKey(sid)) {
                    ids.add(vocab.get(sid));
                } else {
                    throw new IOException("Unknown special token in template: " + sid);
                }
                (seenSeq ? suf : pre).addAll(ids);
            }
        }
        prefixIds = toArray(pre);
        suffixIds = toArray(suf);
    }

    private static Map<String, Object> findTemplate(Object cfg) {
        Map<String, Object> m = MiniJson.obj(cfg);
        if (m == null) return null;
        String type = MiniJson.str(m, "type", "");
        if ("TemplateProcessing".equals(type)) return m;
        if ("Sequence".equals(type)) {
            List<Object> list = MiniJson.arr(m.get("processors"));
            if (list != null) for (Object o : list) {
                Map<String, Object> t = findTemplate(o);
                if (t != null) return t;
            }
        }
        return null;
    }

    private static void validateConfig(Object cfg, String what) throws IOException {
        Map<String, Object> m = MiniJson.obj(cfg);
        if (m == null) return;
        String type = MiniJson.str(m, "type", "");
        if ("Sequence".equals(type)) {
            List<Object> list = MiniJson.arr(m.get("normalizer".equals(what) ? "normalizers" : "pretokenizers"));
            if (list != null) for (Object o : list) validateConfig(o, what);
            return;
        }
        String[] ok = "normalizer".equals(what)
                ? new String[]{"Replace", "Prepend", "NFC", "NFD", "NFKC", "NFKD", "Lowercase", "Strip"}
                : new String[]{"Split", "Metaspace", "WhitespaceSplit"};
        for (String s : ok) if (s.equals(type)) return;
        throw new IOException("Unsupported " + what + " type: " + type);
    }

    // ------------------------------------------------------------------ public API

    public int vocabSize() { return idToToken.length; }

    public Integer tokenId(String token) { return vocab.get(token); }

    public String token(int id) { return id >= 0 && id < idToToken.length ? idToToken[id] : null; }

    /** Encodes text with the post-processor's special tokens (e.g. {@code <bos>}). */
    public int[] encode(String text) {
        return encode(text, true, Integer.MAX_VALUE);
    }

    /**
     * @param maxLength total length cap including special tokens; content is truncated from the right
     *                  while the template's suffix tokens (e.g. {@code <eos>}) are kept.
     */
    public int[] encode(String text, boolean addSpecialTokens, int maxLength) {
        IntList out = new IntList(text.length() / 2 + 8);
        int start = 0;
        int i = 0;
        int n = text.length();
        while (i < n) {
            // longest added-token match starting at i
            TrieNode node = addedRoot;
            int matchEnd = -1, matchId = -1;
            for (int k = i; k < n; k++) {
                node = node.child(text.charAt(k));
                if (node == null) break;
                if (node.tokenId >= 0) {
                    matchEnd = k + 1;
                    matchId = node.tokenId;
                }
            }
            if (matchEnd > 0) {
                if (start < i) encodeSegment(text.substring(start, i), start == 0, out);
                out.add(matchId);
                i = matchEnd;
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) encodeSegment(text.substring(start), start == 0, out);

        int[] content = out.toArray();
        if (!addSpecialTokens) {
            if (content.length > maxLength) content = java.util.Arrays.copyOf(content, maxLength);
            return content;
        }
        int room = maxLength - prefixIds.length - suffixIds.length;
        int len = Math.max(0, Math.min(content.length, room));
        int[] res = new int[prefixIds.length + len + suffixIds.length];
        System.arraycopy(prefixIds, 0, res, 0, prefixIds.length);
        System.arraycopy(content, 0, res, prefixIds.length, len);
        System.arraycopy(suffixIds, 0, res, prefixIds.length + len, suffixIds.length);
        return res;
    }

    private void encodeSegment(String segment, boolean atTextStart, IntList out) {
        String normalized = normalize(normalizerCfg, segment);
        List<String> pieces = new ArrayList<String>();
        pieces.add(normalized);
        pieces = preTokenize(preTokenizerCfg, pieces, atTextStart);
        for (String p : pieces) {
            if (!p.isEmpty()) bpe(p, out);
        }
    }

    // ------------------------------------------------------------------ normalizers

    private static String normalize(Object cfg, String s) {
        Map<String, Object> m = MiniJson.obj(cfg);
        if (m == null) return s;
        String type = MiniJson.str(m, "type", "");
        if ("Sequence".equals(type)) {
            for (Object o : MiniJson.arr(m.get("normalizers"))) s = normalize(o, s);
            return s;
        } else if ("Replace".equals(type)) {
            Map<String, Object> pat = MiniJson.obj(m.get("pattern"));
            String content = MiniJson.str(m, "content", "");
            if (pat.containsKey("String")) return s.replace(MiniJson.str(pat, "String", ""), content);
            return Pattern.compile(MiniJson.str(pat, "Regex", "")).matcher(s).replaceAll(Matcher.quoteReplacement(content));
        } else if ("Prepend".equals(type)) {
            return s.isEmpty() ? s : MiniJson.str(m, "prepend", "") + s;
        } else if ("NFC".equals(type)) {
            return Normalizer.normalize(s, Normalizer.Form.NFC);
        } else if ("NFD".equals(type)) {
            return Normalizer.normalize(s, Normalizer.Form.NFD);
        } else if ("NFKC".equals(type)) {
            return Normalizer.normalize(s, Normalizer.Form.NFKC);
        } else if ("NFKD".equals(type)) {
            return Normalizer.normalize(s, Normalizer.Form.NFKD);
        } else if ("Lowercase".equals(type)) {
            return s.toLowerCase(java.util.Locale.ROOT);
        } else if ("Strip".equals(type)) {
            boolean l = MiniJson.bool(m, "strip_left", true), r = MiniJson.bool(m, "strip_right", true);
            int a = 0, b = s.length();
            if (l) while (a < b && Character.isWhitespace(s.charAt(a))) a++;
            if (r) while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
            return s.substring(a, b);
        }
        return s;
    }

    // ------------------------------------------------------------------ pre-tokenizers

    private static List<String> preTokenize(Object cfg, List<String> pieces, boolean atTextStart) {
        Map<String, Object> m = MiniJson.obj(cfg);
        if (m == null) return pieces;
        String type = MiniJson.str(m, "type", "");
        if ("Sequence".equals(type)) {
            for (Object o : MiniJson.arr(m.get("pretokenizers"))) pieces = preTokenize(o, pieces, atTextStart);
            return pieces;
        }
        List<String> out = new ArrayList<String>();
        for (int idx = 0; idx < pieces.size(); idx++) {
            String p = pieces.get(idx);
            if ("Split".equals(type)) {
                Map<String, Object> pat = MiniJson.obj(m.get("pattern"));
                Pattern re = pat.containsKey("String")
                        ? Pattern.compile(Pattern.quote(MiniJson.str(pat, "String", "")))
                        : Pattern.compile(MiniJson.str(pat, "Regex", ""));
                split(p, re, MiniJson.str(m, "behavior", "Isolated"), MiniJson.bool(m, "invert", false), out);
            } else if ("WhitespaceSplit".equals(type)) {
                for (String w : p.trim().split("\\s+")) if (!w.isEmpty()) out.add(w);
            } else if ("Metaspace".equals(type)) {
                String rep = MiniJson.str(m, "replacement", "▁");
                String scheme = MiniJson.str(m, "prepend_scheme", null);
                if (scheme == null) scheme = MiniJson.bool(m, "add_prefix_space", true) ? "always" : "never";
                String s = p.replace(" ", rep);
                boolean prepend = "always".equals(scheme) || ("first".equals(scheme) && idx == 0 && atTextStart);
                if (prepend && !s.startsWith(rep)) s = rep + s;
                if (MiniJson.bool(m, "split", true)) {
                    split(s, Pattern.compile(Pattern.quote(rep)), "MergedWithNext", false, out);
                } else {
                    out.add(s);
                }
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private static void split(String s, Pattern re, String behavior, boolean invert, List<String> out) {
        // Build a list of (start, end, isMatch) spans covering s.
        List<int[]> spans = new ArrayList<int[]>();
        Matcher mt = re.matcher(s);
        int last = 0;
        while (mt.find()) {
            if (mt.end() == mt.start()) continue;
            if (mt.start() > last) spans.add(new int[]{last, mt.start(), 0});
            spans.add(new int[]{mt.start(), mt.end(), 1});
            last = mt.end();
        }
        if (last < s.length()) spans.add(new int[]{last, s.length(), 0});
        if (invert) for (int[] sp : spans) sp[2] = 1 - sp[2];

        List<int[]> res = new ArrayList<int[]>();
        if ("Removed".equals(behavior)) {
            for (int[] sp : spans) if (sp[2] == 0) res.add(sp);
        } else if ("Isolated".equals(behavior)) {
            res.addAll(spans);
        } else if ("MergedWithPrevious".equals(behavior)) {
            boolean prevNonMatch = false;
            for (int[] sp : spans) {
                if (sp[2] == 1 && prevNonMatch) {
                    res.get(res.size() - 1)[1] = sp[1];
                } else {
                    res.add(new int[]{sp[0], sp[1], sp[2]});
                }
                prevNonMatch = sp[2] == 0;
            }
        } else if ("MergedWithNext".equals(behavior)) {
            boolean prevMatch = false;
            for (int[] sp : spans) {
                if (sp[2] == 0 && prevMatch) {
                    res.get(res.size() - 1)[1] = sp[1];
                } else {
                    res.add(new int[]{sp[0], sp[1], sp[2]});
                }
                prevMatch = sp[2] == 1;
            }
        } else if ("Contiguous".equals(behavior)) {
            boolean prevMatch = false;
            for (int[] sp : spans) {
                if (sp[2] == 1 && prevMatch) {
                    res.get(res.size() - 1)[1] = sp[1];
                } else {
                    res.add(new int[]{sp[0], sp[1], sp[2]});
                }
                prevMatch = sp[2] == 1;
            }
        } else {
            res.addAll(spans);
        }
        for (int[] sp : res) if (sp[1] > sp[0]) out.add(s.substring(sp[0], sp[1]));
    }

    // ------------------------------------------------------------------ BPE

    private void bpe(String word, IntList out) {
        // 1. initial symbols: one per code point (or its UTF-8 byte tokens)
        IntList syms = new IntList(word.length() + 4);
        boolean lastUnk = false;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            int len = Character.charCount(cp);
            String ch = word.substring(i, i + len);
            i += len;
            Integer id = vocab.get(ch);
            if (id != null) {
                syms.add(id);
                lastUnk = false;
                continue;
            }
            if (byteFallback) {
                byte[] bytes = ch.getBytes(UTF8);
                boolean all = true;
                for (byte b : bytes) if (byteIds[b & 0xff] < 0) { all = false; break; }
                if (all) {
                    for (byte b : bytes) syms.add(byteIds[b & 0xff]);
                    lastUnk = false;
                    continue;
                }
            }
            if (unkId >= 0) {
                if (!(fuseUnk && lastUnk)) syms.add(unkId);
                lastUnk = true;
            }
        }
        int n = syms.size();
        if (n == 0) return;
        if (n == 1) {
            out.add(syms.get(0));
            return;
        }
        int[] ids = syms.toArray();
        int[] prev = new int[n], next = new int[n];
        for (int i = 0; i < n; i++) {
            prev[i] = i - 1;
            next[i] = i + 1 < n ? i + 1 : -1;
        }
        LongHeap heap = new LongHeap(n);
        for (int i = 0; i + 1 < n; i++) {
            int r = mergeRanks.get(pairKey(ids[i], ids[i + 1]));
            if (r >= 0) heap.push(((long) r << 32) | i);
        }
        boolean[] dead = new boolean[n];
        while (heap.size() > 0) {
            long top = heap.pop();
            int rank = (int) (top >>> 32);
            int pos = (int) (top & 0xffffffffL);
            if (dead[pos]) continue;
            int nx = next[pos];
            if (nx < 0) continue;
            if (mergeRanks.get(pairKey(ids[pos], ids[nx])) != rank) continue;
            int newId = mergeNewIds[rank];
            if (newId < 0) continue;
            ids[pos] = newId;
            dead[nx] = true;
            int nn = next[nx];
            next[pos] = nn;
            if (nn >= 0) prev[nn] = pos;
            int pv = prev[pos];
            if (pv >= 0) {
                int r = mergeRanks.get(pairKey(ids[pv], ids[pos]));
                if (r >= 0) heap.push(((long) r << 32) | pv);
            }
            if (nn >= 0) {
                int r = mergeRanks.get(pairKey(ids[pos], ids[nn]));
                if (r >= 0) heap.push(((long) r << 32) | pos);
            }
        }
        for (int i = 0; i >= 0; i = next[i]) out.add(ids[i]);
    }

    private static long pairKey(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    // ------------------------------------------------------------------ cache

    private void writeCache(File cache, File source) throws IOException {
        File tmp = new File(cache.getPath() + ".tmp");
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            out.writeInt(0x48465448);
            out.writeInt(CACHE_VERSION);
            out.writeLong(source.length());
            out.writeLong(source.lastModified());
            out.writeInt(idToToken.length);
            for (String t : idToToken) writeStr(out, t);
            out.writeInt(mergeNewIds.length);
            // rank -> (a, b, new) ; recover a/b from the hash map keys
            long[] keys = new long[mergeNewIds.length];
            java.util.Arrays.fill(keys, -1L);
            mergeRanks.forEach(keys);
            for (int r = 0; r < mergeNewIds.length; r++) {
                out.writeLong(keys[r]);
                out.writeInt(mergeNewIds[r]);
            }
            out.writeInt(addedContents.size());
            for (int i = 0; i < addedContents.size(); i++) {
                writeStr(out, addedContents.get(i));
                out.writeInt(addedIds.get(i));
                out.writeBoolean(addedSpecial.get(i));
            }
            out.writeBoolean(byteFallback);
            out.writeInt(unkId);
            out.writeBoolean(fuseUnk);
            writeStr(out, MiniJson.write(normalizerCfg));
            writeStr(out, MiniJson.write(preTokenizerCfg));
            writeStr(out, MiniJson.write(postProcessorCfg));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(cache)) throw new IOException("cannot write cache");
    }

    private static HfTokenizer readCache(File cache, File source) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(cache), 1 << 16));
        try {
            if (in.readInt() != 0x48465448 || in.readInt() != CACHE_VERSION) return null;
            if (in.readLong() != source.length() || in.readLong() != source.lastModified()) return null;
            HfTokenizer t = new HfTokenizer();
            int n = in.readInt();
            t.idToToken = new String[n];
            for (int i = 0; i < n; i++) {
                String s = readStr(in);
                t.idToToken[i] = s;
                if (s != null) t.vocab.put(s, i);
            }
            int m = in.readInt();
            t.mergeRanks = new LongIntMap(m * 2 + 16);
            t.mergeNewIds = new int[m];
            for (int r = 0; r < m; r++) {
                long key = in.readLong();
                t.mergeNewIds[r] = in.readInt();
                if (key != -1L) t.mergeRanks.put(key, r);
            }
            int a = in.readInt();
            for (int i = 0; i < a; i++) {
                String c = readStr(in);
                int id = in.readInt();
                boolean sp = in.readBoolean();
                t.addAddedToken(c, id, sp);
            }
            t.byteFallback = in.readBoolean();
            t.unkId = in.readInt();
            t.fuseUnk = in.readBoolean();
            t.normalizerCfg = MiniJson.parse(readStr(in));
            t.preTokenizerCfg = MiniJson.parse(readStr(in));
            t.postProcessorCfg = MiniJson.parse(readStr(in));
            for (int b = 0; b < 256; b++) {
                Integer id = t.vocab.get(String.format("<0x%02X>", b));
                t.byteIds[b] = id == null ? -1 : id;
            }
            t.setupPostProcessor();
            return t;
        } finally {
            in.close();
        }
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(UTF8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, UTF8);
    }

    private static int[] toArray(List<Integer> l) {
        int[] r = new int[l.size()];
        for (int i = 0; i < r.length; i++) r[i] = l.get(i);
        return r;
    }

    // ------------------------------------------------------------------ small collections

    private static final class TrieNode {
        char[] keys = new char[0];
        TrieNode[] kids = new TrieNode[0];
        int tokenId = -1;

        TrieNode child(char c) {
            for (int i = 0; i < keys.length; i++) if (keys[i] == c) return kids[i];
            return null;
        }

        TrieNode add(char c) {
            int n = keys.length;
            keys = java.util.Arrays.copyOf(keys, n + 1);
            kids = java.util.Arrays.copyOf(kids, n + 1);
            keys[n] = c;
            TrieNode t = new TrieNode();
            kids[n] = t;
            return t;
        }
    }

    static final class IntList {
        int[] a;
        int n;

        IntList(int cap) { a = new int[Math.max(4, cap)]; }

        void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        int get(int i) { return a[i]; }

        int size() { return n; }

        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }

    /** Open-addressing long -> non-negative int map. */
    static final class LongIntMap {
        private long[] keys;
        private int[] vals;
        private int mask, size;

        LongIntMap(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
            keys = new long[cap];
            vals = new int[cap];
            java.util.Arrays.fill(vals, -1);
            mask = cap - 1;
        }

        private static int hash(long k) {
            k ^= k >>> 33;
            k *= 0xff51afd7ed558ccdL;
            k ^= k >>> 33;
            k *= 0xc4ceb9fe1a85ec53L;
            k ^= k >>> 33;
            return (int) k;
        }

        int get(long k) {
            int i = hash(k) & mask;
            while (vals[i] >= 0) {
                if (keys[i] == k) return vals[i];
                i = (i + 1) & mask;
            }
            return -1;
        }

        void put(long k, int v) {
            if ((size + 1) * 2 > keys.length) grow();
            int i = hash(k) & mask;
            while (vals[i] >= 0) {
                if (keys[i] == k) {
                    vals[i] = v;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = k;
            vals[i] = v;
            size++;
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = vals;
            keys = new long[ok.length * 2];
            vals = new int[ok.length * 2];
            java.util.Arrays.fill(vals, -1);
            mask = keys.length - 1;
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ov[i] >= 0) put(ok[i], ov[i]);
        }

        /** Writes key of each entry into out[value]. */
        void forEach(long[] out) {
            for (int i = 0; i < keys.length; i++) if (vals[i] >= 0 && vals[i] < out.length) out[vals[i]] = keys[i];
        }
    }

    /** Binary min-heap of longs. */
    static final class LongHeap {
        private long[] h;
        private int n;

        LongHeap(int cap) { h = new long[Math.max(8, cap)]; }

        int size() { return n; }

        void push(long v) {
            if (n == h.length) h = java.util.Arrays.copyOf(h, n * 2);
            int i = n++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (h[p] <= v) break;
                h[i] = h[p];
                i = p;
            }
            h[i] = v;
        }

        long pop() {
            long top = h[0];
            long last = h[--n];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= n) break;
                int r = l + 1;
                int c = (r < n && h[r] < h[l]) ? r : l;
                if (h[c] >= last) break;
                h[i] = h[c];
                i = c;
            }
            h[i] = last;
            return top;
        }
    }
}
