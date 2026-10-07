using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace SemSearch.Core
{
    /// <summary>
    /// Vector index of files on disk: in memory for brute-force cosine search, persisted as an append-only
    /// log (upserts and deletions) that is compacted when it grows stale.
    /// </summary>
    public sealed class VectorIndex
    {
        public const byte KindImage = 0, KindDocument = 1, KindVideo = 2;
        public static readonly int[] Dims = { 128, 256, 512, 768 };
        private const int Magic = 0x58495353; // "SSIX"
        private const byte OpPut = 1, OpDelete = 2;

        public sealed class Item
        {
            public string Path;
            public byte Kind;
            public long Size, MtimeTicks;
            public float[] Emb;
            internal float[] Norms = new float[Dims.Length];

            internal void ComputeNorms()
            {
                for (int i = 0; i < Dims.Length; i++) Norms[i] = VectorMath.PrefixNorm(Emb, Math.Min(Dims[i], Emb.Length));
            }
        }

        public sealed class Hit
        {
            public Item Item;
            public float Score;
        }

        private readonly string file;
        private readonly Dictionary<string, Item> items = new Dictionary<string, Item>(StringComparer.OrdinalIgnoreCase);
        private readonly object sync = new object();
        private int logRecords;

        public VectorIndex(string file)
        {
            this.file = file;
            if (File.Exists(file)) Load();
        }

        private void Load()
        {
            using (var r = new BinaryReader(File.OpenRead(file), Encoding.UTF8))
            {
                if (r.BaseStream.Length < 8 || r.ReadInt32() != Magic || r.ReadInt32() != 1) return;
                while (r.BaseStream.Position < r.BaseStream.Length)
                {
                    try
                    {
                        byte op = r.ReadByte();
                        string path = r.ReadString();
                        if (op == OpDelete)
                        {
                            items.Remove(path);
                        }
                        else
                        {
                            var it = new Item { Path = path, Kind = r.ReadByte(), Size = r.ReadInt64(), MtimeTicks = r.ReadInt64() };
                            int d = r.ReadInt32();
                            it.Emb = new float[d];
                            for (int i = 0; i < d; i++) it.Emb[i] = r.ReadSingle();
                            it.ComputeNorms();
                            items[path] = it;
                        }
                        logRecords++;
                    }
                    catch (EndOfStreamException)
                    {
                        break; // torn last record after a crash: ignore it
                    }
                }
            }
        }

        public int Count(byte kind)
        {
            lock (sync)
            {
                int n = 0;
                foreach (var it in items.Values) if (it.Kind == kind) n++;
                return n;
            }
        }

        /// <summary>True if the file is indexed with the same size and modification time.</summary>
        public bool IsCurrent(string path, long size, long mtimeTicks)
        {
            lock (sync) return items.TryGetValue(path, out var it) && it.Size == size && it.MtimeTicks == mtimeTicks;
        }

        public void Put(string path, byte kind, long size, long mtimeTicks, float[] emb)
        {
            var it = new Item { Path = path, Kind = kind, Size = size, MtimeTicks = mtimeTicks, Emb = emb };
            it.ComputeNorms();
            lock (sync)
            {
                items[path] = it;
                Append(w =>
                {
                    w.Write(OpPut);
                    w.Write(path);
                    w.Write(kind);
                    w.Write(size);
                    w.Write(mtimeTicks);
                    w.Write(emb.Length);
                    foreach (float x in emb) w.Write(x);
                });
            }
        }

        public void Remove(string path)
        {
            lock (sync)
            {
                if (!items.Remove(path)) return;
                Append(w =>
                {
                    w.Write(OpDelete);
                    w.Write(path);
                });
            }
        }

        public List<string> Paths()
        {
            lock (sync) return new List<string>(items.Keys);
        }

        public void Clear()
        {
            lock (sync)
            {
                items.Clear();
                if (File.Exists(file)) File.Delete(file);
                logRecords = 0;
            }
        }

        private void Append(Action<BinaryWriter> write)
        {
            bool fresh = !File.Exists(file);
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(file)));
            using (var w = new BinaryWriter(new FileStream(file, FileMode.Append, FileAccess.Write), Encoding.UTF8))
            {
                if (fresh)
                {
                    w.Write(Magic);
                    w.Write(1);
                }
                write(w);
            }
            if (++logRecords > 1000 && logRecords > items.Count * 2) Compact();
        }

        /// <summary>Rewrites the log with only the live items.</summary>
        public void Compact()
        {
            lock (sync)
            {
                string tmp = file + ".tmp";
                using (var w = new BinaryWriter(File.Create(tmp), Encoding.UTF8))
                {
                    w.Write(Magic);
                    w.Write(1);
                    foreach (var it in items.Values)
                    {
                        w.Write(OpPut);
                        w.Write(it.Path);
                        w.Write(it.Kind);
                        w.Write(it.Size);
                        w.Write(it.MtimeTicks);
                        w.Write(it.Emb.Length);
                        foreach (float x in it.Emb) w.Write(x);
                    }
                }
                File.Copy(tmp, file, true);
                File.Delete(tmp);
                logRecords = items.Count;
            }
        }

        /// <summary>Cosine search over the first <paramref name="dims"/> components (Matryoshka truncation).</summary>
        /// <param name="keep">optional filter on items (e.g. the picture size filter); null keeps everything</param>
        public List<Hit> Search(float[] qImages, float[] qDocs, int dims, bool images, bool docs, int limit, string exclude = null,
                                Func<Item, bool> keep = null)
        {
            int di = Array.IndexOf(Dims, dims);
            int d = Math.Min(dims, Math.Min(qImages.Length, qDocs.Length));
            float qin = VectorMath.PrefixNorm(qImages, d), qdn = VectorMath.PrefixNorm(qDocs, d);
            var hits = new List<Hit>();
            lock (sync)
            {
                foreach (var it in items.Values)
                {
                    if (it.Emb.Length < d || (exclude != null && string.Equals(it.Path, exclude, StringComparison.OrdinalIgnoreCase))) continue;
                    bool isDoc = it.Kind == KindDocument;
                    if ((isDoc && !docs) || (!isDoc && !images)) continue;
                    if (keep != null && !keep(it)) continue;
                    float[] q = isDoc ? qDocs : qImages;
                    float qn = isDoc ? qdn : qin;
                    float bn = di >= 0 && Dims[di] == d ? it.Norms[di] : VectorMath.PrefixNorm(it.Emb, d);
                    float den = qn * bn;
                    hits.Add(new Hit { Item = it, Score = den > 0 ? VectorMath.Dot(q, it.Emb, d) / den : 0 });
                }
            }
            hits.Sort((a, b) => b.Score.CompareTo(a.Score));
            if (hits.Count > limit) hits.RemoveRange(limit, hits.Count - limit);
            return hits;
        }

        /// <summary>Snapshot of the items of one kind.</summary>
        public List<Item> Items(byte kind)
        {
            lock (sync)
            {
                var list = new List<Item>();
                foreach (var it in items.Values) if (it.Kind == kind) list.Add(it);
                return list;
            }
        }

        public Item Get(string path)
        {
            lock (sync) return items.TryGetValue(path, out var it) ? it : null;
        }
    }
}
