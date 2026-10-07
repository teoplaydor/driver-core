using System.Text;

namespace SemSearch.Core
{
    /// <summary>Snowball Russian stemmer (same port as the Android app; checked against snowballstemmer).</summary>
    public static class RussianStemmer
    {
        private const string Vowels = "аеиоуыэюя";
        private static readonly string[] PerfectiveGerund1 = { "в", "вши", "вшись" };
        private static readonly string[] PerfectiveGerund2 = { "ив", "ивши", "ившись", "ыв", "ывши", "ывшись" };
        private static readonly string[] Adjective = { "ее", "ие", "ые", "ое", "ими", "ыми", "ей", "ий", "ый", "ой", "ем",
            "им", "ым", "ом", "его", "ого", "ему", "ому", "их", "ых", "ую", "юю", "ая", "яя", "ою", "ею" };
        private static readonly string[] Participle1 = { "ем", "нн", "вш", "ющ", "щ" };
        private static readonly string[] Participle2 = { "ивш", "ывш", "ующ" };
        private static readonly string[] Reflexive = { "ся", "сь" };
        private static readonly string[] Verb1 = { "ла", "на", "ете", "йте", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ют",
            "ны", "ть", "ешь", "нно" };
        private static readonly string[] Verb2 = { "ила", "ыла", "ена", "ейте", "уйте", "ите", "или", "ыли", "ей", "уй", "ил",
            "ыл", "им", "ым", "ен", "ило", "ыло", "ено", "ят", "ует", "уют", "ит", "ыт", "ены", "ить", "ыть", "ишь",
            "ую", "ю" };
        private static readonly string[] Noun = { "а", "ев", "ов", "ие", "ье", "е", "иями", "ями", "ами", "еи", "ии", "и", "ией",
            "ей", "ой", "ий", "й", "иям", "ям", "ием", "ем", "ам", "ом", "о", "у", "ах", "иях", "ях", "ы", "ь", "ию",
            "ью", "ю", "ия", "ья", "я" };
        private static readonly string[] Derivational = { "ост", "ость" };
        private static readonly string[] Tidy = { "ейш", "ейше", "н", "ь" };

        private static bool IsVowel(char c) => Vowels.IndexOf(c) >= 0;

        public static string Stem(string word)
        {
            var w = new StringBuilder(word.ToLowerInvariant().Replace('ё', 'е'));
            int n = w.Length, pV = n, p2 = n, i = 0;
            while (i < n && !IsVowel(w[i])) i++;
            if (i < n)
            {
                pV = i + 1;
                int j = pV;
                while (j < n && IsVowel(w[j])) j++;
                if (j < n)
                {
                    j++;
                    while (j < n && !IsVowel(w[j])) j++;
                    if (j < n)
                    {
                        j++;
                        while (j < n && IsVowel(w[j])) j++;
                        if (j < n) p2 = j + 1;
                    }
                }
            }
            if (pV > w.Length) return w.ToString();

            if (!RemoveGrouped(w, pV, PerfectiveGerund1, PerfectiveGerund2))
            {
                RemoveLongest(w, pV, Reflexive);
                if (!RemoveAdjectival(w, pV) && !RemoveGrouped(w, pV, Verb1, Verb2)) RemoveLongest(w, pV, Noun);
            }
            if (w.Length > pV && w[w.Length - 1] == 'и') w.Length--;
            string d = LongestSuffix(w, pV, Derivational);
            if (d != null && w.Length - d.Length >= p2) w.Length -= d.Length;
            string t = LongestSuffix(w, pV, Tidy);
            if (t != null)
            {
                if (t == "ейш" || t == "ейше")
                {
                    w.Length -= t.Length;
                    if (EndsWith(w, pV, "нн")) w.Length--;
                }
                else if (t == "н")
                {
                    if (EndsWith(w, pV, "нн")) w.Length--;
                }
                else w.Length--;
            }
            return w.ToString();
        }

        private static bool EndsWith(StringBuilder w, int limit, string s)
        {
            int start = w.Length - s.Length;
            if (start < limit) return false;
            for (int k = 0; k < s.Length; k++) if (w[start + k] != s[k]) return false;
            return true;
        }

        private static string LongestSuffix(StringBuilder w, int limit, string[] list)
        {
            string best = null;
            foreach (var s in list)
                if ((best == null || s.Length > best.Length) && EndsWith(w, limit, s)) best = s;
            return best;
        }

        private static bool RemoveLongest(StringBuilder w, int limit, string[] list)
        {
            string s = LongestSuffix(w, limit, list);
            if (s == null) return false;
            w.Length -= s.Length;
            return true;
        }

        private static bool RemoveGrouped(StringBuilder w, int limit, string[] g1, string[] g2)
        {
            string a = LongestSuffix(w, limit, g1), b = LongestSuffix(w, limit, g2);
            if (a == null && b == null) return false;
            if (b != null && (a == null || b.Length >= a.Length))
            {
                w.Length -= b.Length;
                return true;
            }
            int before = w.Length - a.Length - 1;
            if (before < limit) return false;
            char c = w[before];
            if (c != 'а' && c != 'я') return false;
            w.Length -= a.Length;
            return true;
        }

        private static bool RemoveAdjectival(StringBuilder w, int limit)
        {
            if (!RemoveLongest(w, limit, Adjective)) return false;
            RemoveGrouped(w, limit, Participle1, Participle2);
            return true;
        }
    }
}
