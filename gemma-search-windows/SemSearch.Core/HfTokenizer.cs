using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace SemSearch.Core
{
    /// <summary>
    /// C# port of the Android app's HfTokenizer: the subset of Hugging Face tokenizer.json used by
    /// Gemma (BPE with byte fallback, added tokens, Replace/Prepend/Unicode normalizers, Split/Metaspace
    /// pre-tokenizers, TemplateProcessing). Verified token-for-token against the Rust implementation.
    /// </summary>
    public sealed class HfTokenizer
    {
        private const int CacheVersion = 2;

        private string[] idToToken = new string[0];
        private readonly Dictionary<string, int> vocab = new Dictionary<string, int>(1 << 19, StringComparer.Ordinal);
        private Dictionary<long, int> mergeRanks = new Dictionary<long, int>();
        private int[] mergeNewIds = new int[0];
        private readonly int[] byteIds = new int[256];
        private bool byteFallback;
        private int unkId = -1;
        private bool fuseUnk;

        private readonly TrieNode addedRoot = new TrieNode();
        private readonly List<(string content, int id, bool special)> added = new List<(string, int, bool)>();

        private JsonElement? normalizerCfg, preTokenizerCfg, postProcessorCfg;
        private int[] prefixIds = new int[0], suffixIds = new int[0];

        private HfTokenizer()
        {
            for (int i = 0; i < 256; i++) byteIds[i] = -1;
        }

        // ------------------------------------------------------------------ loading

        public static HfTokenizer Load(string jsonPath, string cachePath)
        {
            if (cachePath != null && File.Exists(cachePath))
            {
                try
                {
                    var c = ReadCache(cachePath, jsonPath);
                    if (c != null) return c;
                }
                catch (Exception)
                {
                    // fall back to a fresh parse
                }
            }
            var t = new HfTokenizer();
            byte[] json = File.ReadAllBytes(jsonPath);
            int bom = json.Length >= 3 && json[0] == 0xEF && json[1] == 0xBB && json[2] == 0xBF ? 3 : 0;
            t.Parse(bom == 0 ? json : json.AsSpan(bom).ToArray());
            t.FinishSetup();
            if (cachePath != null)
            {
                try { t.WriteCache(cachePath, jsonPath); }
                catch (Exception) { try { File.Delete(cachePath); } catch { } }
            }
            return t;
        }

        private void Parse(byte[] json)
        {
            var r = new Utf8JsonReader(json, new JsonReaderOptions { CommentHandling = JsonCommentHandling.Skip });
            var pendingAdded = new List<(string, int, bool)>();
            List<(string a, string b)> pendingMerges = null;
            Expect(ref r, JsonTokenType.StartObject);
            while (r.Read() && r.TokenType != JsonTokenType.EndObject)
            {
                string key = r.GetString();
                r.Read();
                switch (key)
                {
                    case "added_tokens":
                        if (r.TokenType == JsonTokenType.Null) break;
                        using (var doc = JsonDocument.ParseValue(ref r))
                        {
                            foreach (var a in doc.RootElement.EnumerateArray())
                            {
                                pendingAdded.Add((Str(a, "content", ""), (int)Num(a, "id", -1), Bool(a, "special", false)));
                            }
                        }
                        break;
                    case "normalizer":
                        normalizerCfg = Element(ref r);
                        break;
                    case "pre_tokenizer":
                        preTokenizerCfg = Element(ref r);
                        break;
                    case "post_processor":
                        postProcessorCfg = Element(ref r);
                        break;
                    case "model":
                        pendingMerges = ParseModel(ref r);
                        break;
                    default:
                        r.Skip();
                        break;
                }
            }
            if (pendingMerges != null)
            {
                var triples = new List<int[]>(pendingMerges.Count);
                foreach (var (a, b) in pendingMerges) triples.Add(MergeTriple(a, b));
                SetMerges(triples);
            }
            foreach (var (c, id, sp) in pendingAdded) AddAddedToken(c, id, sp);
        }

        private List<(string, string)> ParseModel(ref Utf8JsonReader r)
        {
            List<(string, string)> pending = null;
            string unkToken = null;
            Expect(ref r, JsonTokenType.StartObject, false);
            while (r.Read() && r.TokenType != JsonTokenType.EndObject)
            {
                string mk = r.GetString();
                r.Read();
                switch (mk)
                {
                    case "type":
                        if (r.GetString() != "BPE") throw new InvalidDataException("Unsupported tokenizer model: " + r.GetString());
                        break;
                    case "vocab":
                        while (r.Read() && r.TokenType != JsonTokenType.EndObject)
                        {
                            string tok = r.GetString();
                            r.Read();
                            vocab[tok] = r.GetInt32();
                        }
                        break;
                    case "merges":
                    {
                        bool buffer = vocab.Count == 0;
                        var list = new List<(string, string)>(1 << 19);
                        while (r.Read() && r.TokenType != JsonTokenType.EndArray)
                        {
                            string a, b;
                            if (r.TokenType == JsonTokenType.String)
                            {
                                string s = r.GetString();
                                int sp = s.IndexOf(' ', 1);
                                if (sp < 0) continue;
                                a = s.Substring(0, sp);
                                b = s.Substring(sp + 1);
                            }
                            else
                            {
                                r.Read();
                                a = r.GetString();
                                r.Read();
                                b = r.GetString();
                                r.Read(); // EndArray
                            }
                            list.Add((a, b));
                        }
                        if (buffer)
                        {
                            pending = list;
                        }
                        else
                        {
                            var triples = new List<int[]>(list.Count);
                            foreach (var (a, b) in list) triples.Add(MergeTriple(a, b));
                            SetMerges(triples);
                        }
                        break;
                    }
                    case "byte_fallback":
                        byteFallback = r.GetBoolean();
                        break;
                    case "fuse_unk":
                        fuseUnk = r.GetBoolean();
                        break;
                    case "unk_token":
                        unkToken = r.TokenType == JsonTokenType.Null ? null : r.GetString();
                        break;
                    case "continuing_subword_prefix":
                    case "end_of_word_suffix":
                        if (r.TokenType == JsonTokenType.String && r.GetString().Length > 0)
                            throw new InvalidDataException("Unsupported BPE option " + mk);
                        break;
                    default:
                        r.Skip();
                        break;
                }
            }
            if (unkToken != null && vocab.TryGetValue(unkToken, out int u)) unkId = u;
            return pending;
        }

        private static JsonElement? Element(ref Utf8JsonReader r)
        {
            if (r.TokenType == JsonTokenType.Null) return null;
            using (var doc = JsonDocument.ParseValue(ref r)) return doc.RootElement.Clone();
        }

        private static void Expect(ref Utf8JsonReader r, JsonTokenType t, bool read = true)
        {
            if (read) r.Read();
            if (r.TokenType != t) throw new InvalidDataException("tokenizer.json: expected " + t + ", got " + r.TokenType);
        }

        private int[] MergeTriple(string a, string b)
        {
            if (vocab.TryGetValue(a, out int ia) && vocab.TryGetValue(b, out int ib) && vocab.TryGetValue(a + b, out int inew))
                return new[] { ia, ib, inew };
            return null;
        }

        private void SetMerges(List<int[]> triples)
        {
            mergeRanks = new Dictionary<long, int>(triples.Count);
            mergeNewIds = new int[triples.Count];
            for (int rank = 0; rank < triples.Count; rank++)
            {
                var t = triples[rank];
                if (t == null)
                {
                    mergeNewIds[rank] = -1;
                    continue;
                }
                // Like the Rust implementation, a repeated pair keeps its last rank.
                mergeRanks[PairKey(t[0], t[1])] = rank;
                mergeNewIds[rank] = t[2];
            }
        }

        private void AddAddedToken(string content, int id, bool special)
        {
            if (id < 0 || content.Length == 0) return;
            vocab[content] = id;
            added.Add((content, id, special));
            var n = addedRoot;
            foreach (char c in content)
            {
                if (!n.Kids.TryGetValue(c, out var next))
                {
                    next = new TrieNode();
                    n.Kids[c] = next;
                }
                n = next;
            }
            n.TokenId = id;
        }

        private void FinishSetup()
        {
            int max = -1;
            foreach (var v in vocab.Values) max = Math.Max(max, v);
            idToToken = new string[max + 1];
            foreach (var kv in vocab) idToToken[kv.Value] = kv.Key;
            InitByteIds();
            Validate(normalizerCfg, "normalizer");
            Validate(preTokenizerCfg, "pre_tokenizer");
            SetupPostProcessor();
        }

        private void InitByteIds()
        {
            for (int b = 0; b < 256; b++) byteIds[b] = vocab.TryGetValue("<0x" + b.ToString("X2") + ">", out int id) ? id : -1;
        }

        private void SetupPostProcessor()
        {
            prefixIds = new int[0];
            suffixIds = new int[0];
            var pp = FindTemplate(postProcessorCfg);
            if (pp == null || !pp.Value.TryGetProperty("single", out var single)) return;
            pp.Value.TryGetProperty("special_tokens", out var specials);
            var pre = new List<int>();
            var suf = new List<int>();
            bool seenSeq = false;
            foreach (var item in single.EnumerateArray())
            {
                if (item.TryGetProperty("Sequence", out _))
                {
                    seenSeq = true;
                }
                else if (item.TryGetProperty("SpecialToken", out var st))
                {
                    string sid = st.GetProperty("id").GetString();
                    var ids = new List<int>();
                    if (specials.ValueKind == JsonValueKind.Object && specials.TryGetProperty(sid, out var spec)
                        && spec.TryGetProperty("ids", out var idArr))
                    {
                        foreach (var x in idArr.EnumerateArray()) ids.Add(x.GetInt32());
                    }
                    else if (vocab.TryGetValue(sid, out int vid))
                    {
                        ids.Add(vid);
                    }
                    else
                    {
                        throw new InvalidDataException("Unknown special token in template: " + sid);
                    }
                    (seenSeq ? suf : pre).AddRange(ids);
                }
            }
            prefixIds = pre.ToArray();
            suffixIds = suf.ToArray();
        }

        private static JsonElement? FindTemplate(JsonElement? cfg)
        {
            if (cfg == null || cfg.Value.ValueKind != JsonValueKind.Object) return null;
            string type = Str(cfg.Value, "type", "");
            if (type == "TemplateProcessing") return cfg;
            if (type == "Sequence" && cfg.Value.TryGetProperty("processors", out var list))
            {
                foreach (var p in list.EnumerateArray())
                {
                    var t = FindTemplate(p);
                    if (t != null) return t;
                }
            }
            return null;
        }

        private static void Validate(JsonElement? cfg, string what)
        {
            if (cfg == null || cfg.Value.ValueKind != JsonValueKind.Object) return;
            string type = Str(cfg.Value, "type", "");
            if (type == "Sequence")
            {
                if (cfg.Value.TryGetProperty(what == "normalizer" ? "normalizers" : "pretokenizers", out var list))
                    foreach (var x in list.EnumerateArray()) Validate(x, what);
                return;
            }
            string[] ok = what == "normalizer"
                ? new[] { "Replace", "Prepend", "NFC", "NFD", "NFKC", "NFKD", "Lowercase", "Strip" }
                : new[] { "Split", "Metaspace", "WhitespaceSplit" };
            if (Array.IndexOf(ok, type) < 0) throw new InvalidDataException("Unsupported " + what + " type: " + type);
        }

        // ------------------------------------------------------------------ public API

        public int VocabSize => idToToken.Length;

        public int? TokenId(string token) => vocab.TryGetValue(token, out int id) ? id : (int?)null;

        public string Token(int id) => id >= 0 && id < idToToken.Length ? idToToken[id] : null;

        public int[] Encode(string text) => Encode(text, true, int.MaxValue);

        /// <param name="maxLength">total length cap; content is truncated, template suffix (e.g. eos) kept.</param>
        public int[] Encode(string text, bool addSpecialTokens, int maxLength)
        {
            var output = new List<int>(text.Length / 2 + 8);
            int start = 0, i = 0, n = text.Length;
            while (i < n)
            {
                var node = addedRoot;
                int matchEnd = -1, matchId = -1;
                for (int k = i; k < n; k++)
                {
                    if (!node.Kids.TryGetValue(text[k], out node)) break;
                    if (node.TokenId >= 0)
                    {
                        matchEnd = k + 1;
                        matchId = node.TokenId;
                    }
                }
                if (matchEnd > 0)
                {
                    if (start < i) EncodeSegment(text.Substring(start, i - start), start == 0, output);
                    output.Add(matchId);
                    i = matchEnd;
                    start = i;
                }
                else
                {
                    i++;
                }
            }
            if (start < n) EncodeSegment(text.Substring(start), start == 0, output);

            if (!addSpecialTokens)
            {
                if (output.Count > maxLength) output.RemoveRange(maxLength, output.Count - maxLength);
                return output.ToArray();
            }
            int room = maxLength == int.MaxValue ? int.MaxValue : maxLength - prefixIds.Length - suffixIds.Length;
            int len = Math.Max(0, Math.Min(output.Count, room));
            var res = new int[prefixIds.Length + len + suffixIds.Length];
            Array.Copy(prefixIds, 0, res, 0, prefixIds.Length);
            output.CopyTo(0, res, prefixIds.Length, len);
            Array.Copy(suffixIds, 0, res, prefixIds.Length + len, suffixIds.Length);
            return res;
        }

        private void EncodeSegment(string segment, bool atTextStart, List<int> output)
        {
            string normalized = Normalize(normalizerCfg, segment);
            var pieces = PreTokenize(preTokenizerCfg, new List<string> { normalized }, atTextStart);
            foreach (var p in pieces) if (p.Length > 0) Bpe(p, output);
        }

        // ------------------------------------------------------------------ normalizers

        private static string Normalize(JsonElement? cfgN, string s)
        {
            if (cfgN == null || cfgN.Value.ValueKind != JsonValueKind.Object) return s;
            var cfg = cfgN.Value;
            switch (Str(cfg, "type", ""))
            {
                case "Sequence":
                    foreach (var x in cfg.GetProperty("normalizers").EnumerateArray()) s = Normalize(x, s);
                    return s;
                case "Replace":
                {
                    var pat = cfg.GetProperty("pattern");
                    string content = Str(cfg, "content", "");
                    if (pat.TryGetProperty("String", out var lit)) return s.Replace(lit.GetString(), content, StringComparison.Ordinal);
                    return new Regex(pat.GetProperty("Regex").GetString()).Replace(s, content.Replace("$", "$$"));
                }
                case "Prepend":
                    return s.Length == 0 ? s : Str(cfg, "prepend", "") + s;
                case "NFC": return s.Normalize(NormalizationForm.FormC);
                case "NFD": return s.Normalize(NormalizationForm.FormD);
                case "NFKC": return s.Normalize(NormalizationForm.FormKC);
                case "NFKD": return s.Normalize(NormalizationForm.FormKD);
                case "Lowercase": return s.ToLowerInvariant();
                case "Strip":
                {
                    bool l = Bool(cfg, "strip_left", true), r = Bool(cfg, "strip_right", true);
                    if (l) s = s.TrimStart();
                    if (r) s = s.TrimEnd();
                    return s;
                }
                default: return s;
            }
        }

        // ------------------------------------------------------------------ pre-tokenizers

        private static List<string> PreTokenize(JsonElement? cfgN, List<string> pieces, bool atTextStart)
        {
            if (cfgN == null || cfgN.Value.ValueKind != JsonValueKind.Object) return pieces;
            var cfg = cfgN.Value;
            string type = Str(cfg, "type", "");
            if (type == "Sequence")
            {
                foreach (var x in cfg.GetProperty("pretokenizers").EnumerateArray()) pieces = PreTokenize(x, pieces, atTextStart);
                return pieces;
            }
            var output = new List<string>();
            for (int idx = 0; idx < pieces.Count; idx++)
            {
                string p = pieces[idx];
                if (type == "Split")
                {
                    var pat = cfg.GetProperty("pattern");
                    var re = pat.TryGetProperty("String", out var lit)
                        ? new Regex(Regex.Escape(lit.GetString()))
                        : new Regex(pat.GetProperty("Regex").GetString());
                    Split(p, re, Str(cfg, "behavior", "Isolated"), Bool(cfg, "invert", false), output);
                }
                else if (type == "WhitespaceSplit")
                {
                    foreach (var w in p.Split((char[])null, StringSplitOptions.RemoveEmptyEntries)) output.Add(w);
                }
                else if (type == "Metaspace")
                {
                    string rep = Str(cfg, "replacement", "▁");
                    string scheme = Str(cfg, "prepend_scheme", null)
                                    ?? (Bool(cfg, "add_prefix_space", true) ? "always" : "never");
                    string s = p.Replace(" ", rep, StringComparison.Ordinal);
                    bool prepend = scheme == "always" || (scheme == "first" && idx == 0 && atTextStart);
                    if (prepend && !s.StartsWith(rep, StringComparison.Ordinal)) s = rep + s;
                    if (Bool(cfg, "split", true)) Split(s, new Regex(Regex.Escape(rep)), "MergedWithNext", false, output);
                    else output.Add(s);
                }
                else
                {
                    output.Add(p);
                }
            }
            return output;
        }

        private static void Split(string s, Regex re, string behavior, bool invert, List<string> output)
        {
            var spans = new List<int[]>();
            int last = 0;
            foreach (Match m in re.Matches(s))
            {
                if (m.Length == 0) continue;
                if (m.Index > last) spans.Add(new[] { last, m.Index, 0 });
                spans.Add(new[] { m.Index, m.Index + m.Length, 1 });
                last = m.Index + m.Length;
            }
            if (last < s.Length) spans.Add(new[] { last, s.Length, 0 });
            if (invert) foreach (var sp in spans) sp[2] = 1 - sp[2];

            var res = new List<int[]>();
            switch (behavior)
            {
                case "Removed":
                    foreach (var sp in spans) if (sp[2] == 0) res.Add(sp);
                    break;
                case "MergedWithPrevious":
                {
                    bool prevNonMatch = false;
                    foreach (var sp in spans)
                    {
                        if (sp[2] == 1 && prevNonMatch) res[res.Count - 1][1] = sp[1];
                        else res.Add(new[] { sp[0], sp[1], sp[2] });
                        prevNonMatch = sp[2] == 0;
                    }
                    break;
                }
                case "MergedWithNext":
                {
                    bool prevMatch = false;
                    foreach (var sp in spans)
                    {
                        if (sp[2] == 0 && prevMatch) res[res.Count - 1][1] = sp[1];
                        else res.Add(new[] { sp[0], sp[1], sp[2] });
                        prevMatch = sp[2] == 1;
                    }
                    break;
                }
                case "Contiguous":
                {
                    bool prevMatch = false;
                    foreach (var sp in spans)
                    {
                        if (sp[2] == 1 && prevMatch) res[res.Count - 1][1] = sp[1];
                        else res.Add(new[] { sp[0], sp[1], sp[2] });
                        prevMatch = sp[2] == 1;
                    }
                    break;
                }
                default:
                    res.AddRange(spans);
                    break;
            }
            foreach (var sp in res) if (sp[1] > sp[0]) output.Add(s.Substring(sp[0], sp[1] - sp[0]));
        }

        // ------------------------------------------------------------------ BPE

        private void Bpe(string word, List<int> output)
        {
            var syms = new List<int>(word.Length + 4);
            bool lastUnk = false;
            for (int i = 0; i < word.Length;)
            {
                int len = char.IsSurrogatePair(word, i) ? 2 : 1;
                string ch = word.Substring(i, len);
                i += len;
                if (vocab.TryGetValue(ch, out int id))
                {
                    syms.Add(id);
                    lastUnk = false;
                    continue;
                }
                if (byteFallback)
                {
                    byte[] bytes = Encoding.UTF8.GetBytes(ch);
                    bool all = true;
                    foreach (byte b in bytes) if (byteIds[b] < 0) { all = false; break; }
                    if (all)
                    {
                        foreach (byte b in bytes) syms.Add(byteIds[b]);
                        lastUnk = false;
                        continue;
                    }
                }
                if (unkId >= 0)
                {
                    if (!(fuseUnk && lastUnk)) syms.Add(unkId);
                    lastUnk = true;
                }
            }
            int n = syms.Count;
            if (n == 0) return;
            if (n == 1)
            {
                output.Add(syms[0]);
                return;
            }
            int[] ids = syms.ToArray();
            int[] prev = new int[n], next = new int[n];
            for (int i = 0; i < n; i++)
            {
                prev[i] = i - 1;
                next[i] = i + 1 < n ? i + 1 : -1;
            }
            var heap = new LongHeap(n);
            for (int i = 0; i + 1 < n; i++)
            {
                if (mergeRanks.TryGetValue(PairKey(ids[i], ids[i + 1]), out int r)) heap.Push(((long)r << 32) | (uint)i);
            }
            var dead = new bool[n];
            while (heap.Count > 0)
            {
                long top = heap.Pop();
                int rank = (int)(top >> 32);
                int pos = (int)(top & 0xffffffffL);
                if (dead[pos]) continue;
                int nx = next[pos];
                if (nx < 0) continue;
                if (!mergeRanks.TryGetValue(PairKey(ids[pos], ids[nx]), out int cur) || cur != rank) continue;
                int newId = mergeNewIds[rank];
                if (newId < 0) continue;
                ids[pos] = newId;
                dead[nx] = true;
                int nn = next[nx];
                next[pos] = nn;
                if (nn >= 0) prev[nn] = pos;
                int pv = prev[pos];
                if (pv >= 0 && mergeRanks.TryGetValue(PairKey(ids[pv], ids[pos]), out int r1)) heap.Push(((long)r1 << 32) | (uint)pv);
                if (nn >= 0 && mergeRanks.TryGetValue(PairKey(ids[pos], ids[nn]), out int r2)) heap.Push(((long)r2 << 32) | (uint)pos);
            }
            for (int i = 0; i >= 0; i = next[i]) output.Add(ids[i]);
        }

        private static long PairKey(int a, int b) => ((long)a << 32) | (uint)b;

        // ------------------------------------------------------------------ cache

        private void WriteCache(string cache, string source)
        {
            var fi = new FileInfo(source);
            string tmp = cache + ".tmp";
            using (var w = new BinaryWriter(File.Create(tmp), Encoding.UTF8))
            {
                w.Write(0x48465448);
                w.Write(CacheVersion);
                w.Write(fi.Length);
                w.Write(fi.LastWriteTimeUtc.Ticks);
                w.Write(idToToken.Length);
                foreach (var t in idToToken) WriteStr(w, t);
                var keys = new long[mergeNewIds.Length];
                for (int i = 0; i < keys.Length; i++) keys[i] = -1;
                foreach (var kv in mergeRanks) keys[kv.Value] = kv.Key;
                w.Write(mergeNewIds.Length);
                for (int r = 0; r < mergeNewIds.Length; r++)
                {
                    w.Write(keys[r]);
                    w.Write(mergeNewIds[r]);
                }
                w.Write(added.Count);
                foreach (var (c, id, sp) in added)
                {
                    WriteStr(w, c);
                    w.Write(id);
                    w.Write(sp);
                }
                w.Write(byteFallback);
                w.Write(unkId);
                w.Write(fuseUnk);
                WriteStr(w, normalizerCfg?.GetRawText());
                WriteStr(w, preTokenizerCfg?.GetRawText());
                WriteStr(w, postProcessorCfg?.GetRawText());
            }
            File.Copy(tmp, cache, true);
            File.Delete(tmp);
        }

        private static HfTokenizer ReadCache(string cache, string source)
        {
            var fi = new FileInfo(source);
            using (var r = new BinaryReader(File.OpenRead(cache), Encoding.UTF8))
            {
                if (r.ReadInt32() != 0x48465448 || r.ReadInt32() != CacheVersion) return null;
                if (r.ReadInt64() != fi.Length || r.ReadInt64() != fi.LastWriteTimeUtc.Ticks) return null;
                var t = new HfTokenizer();
                int n = r.ReadInt32();
                t.idToToken = new string[n];
                for (int i = 0; i < n; i++)
                {
                    string s = ReadStr(r);
                    t.idToToken[i] = s;
                    if (s != null) t.vocab[s] = i;
                }
                int m = r.ReadInt32();
                t.mergeRanks = new Dictionary<long, int>(m);
                t.mergeNewIds = new int[m];
                for (int rank = 0; rank < m; rank++)
                {
                    long key = r.ReadInt64();
                    t.mergeNewIds[rank] = r.ReadInt32();
                    if (key != -1L) t.mergeRanks[key] = rank;
                }
                int a = r.ReadInt32();
                for (int i = 0; i < a; i++)
                {
                    string c = ReadStr(r);
                    int id = r.ReadInt32();
                    bool sp = r.ReadBoolean();
                    t.AddAddedToken(c, id, sp);
                }
                t.byteFallback = r.ReadBoolean();
                t.unkId = r.ReadInt32();
                t.fuseUnk = r.ReadBoolean();
                t.normalizerCfg = ParseOrNull(ReadStr(r));
                t.preTokenizerCfg = ParseOrNull(ReadStr(r));
                t.postProcessorCfg = ParseOrNull(ReadStr(r));
                t.InitByteIds();
                t.SetupPostProcessor();
                return t;
            }
        }

        private static JsonElement? ParseOrNull(string s)
        {
            if (s == null) return null;
            using (var d = JsonDocument.Parse(s)) return d.RootElement.Clone();
        }

        private static void WriteStr(BinaryWriter w, string s)
        {
            if (s == null)
            {
                w.Write(-1);
                return;
            }
            byte[] b = Encoding.UTF8.GetBytes(s);
            w.Write(b.Length);
            w.Write(b);
        }

        private static string ReadStr(BinaryReader r)
        {
            int len = r.ReadInt32();
            return len < 0 ? null : Encoding.UTF8.GetString(r.ReadBytes(len));
        }

        // ------------------------------------------------------------------ helpers

        internal static string Str(JsonElement e, string k, string def) =>
            e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : def;

        internal static long Num(JsonElement e, string k, long def)
        {
            if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty(k, out var v) || v.ValueKind != JsonValueKind.Number) return def;
            return v.TryGetInt64(out long l) ? l : (long)v.GetDouble();
        }

        internal static bool Bool(JsonElement e, string k, bool def) =>
            e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v)
            && (v.ValueKind == JsonValueKind.True || v.ValueKind == JsonValueKind.False) ? v.GetBoolean() : def;

        private sealed class TrieNode
        {
            public readonly Dictionary<char, TrieNode> Kids = new Dictionary<char, TrieNode>();
            public int TokenId = -1;
        }

        private sealed class LongHeap
        {
            private long[] h;
            public int Count { get; private set; }

            public LongHeap(int cap) => h = new long[Math.Max(8, cap)];

            public void Push(long v)
            {
                if (Count == h.Length) Array.Resize(ref h, Count * 2);
                int i = Count++;
                while (i > 0)
                {
                    int p = (i - 1) >> 1;
                    if (h[p] <= v) break;
                    h[i] = h[p];
                    i = p;
                }
                h[i] = v;
            }

            public long Pop()
            {
                long top = h[0];
                long last = h[--Count];
                int i = 0;
                while (true)
                {
                    int l = 2 * i + 1;
                    if (l >= Count) break;
                    int r = l + 1;
                    int c = r < Count && h[r] < h[l] ? r : l;
                    if (h[c] >= last) break;
                    h[i] = h[c];
                    i = c;
                }
                h[i] = last;
                return top;
            }
        }
    }
}
