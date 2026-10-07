using System;
using System.Collections.Generic;
using System.IO;

namespace SemSearch.Core
{
    /// <summary>
    /// Pixel size from the file header (JPEG, PNG, GIF, BMP, TIFF) without decoding: a few hundred bytes per file, so
    /// the size filter can run over a whole photo library while scanning.
    /// </summary>
    public static class ImageSize
    {
        /// <summary>Width and height, or (0, 0) when the header cannot be read.</summary>
        public static (int W, int H) Read(string path)
        {
            try
            {
                using (var s = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 4096))
                    return Read(s);
            }
            catch (Exception)
            {
                return (0, 0);
            }
        }

        public static (int W, int H) Read(Stream s)
        {
            var b = new byte[32];
            int n = Fill(s, b, 0, 32);
            if (n >= 24 && b[0] == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R')
                return (BE32(b, 16), BE32(b, 20));
            if (n >= 10 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F')
                return (b[6] | b[7] << 8, b[8] | b[9] << 8);
            if (n >= 26 && b[0] == 'B' && b[1] == 'M')
            {
                int dib = LE32(b, 14);
                if (dib == 12) return (b[18] | b[19] << 8, b[20] | b[21] << 8);
                return (Math.Abs(LE32(b, 18)), Math.Abs(LE32(b, 22)));
            }
            if (n >= 8 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42)))
                return Tiff(s, b[0] == 'I');
            if (n >= 4 && b[0] == 0xFF && b[1] == 0xD8)
                return Jpeg(s);
            return (0, 0);
        }

        /// <summary>Walks the JPEG markers by their lengths (EXIF thumbnails and XMP can be tens of KB) up to a SOFn.</summary>
        private static (int W, int H) Jpeg(Stream s)
        {
            var b = new byte[9];
            long p = 2;
            for (int guard = 0; guard < 1000; guard++)
            {
                s.Position = p;
                if (Fill(s, b, 0, 2) < 2 || b[0] != 0xFF) return (0, 0);
                int m = b[1];
                if (m == 0xFF) { p++; continue; } // fill byte
                if (m == 0x01 || (m >= 0xD0 && m <= 0xD8)) { p += 2; continue; } // no length
                if (m == 0xD9 || m == 0xDA) return (0, 0); // end of image / scan data before any frame header
                if (Fill(s, b, 2, 2) < 2) return (0, 0);
                int len = b[2] << 8 | b[3];
                if (len < 2) return (0, 0);
                bool sof = m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
                if (sof)
                {
                    if (Fill(s, b, 4, 5) < 5) return (0, 0);
                    return (b[7] << 8 | b[8], b[5] << 8 | b[6]);
                }
                p += 2 + len;
            }
            return (0, 0);
        }

        private static (int W, int H) Tiff(Stream s, bool le)
        {
            var b = new byte[12];
            s.Position = 4;
            if (Fill(s, b, 0, 4) < 4) return (0, 0);
            long ifd = (uint)(le ? LE32(b, 0) : BE32(b, 0));
            s.Position = ifd;
            if (Fill(s, b, 0, 2) < 2) return (0, 0);
            int count = le ? b[0] | b[1] << 8 : b[0] << 8 | b[1];
            int w = 0, h = 0;
            for (int i = 0; i < count && i < 512; i++)
            {
                if (Fill(s, b, 0, 12) < 12) break;
                int tag = le ? b[0] | b[1] << 8 : b[0] << 8 | b[1];
                int type = le ? b[2] | b[3] << 8 : b[2] << 8 | b[3];
                int value = type == 3 ? (le ? b[8] | b[9] << 8 : b[8] << 8 | b[9]) : (le ? LE32(b, 8) : BE32(b, 8));
                if (tag == 256) w = value;
                else if (tag == 257) h = value;
                if (w > 0 && h > 0) break;
            }
            return (w, h);
        }

        private static int Fill(Stream s, byte[] b, int off, int count)
        {
            int got = 0;
            while (got < count)
            {
                int r = s.Read(b, off + got, count - got);
                if (r <= 0) break;
                got += r;
            }
            return got;
        }

        private static int BE32(byte[] b, int o) => b[o] << 24 | b[o + 1] << 16 | b[o + 2] << 8 | b[o + 3];
        private static int LE32(byte[] b, int o) => b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24;
    }

    /// <summary>
    /// Remembers header sizes by path + file size + mtime (sizes.bin next to the index): a library is read once,
    /// later scans and filter changes cost nothing.
    /// </summary>
    public sealed class ImageSizeCache
    {
        private readonly string file;
        private readonly Dictionary<string, (long Size, long Mtime, int W, int H)> map =
            new Dictionary<string, (long, long, int, int)>(StringComparer.OrdinalIgnoreCase);
        private readonly object sync = new object();
        private bool dirty;

        public ImageSizeCache(string file)
        {
            this.file = file;
            try
            {
                if (!File.Exists(file)) return;
                using (var r = new BinaryReader(File.OpenRead(file)))
                {
                    if (r.ReadInt32() != 0x43445353) return; // "SSDC"
                    int n = r.ReadInt32();
                    for (int i = 0; i < n; i++) map[r.ReadString()] = (r.ReadInt64(), r.ReadInt64(), r.ReadInt32(), r.ReadInt32());
                }
            }
            catch (Exception)
            {
                map.Clear(); // damaged cache: sizes are simply read again
            }
        }

        public int Count
        {
            get { lock (sync) return map.Count; }
        }

        /// <summary>Cached size only (never touches the file): for filtering search results.</summary>
        public bool TryGet(string path, long size, long mtime, out int w, out int h)
        {
            lock (sync)
            {
                if (map.TryGetValue(path, out var e) && e.Size == size && e.Mtime == mtime)
                {
                    w = e.W;
                    h = e.H;
                    return true;
                }
            }
            w = h = 0;
            return false;
        }

        public (int W, int H) Get(string path, long size, long mtime)
        {
            lock (sync)
                if (map.TryGetValue(path, out var e) && e.Size == size && e.Mtime == mtime) return (e.W, e.H);
            var wh = ImageSize.Read(path);
            lock (sync)
            {
                map[path] = (size, mtime, wh.W, wh.H);
                dirty = true;
            }
            return wh;
        }

        /// <summary>Drops entries of files that are gone (call with every image path the last scan saw).</summary>
        public void Keep(ICollection<string> paths)
        {
            lock (sync)
            {
                var gone = new List<string>();
                foreach (var k in map.Keys) if (!paths.Contains(k)) gone.Add(k);
                foreach (var k in gone) map.Remove(k);
                if (gone.Count > 0) dirty = true;
            }
        }

        public void Save()
        {
            lock (sync)
            {
                if (!dirty) return;
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(file));
                    string tmp = file + ".tmp";
                    using (var w = new BinaryWriter(File.Create(tmp)))
                    {
                        w.Write(0x43445353);
                        w.Write(map.Count);
                        foreach (var kv in map)
                        {
                            w.Write(kv.Key);
                            w.Write(kv.Value.Size);
                            w.Write(kv.Value.Mtime);
                            w.Write(kv.Value.W);
                            w.Write(kv.Value.H);
                        }
                    }
                    File.Move(tmp, file, true);
                    dirty = false;
                }
                catch (Exception)
                {
                    // read-only profile: sizes are read again next time
                }
            }
        }
    }
}
