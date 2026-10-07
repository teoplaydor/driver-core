using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using SemSearch.Core;

namespace SemSearch
{
    /// <summary>Photos via GDI+: EXIF orientation, high-quality bicubic resize, thumbnails.</summary>
    internal sealed class ImageFile : IImageSource, IDisposable
    {
        public static readonly HashSet<string> Extensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            { ".jpg", ".jpeg", ".jfif", ".png", ".bmp", ".gif", ".tif", ".tiff" };

        private readonly Image img;
        private readonly RotateFlipType orient;

        private ImageFile(Image img, int exifOrientation = 0)
        {
            this.img = img;
            orient = exifOrientation > 0 ? ToRotateFlip(exifOrientation) : Orientation(img);
        }

        public static ImageFile Open(string path)
        {
            // Read into memory: GDI+ keeps files locked otherwise.
            byte[] data = File.ReadAllBytes(path);
            return new ImageFile(Image.FromStream(new MemoryStream(data), false, false), ExifOrientation.FromJpeg(data));
        }

        private static bool Swaps(RotateFlipType t) =>
            t == RotateFlipType.Rotate90FlipNone || t == RotateFlipType.Rotate270FlipNone
            || t == RotateFlipType.Rotate90FlipX || t == RotateFlipType.Rotate270FlipX;

        public int Width => Swaps(orient) ? img.Height : img.Width;
        public int Height => Swaps(orient) ? img.Width : img.Height;

        /// <summary>Upright ARGB pixels at exactly w x h.</summary>
        public int[] Argb(int w, int h)
        {
            int dw = Swaps(orient) ? h : w, dh = Swaps(orient) ? w : h;
            using (var bmp = new Bitmap(dw, dh, PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(bmp))
                using (var attr = new ImageAttributes())
                {
                    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                    g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                    g.CompositingQuality = CompositingQuality.HighQuality;
                    g.Clear(Color.White); // transparent PNGs → white, like Pillow's convert("RGB") on a typical background
                    attr.SetWrapMode(WrapMode.TileFlipXY); // no dark fringe at the borders
                    g.DrawImage(img, new Rectangle(0, 0, dw, dh), 0, 0, img.Width, img.Height, GraphicsUnit.Pixel, attr);
                }
                if (orient != RotateFlipType.RotateNoneFlipNone) bmp.RotateFlip(orient);
                var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                try
                {
                    var px = new int[w * h];
                    for (int y = 0; y < h; y++) Marshal.Copy(data.Scan0 + y * data.Stride, px, y * w, w);
                    return px;
                }
                finally
                {
                    bmp.UnlockBits(data);
                }
            }
        }

        /// <summary>Thumbnail fitting a size x size box (upright).</summary>
        public Bitmap Thumbnail(int size)
        {
            double s = Math.Min((double)size / Width, (double)size / Height);
            int w = Math.Max(1, (int)Math.Round(Width * s)), h = Math.Max(1, (int)Math.Round(Height * s));
            int dw = Swaps(orient) ? h : w, dh = Swaps(orient) ? w : h;
            var bmp = new Bitmap(dw, dh, PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(bmp))
            {
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.Clear(Color.Transparent);
                g.DrawImage(img, 0, 0, dw, dh);
            }
            if (orient != RotateFlipType.RotateNoneFlipNone) bmp.RotateFlip(orient);
            return bmp;
        }

        private static RotateFlipType ToRotateFlip(int exif)
        {
            switch (exif)
            {
                case 2: return RotateFlipType.RotateNoneFlipX;
                case 3: return RotateFlipType.Rotate180FlipNone;
                case 4: return RotateFlipType.Rotate180FlipX;
                case 5: return RotateFlipType.Rotate90FlipX;
                case 6: return RotateFlipType.Rotate90FlipNone;
                case 7: return RotateFlipType.Rotate270FlipX;
                case 8: return RotateFlipType.Rotate270FlipNone;
                default: return RotateFlipType.RotateNoneFlipNone;
            }
        }

        /// <summary>EXIF orientation from GDI+ properties (TIFF, PNG eXIf, ...).</summary>
        private static RotateFlipType Orientation(Image img)
        {
            try
            {
                foreach (int id in img.PropertyIdList)
                    if (id == 0x0112) return ToRotateFlip(BitConverter.ToUInt16(img.GetPropertyItem(id).Value, 0));
            }
            catch (Exception) { }
            return RotateFlipType.RotateNoneFlipNone;
        }

        public void Dispose() => img.Dispose();
    }
}
