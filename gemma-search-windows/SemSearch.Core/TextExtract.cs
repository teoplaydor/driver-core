using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;

namespace SemSearch.Core
{
    /// <summary>Text for document embeddings: plain-text/code files and .docx (first few thousand characters).</summary>
    public static class TextExtract
    {
        public const int MaxChars = 6000;

        public static readonly HashSet<string> TextExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            ".txt", ".md", ".markdown", ".rst", ".csv", ".tsv", ".log", ".ini", ".cfg", ".conf", ".json", ".xml", ".yaml", ".yml",
            ".toml", ".html", ".htm", ".css", ".js", ".ts", ".tsx", ".jsx", ".py", ".java", ".kt", ".cs", ".cpp", ".cc", ".c",
            ".h", ".hpp", ".go", ".rs", ".rb", ".php", ".swift", ".sql", ".sh", ".ps1", ".bat", ".cmd", ".lua", ".dart", ".vue",
            ".tex", ".srt"
        };

        public static bool IsSupported(string path)
        {
            string ext = Path.GetExtension(path);
            return TextExtensions.Contains(ext) || ext.Equals(".docx", StringComparison.OrdinalIgnoreCase);
        }

        /// <returns>text, or null for binary/empty files</returns>
        public static string Extract(string path)
        {
            if (Path.GetExtension(path).Equals(".docx", StringComparison.OrdinalIgnoreCase)) return Docx(path);
            byte[] head;
            using (var f = File.OpenRead(path))
            {
                head = new byte[Math.Min(f.Length, MaxChars * 4)];
                int read = 0, n;
                while (read < head.Length && (n = f.Read(head, read, head.Length - read)) > 0) read += n;
            }
            string s = Decode(head);
            if (s == null) return null;
            s = s.Length > MaxChars ? s.Substring(0, MaxChars) : s;
            return string.IsNullOrWhiteSpace(s) ? null : s;
        }

        private static string Decode(byte[] b)
        {
            if (b.Length >= 2 && b[0] == 0xFF && b[1] == 0xFE) return Encoding.Unicode.GetString(b, 2, b.Length - 2);
            if (b.Length >= 2 && b[0] == 0xFE && b[1] == 0xFF) return Encoding.BigEndianUnicode.GetString(b, 2, b.Length - 2);
            int start = b.Length >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF ? 3 : 0;
            int zeros = 0;
            for (int i = start; i < Math.Min(b.Length, 4096); i++) if (b[i] == 0) zeros++;
            if (zeros > 0) return null; // binary
            string utf8 = new UTF8Encoding(false, false).GetString(b, start, b.Length - start);
            int bad = 0;
            foreach (char c in utf8) if (c == '�') bad++;
            if (bad > utf8.Length / 50 + 2)
            {
                // Probably a legacy Cyrillic encoding (Windows-1251).
                try
                {
                    Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
                    return Encoding.GetEncoding(1251).GetString(b, start, b.Length - start);
                }
                catch (Exception) { }
            }
            return utf8;
        }

        private static readonly Regex Para = new Regex("</w:p>", RegexOptions.Compiled);
        private static readonly Regex Tag = new Regex("<[^>]+>", RegexOptions.Compiled);

        private static string Docx(string path)
        {
            using (var zip = ZipFile.OpenRead(path))
            {
                var e = zip.GetEntry("word/document.xml");
                if (e == null) return null;
                string xml;
                using (var r = new StreamReader(e.Open(), Encoding.UTF8)) xml = r.ReadToEnd();
                string text = Tag.Replace(Para.Replace(xml, "\n"), "");
                text = System.Net.WebUtility.HtmlDecode(text);
                text = Regex.Replace(text, "\n{3,}", "\n\n").Trim();
                if (text.Length > MaxChars) text = text.Substring(0, MaxChars);
                return text.Length == 0 ? null : text;
            }
        }
    }
}
