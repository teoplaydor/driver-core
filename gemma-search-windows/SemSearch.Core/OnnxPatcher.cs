using System;
using System.IO;
using System.Text;

namespace SemSearch.Core
{
    /// <summary>
    /// Copies an ONNX graph with accuracy_level=4 on every 4-bit MatMulNBits node, so ONNX Runtime computes
    /// in int8 instead of de-quantizing to fp32. Weights stay in the shared external .onnx_data file.
    /// Minimal protobuf rewrite: ModelProto.graph(7) → GraphProto.node(1) → NodeProto op_type(4)/attribute(5).
    /// </summary>
    public static class OnnxPatcher
    {
        public static int SetMatMulNBitsAccuracy(string input, string output, int level)
        {
            byte[] model = File.ReadAllBytes(input);
            int count = 0;
            byte[] patched = Rewrite(model, 0, model.Length, 7, (b, f, t) => Rewrite(b, f, t, 1, (b2, f2, t2) => Node(b2, f2, t2, level, ref count)));
            string tmp = output + ".tmp";
            File.WriteAllBytes(tmp, patched);
            File.Copy(tmp, output, true);
            File.Delete(tmp);
            return count;
        }

        private delegate byte[] Sub(byte[] b, int from, int to);

        private static byte[] Rewrite(byte[] b, int from, int to, int fieldToRewrite, Sub sub)
        {
            var o = new MemoryStream(to - from + 256);
            int pos = from;
            while (pos < to)
            {
                int start = pos;
                long key = Varint(b, ref pos);
                int field = (int)(key >> 3), wire = (int)(key & 7);
                if (field == fieldToRewrite && wire == 2)
                {
                    int len = (int)Varint(b, ref pos);
                    byte[] inner = sub(b, pos, pos + len);
                    pos += len;
                    WriteLen(o, field, inner);
                }
                else
                {
                    Skip(b, ref pos, wire);
                    o.Write(b, start, pos - start);
                }
            }
            return o.ToArray();
        }

        private static byte[] Node(byte[] b, int from, int to, int level, ref int count)
        {
            bool target = false;
            int pos = from;
            while (pos < to)
            {
                long key = Varint(b, ref pos);
                int field = (int)(key >> 3), wire = (int)(key & 7);
                if (field == 4 && wire == 2)
                {
                    int len = (int)Varint(b, ref pos);
                    target = Encoding.UTF8.GetString(b, pos, len) == "MatMulNBits";
                    pos += len;
                }
                else Skip(b, ref pos, wire);
            }
            var o = new MemoryStream(to - from + 32);
            if (!target)
            {
                o.Write(b, from, to - from);
                return o.ToArray();
            }
            pos = from;
            while (pos < to)
            {
                int start = pos;
                long key = Varint(b, ref pos);
                int field = (int)(key >> 3), wire = (int)(key & 7);
                if (field == 5 && wire == 2)
                {
                    int len = (int)Varint(b, ref pos);
                    bool isAccuracy = AttributeName(b, pos, pos + len) == "accuracy_level";
                    pos += len;
                    if (!isAccuracy) o.Write(b, start, pos - start);
                }
                else
                {
                    Skip(b, ref pos, wire);
                    o.Write(b, start, pos - start);
                }
            }
            var attr = new MemoryStream();
            WriteLen(attr, 1, Encoding.UTF8.GetBytes("accuracy_level"));
            WriteVarint(attr, 3 << 3);
            WriteVarint(attr, level);
            WriteVarint(attr, 20 << 3);
            WriteVarint(attr, 2); // AttributeProto.INT
            WriteLen(o, 5, attr.ToArray());
            count++;
            return o.ToArray();
        }

        private static string AttributeName(byte[] b, int from, int to)
        {
            int pos = from;
            while (pos < to)
            {
                long key = Varint(b, ref pos);
                int field = (int)(key >> 3), wire = (int)(key & 7);
                if (field == 1 && wire == 2)
                {
                    int len = (int)Varint(b, ref pos);
                    return Encoding.UTF8.GetString(b, pos, len);
                }
                Skip(b, ref pos, wire);
            }
            return null;
        }

        private static long Varint(byte[] b, ref int pos)
        {
            long v = 0;
            int shift = 0;
            while (true)
            {
                int x = b[pos++];
                v |= (long)(x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new InvalidDataException("bad varint");
            }
        }

        private static void Skip(byte[] b, ref int pos, int wire)
        {
            switch (wire)
            {
                case 0: Varint(b, ref pos); break;
                case 1: pos += 8; break;
                case 2:
                {
                    // Read the length first: in "pos += Varint(ref pos)" C# would add to the old pos.
                    long len = Varint(b, ref pos);
                    pos += (int)len;
                    break;
                }
                case 5: pos += 4; break;
                default: throw new InvalidDataException("unsupported wire type " + wire);
            }
        }

        private static void WriteVarint(Stream o, long v)
        {
            while ((v & ~0x7fL) != 0)
            {
                o.WriteByte((byte)((v & 0x7f) | 0x80));
                v = (long)((ulong)v >> 7);
            }
            o.WriteByte((byte)v);
        }

        private static void WriteLen(Stream o, int field, byte[] data)
        {
            WriteVarint(o, ((long)field << 3) | 2);
            WriteVarint(o, data.Length);
            o.Write(data, 0, data.Length);
        }
    }
}
