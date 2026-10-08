import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import io.github.teoplaydor.semsearch.core.Tombstone;

/**
 * The tombstone of a native crash of the NPU process, as Android 12+ gives it (protobuf, AOSP tombstone.proto)
 * and as older ones did (text): signal, abort message, the crashing thread's stack (not another thread's), the
 * process's last log lines.
 */
public class TombstoneTest {
    static int bad;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) bad++;
    }

    static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    static void num(ByteArrayOutputStream o, int field, long v) {
        varint(o, (long) field << 3);
        varint(o, v);
    }

    static void bytes(ByteArrayOutputStream o, int field, byte[] b) {
        varint(o, ((long) field << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }

    static void str(ByteArrayOutputStream o, int field, String s) {
        bytes(o, field, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] frame(String file, String fn, long off) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        num(f, 1, 0x4f1c8);
        str(f, 4, fn);
        num(f, 5, off);
        str(f, 6, file);
        return f.toByteArray();
    }

    static byte[] thread(int id, String name, byte[]... frames) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        num(t, 1, id);
        str(t, 2, name);
        for (byte[] f : frames) bytes(t, 4, f);
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        num(e, 1, id);
        bytes(e, 2, t.toByteArray());
        return e.toByteArray();
    }

    static byte[] log(int prio, String tag, String msg) {
        ByteArrayOutputStream l = new ByteArrayOutputStream();
        str(l, 1, "10-08 14:02:11.123");
        num(l, 4, prio);
        str(l, 5, tag);
        str(l, 6, msg);
        return l.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        num(t, 1, 3); // arch
        str(t, 2, "samsung/…/release-keys");
        num(t, 5, 4321);
        num(t, 6, 4400); // the crashing thread
        ByteArrayOutputStream sig = new ByteArrayOutputStream();
        num(sig, 1, 6);
        str(sig, 2, "SIGABRT");
        num(sig, 3, -6 & 0xffffffffL);
        str(sig, 4, "SI_TKILL");
        bytes(t, 10, sig.toByteArray());
        str(t, 14, "terminating due to uncaught exception of type std::bad_alloc: std::bad_alloc");
        bytes(t, 16, thread(4321, "main", frame("/system/lib64/libc.so", "__epoll_pwait", 8)));
        bytes(t, 16, thread(4400, "npu-binder", frame("/apex/com.android.runtime/lib64/bionic/libc.so", "abort", 168),
                frame("/data/app/~~x/files/qnn/lib/libQnnHtpPrepare.so", "_ZN4hnnx9Allocator5allocEm", 52),
                frame("/data/app/~~x/files/qnn/lib/libQnnHtp.so", "", 0)));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        str(buf, 1, "main");
        bytes(buf, 2, log(2, "onnxruntime", "QnnDsp <V> Prepare: op q::MatMul 12x2520x2520"));
        bytes(buf, 2, log(6, "onnxruntime", "QnnDsp <E> Out of memory for spill-fill buffer"));
        bytes(t, 18, buf.toByteArray());
        Tombstone ts = Tombstone.parse(t.toByteArray());
        String s = ts == null ? "null" : ts.summary(8, 6);
        System.out.println("  " + s.replace("\n", "\n  "));
        check(ts != null && ts.signal.equals("SIGABRT (SI_TKILL)") && ts.abortMessage.contains("std::bad_alloc"), "protobuf: signal and abort message");
        check(ts != null && ts.frames.size() == 3 && ts.frames.get(0).equals("libc.so abort+168")
                        && ts.frames.get(1).equals("libQnnHtpPrepare.so _ZN4hnnx9Allocator5allocEm+52") && ts.frames.get(2).startsWith("libQnnHtp.so pc 0x"),
                "protobuf: the crashing thread's stack, not the main thread's: " + (ts == null ? "" : ts.frames));
        check(ts != null && ts.log.size() == 2 && ts.log.get(1).equals("E/onnxruntime: QnnDsp <E> Out of memory for spill-fill buffer"),
                "protobuf: the last log lines with their priority");

        String text = "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n"
                + "pid: 4321, tid: 4400, name: npu-binder  >>> io.github.teoplaydor.semsearch:npu <<<\n"
                + "signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\n"
                + "Cause: null pointer dereference\n"
                + "backtrace:\n"
                + "      #00 pc 00000000001a2b3c  /data/app/x/files/qnn/lib/libQnnHtp.so (BuildId: 1234)\n"
                + "      #01 pc 0000000000012345  /data/app/x/files/qnn/lib/libonnxruntime.so (OrtSessionCreate+40) (BuildId: 99)\n"
                + "\nstack:\n      #00 something else\n";
        Tombstone tt = Tombstone.parse(text.getBytes(StandardCharsets.UTF_8));
        check(tt != null && tt.signal.startsWith("11 (SIGSEGV)") && tt.cause.equals("null pointer dereference") && tt.frames.size() == 2
                        && tt.frames.get(0).equals("libQnnHtp.so pc 0x00000000001a2b3c") && tt.frames.get(1).equals("libonnxruntime.so OrtSessionCreate+40"),
                "text: signal, cause, backtrace: " + (tt == null ? "null" : tt.frames));
        check(Tombstone.parse("nothing useful".getBytes(StandardCharsets.UTF_8)) == null, "nothing to read: null");
        if (bad > 0) {
            System.out.println(bad + " FAILED");
            System.exit(1);
        }
        System.out.println("tombstone: all ok");
    }
}
