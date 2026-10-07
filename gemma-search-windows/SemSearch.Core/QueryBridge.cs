using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace SemSearch.Core
{
    /// <summary>
    /// Word-level Russian → English rendering of queries (dictionary + Snowball stems). The model aligns
    /// images best with English text, so photo search uses the sum of the original and English vectors.
    /// </summary>
    public sealed class QueryBridge
    {
        public sealed class Result
        {
            public string English;
            public int Translated;
            public List<string> Unknown;
        }

        private static readonly Regex Word = new Regex(@"[\p{L}\p{N}]+(?:[-'][\p{L}\p{N}]+)*");
        private readonly Dictionary<string, string> function = new Dictionary<string, string>();
        private readonly Dictionary<string, string> byStem = new Dictionary<string, string>();

        public static QueryBridge LoadEmbedded()
        {
            using (var s = typeof(QueryBridge).Assembly.GetManifestResourceStream("ru_en_lexicon.txt"))
                return Load(s);
        }

        public static QueryBridge Load(Stream lexicon)
        {
            var b = new QueryBridge();
            using (var r = new StreamReader(lexicon, Encoding.UTF8))
            {
                bool functionSection = false;
                string line;
                while ((line = r.ReadLine()) != null)
                {
                    line = line.Trim();
                    if (line.Length == 0 || line.StartsWith("#")) continue;
                    if (line == "[function]") { functionSection = true; continue; }
                    if (line == "[words]") { functionSection = false; continue; }
                    int eq = line.IndexOf('=');
                    if (eq < 0) continue;
                    string en = line.Substring(eq + 1).Trim();
                    foreach (var f in line.Substring(0, eq).Split('|'))
                    {
                        string form = Normalize(f.Trim());
                        if (form.Length == 0) continue;
                        if (functionSection)
                        {
                            b.function[form] = en;
                        }
                        else
                        {
                            string key = StemPhrase(form);
                            if (!b.byStem.ContainsKey(key)) b.byStem[key] = en;
                            if (!b.byStem.ContainsKey(form)) b.byStem[form] = en;
                        }
                    }
                }
            }
            return b;
        }

        public static bool HasCyrillic(string s)
        {
            foreach (char c in s) if (c >= 'Ѐ' && c <= 'ӿ') return true;
            return false;
        }

        private static string Normalize(string s) => s.ToLowerInvariant().Replace('ё', 'е');

        private static string StemPhrase(string phrase)
        {
            var sb = new StringBuilder();
            foreach (var w in phrase.Split((char[])null, System.StringSplitOptions.RemoveEmptyEntries))
            {
                if (sb.Length > 0) sb.Append(' ');
                sb.Append(RussianStemmer.Stem(w));
            }
            return sb.ToString();
        }

        /// <returns>English rendering, or null when no content word was translated.</returns>
        public Result Translate(string query)
        {
            var words = new List<string>();
            foreach (Match m in Word.Matches(Normalize(query))) words.Add(m.Value);
            var output = new List<(string text, bool isFunction)>();
            var unknown = new List<string>();
            int translated = 0;
            for (int i = 0; i < words.Count; i++)
            {
                string w = words[i];
                if (!HasCyrillic(w))
                {
                    output.Add((w, false));
                    translated++;
                    continue;
                }
                if (i + 1 < words.Count && byStem.TryGetValue(RussianStemmer.Stem(w) + " " + RussianStemmer.Stem(words[i + 1]), out var two))
                {
                    output.Add((two, false));
                    translated++;
                    i++;
                    continue;
                }
                if (function.TryGetValue(w, out var f))
                {
                    if (f.Length > 0) output.Add((f, true));
                    continue;
                }
                // Exact form first ("голубой"/"голубь"), then the stem, then a second stemming pass
                // (Snowball has no part of speech: "ресторан" → "рестора", "ресторане" → "ресторан").
                string stem = RussianStemmer.Stem(w);
                if (byStem.TryGetValue(w, out var en) || byStem.TryGetValue(stem, out en) || byStem.TryGetValue(RussianStemmer.Stem(stem), out en))
                {
                    output.Add((en, false));
                    translated++;
                }
                else unknown.Add(w);
            }
            if (translated == 0) return null;
            int from = 0, to = output.Count;
            while (from < to && output[from].isFunction) from++;
            while (to > from && output[to - 1].isFunction) to--;
            var sb = new StringBuilder();
            for (int i = from; i < to; i++)
            {
                if (sb.Length > 0) sb.Append(' ');
                sb.Append(output[i].text);
            }
            return new Result { English = sb.ToString(), Translated = translated, Unknown = unknown };
        }
    }
}
