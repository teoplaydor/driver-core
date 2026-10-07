namespace SemSearch.Core
{
    /// <summary>
    /// JPEG EXIF orientation without an image library: not every GDI+ exposes it (Wine's does not), and only the
    /// first IFD of the APP1 segment is needed.
    /// </summary>
    public static class ExifOrientation
    {
        /// <summary>EXIF orientation (1..8) read straight from a JPEG's APP1 segment, 0 if absent or unreadable.</summary>
        public static int FromJpeg(byte[] d)
        {
            if (d.Length < 4 || d[0] != 0xFF || d[1] != 0xD8) return 0;
            int p = 2;
            while (p + 4 <= d.Length && d[p] == 0xFF)
            {
                int marker = d[p + 1];
                if (marker == 0xFF) { p++; continue; } // fill byte
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { p += 2; continue; } // no length
                if (marker == 0xDA || marker == 0xD9) break; // image data starts: no more metadata
                int len = (d[p + 2] << 8) | d[p + 3];
                if (len < 2 || p + 2 + len > d.Length) break;
                if (marker == 0xE1 && len >= 16 && d[p + 4] == 'E' && d[p + 5] == 'x' && d[p + 6] == 'i' && d[p + 7] == 'f'
                    && d[p + 8] == 0 && d[p + 9] == 0)
                    return TiffOrientation(d, p + 10, p + 2 + len);
                p += 2 + len;
            }
            return 0;
        }

        private static int TiffOrientation(byte[] d, int t, int end)
        {
            if (t + 8 > end) return 0;
            bool le = d[t] == 'I' && d[t + 1] == 'I';
            if (!le && !(d[t] == 'M' && d[t + 1] == 'M')) return 0;
            int U16(long o) => le ? d[o] | d[o + 1] << 8 : d[o] << 8 | d[o + 1];
            long U32(long o) => le ? (uint)(d[o] | d[o + 1] << 8 | d[o + 2] << 16 | d[o + 3] << 24)
                                   : (uint)(d[o] << 24 | d[o + 1] << 16 | d[o + 2] << 8 | d[o + 3]);
            long ifd = t + U32(t + 4);
            if (ifd + 2 > end) return 0;
            int n = U16(ifd);
            for (int i = 0; i < n; i++)
            {
                long e = ifd + 2 + 12L * i;
                if (e + 12 > end) return 0;
                if (U16(e) == 0x0112)
                {
                    int v = U16(e + 8);
                    return v >= 1 && v <= 8 ? v : 0;
                }
            }
            return 0;
        }
    }
}
