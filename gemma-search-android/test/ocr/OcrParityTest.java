import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.MiniJson;
import io.github.teoplaydor.semsearch.core.Ocr;

/**
 * Text on pictures (core/Ocr: PaddleOCR's models on ONNX Runtime) against RapidOCR 3.10 with the same models
 * (test/ocr/reference_ocr.py): the same pieces of text in the same order, their boxes within a few pixels; the text
 * put together line by line; tickets and receipts readable for the reminders found on photos.
 * usage: OcrParityTest <dir with the pictures and reference.json> <models dir>
 */
public class OcrParityTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static ImagePreprocessor.Source source(final BufferedImage img) {
        return new ImagePreprocessor.Source() {
            @Override
            public int width() {
                return img.getWidth();
            }

            @Override
            public int height() {
                return img.getHeight();
            }

            @Override
            public int[] argb(int w, int h) {
                if (w != img.getWidth() || h != img.getHeight()) throw new IllegalArgumentException("only at its own size here");
                return img.getRGB(0, 0, w, h, null, 0, w);
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]), models = new File(args[1]);
        Map<String, Object> ref = MiniJson.obj(MiniJson.parse(new String(Files.readAllBytes(new File(dir, "reference.json").toPath()), "UTF-8")));
        Ocr ocr = new Ocr(new File(models, "PP-OCRv6_det_small.onnx"), new File(models, "PP-OCRv6_rec_small.onnx"), new File(dir, "dict.txt"), 4);
        for (String name : new String[]{"ticket", "receipt", "screenshot", "turned", "dark"}) {
            BufferedImage img = ImageIO.read(new File(dir, name + ".png"));
            long t0 = System.currentTimeMillis();
            List<Ocr.Line> got = ocr.read(source(img));
            long ms = System.currentTimeMillis() - t0;
            List<Object> want = MiniJson.arr(ref.get(name));
            List<String> gotText = new ArrayList<String>(), wantText = new ArrayList<String>();
            for (Ocr.Line l : got) gotText.add(l.text);
            double worst = 0;
            for (int i = 0; i < want.size(); i++) {
                Map<String, Object> w = (Map<String, Object>) want.get(i);
                wantText.add((String) w.get("text"));
                if (i < got.size()) {
                    List<Object> box = MiniJson.arr(w.get("box"));
                    for (int k = 0; k < 8; k++) worst = Math.max(worst, Math.abs(((Number) box.get(k)).doubleValue() - got.get(i).box[k]));
                }
            }
            check(gotText.equals(wantText) && worst <= 4, name + ": " + gotText.size() + " pieces, the same text, boxes within "
                    + Math.round(worst) + " px (" + ms + " ms)" + (gotText.equals(wantText) ? "" : "\n     got  " + gotText + "\n     want " + wantText));
        }
        String ticket = Ocr.text(ocr.read(source(ImageIO.read(new File(dir, "ticket.png")))));
        check(ticket.startsWith("ELECTRONIC TICKET\nTrain 024A MOSCOW - KAZAN\nDeparture 21.10.2026 14:35"), "line by line: "
                + ticket.replace("\n", " | "));
        // read, then understood: the ticket's departure and the receipt's warranty for reminders
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Europe/Moscow");
        java.util.Calendar now = java.util.Calendar.getInstance(tz);
        now.clear();
        now.set(2026, java.util.Calendar.OCTOBER, 14, 15, 20, 0);
        List<io.github.teoplaydor.semsearch.core.PhotoEvent> train = io.github.teoplaydor.semsearch.core.PhotoEvent.find(ticket,
                now.getTimeInMillis(), now.getTimeInMillis(), tz);
        String receipt = Ocr.text(ocr.read(source(ImageIO.read(new File(dir, "receipt.png")))));
        List<io.github.teoplaydor.semsearch.core.PhotoEvent> warranty = io.github.teoplaydor.semsearch.core.PhotoEvent.find(receipt,
                now.getTimeInMillis(), now.getTimeInMillis(), tz);
        check(train.size() == 1 && train.get(0).title.equals("Поезд 024A Moscow — Kazan")
                        && io.github.teoplaydor.semsearch.core.PhotoEvent.dayMonth(train.get(0).at, tz, false).equals("21 октября")
                        && warranty.size() == 1 && warranty.get(0).title.startsWith("Гарантия")
                        && warranty.get(0).title.endsWith("до 12 марта 2028"),
                "from the pictures: " + (train.isEmpty() ? "-" : train.get(0).title) + "; " + (warranty.isEmpty() ? "-" : warranty.get(0).title));
        ocr.close();
        System.out.println(bad == 0 ? "OCR OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
