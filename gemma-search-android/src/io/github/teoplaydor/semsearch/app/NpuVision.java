package io.github.teoplaydor.semsearch.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.teoplaydor.semsearch.core.VisionRunner;

/**
 * The app's side of {@link NpuService}: starts the NPU process, hands it the QNN libraries and the rewritten
 * vision graph, and runs batches through a memory-mapped file in the cache. Calls have a deadline — the first
 * run of a token budget compiles the graph for the NPU (minutes), later ones take a fraction of a second; a
 * process that stops answering is killed. A crash there surfaces as {@link Crashed}.
 */
final class NpuVision implements VisionRunner {
    /** The NPU process died (driver crash): this variant should not be tried again. */
    static final class Crashed extends IOException {
        Crashed(String m) {
            super(m);
        }
    }

    private final Context ctx;
    private final ExecutorService caller = Executors.newSingleThreadExecutor();
    private final java.util.Set<Integer> compiled = new java.util.HashSet<Integer>();
    private volatile IBinder binder;
    /**
     * The process this object set up died. Android restarts a bound service, but the new process was never
     * given the graph: it is not used, every later call reports the death instead.
     */
    private volatile boolean dead;
    private volatile String death;
    private ServiceConnection conn;
    private volatile int pid;
    private final File io;
    private RandomAccessFile raf;
    private MappedByteBuffer map;

    NpuVision(Context c, File libDir, File graph) throws IOException {
        ctx = c.getApplicationContext();
        io = new File(ctx.getCacheDir(), "npu-io-" + System.nanoTime() + ".bin");
        final CountDownLatch latch = new CountDownLatch(1);
        conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder = service;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder = null;
                if (pid > 0) dead = true;
            }
        };
        if (!ctx.bindService(new Intent(ctx, NpuService.class), conn, Context.BIND_AUTO_CREATE)) {
            throw new IOException("NPU-процесс не запустился");
        }
        try {
            if (!latch.await(30, TimeUnit.SECONDS) || binder == null) throw new IOException("NPU-процесс не ответил");
        } catch (InterruptedException e) {
            throw new IOException("прервано");
        }
        final String lib = libDir.getAbsolutePath(), g = graph.getAbsolutePath();
        Parcel reply = call(NpuService.INIT, 120, new Writer() {
            @Override
            public void write(Parcel p) {
                p.writeString(lib);
                p.writeString(g);
            }
        });
        pid = reply.readInt();
        reply.recycle();
    }

    private interface Writer {
        void write(Parcel p);
    }

    /** One transaction with a deadline; the reply is positioned after the status. */
    private Parcel call(final int code, long timeoutSec, final Writer w) throws IOException {
        Future<Parcel> f = caller.submit(new Callable<Parcel>() {
            @Override
            public Parcel call() throws Exception {
                IBinder b = binder;
                if (dead || b == null) throw new Crashed(death());
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(NpuService.DESCRIPTOR);
                    w.write(data);
                    b.transact(code, data, reply, 0);
                } catch (DeadObjectException e) {
                    reply.recycle();
                    dead = true;
                    throw new Crashed(death());
                } finally {
                    data.recycle();
                }
                return reply;
            }
        });
        Parcel reply;
        try {
            reply = f.get(timeoutSec, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            String stage = stage();
            if (pid > 0) android.os.Process.killProcess(pid);
            dead = true;
            death = "NPU-процесс не ответил за " + timeoutSec + " с и остановлен" + (stage.isEmpty() ? "" : "; " + stage);
            throw new Crashed(death);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c != null ? c.toString() : e.toString());
        } catch (InterruptedException e) {
            throw new IOException("прервано");
        }
        if (reply.readInt() != NpuService.OK) {
            String msg = reply.readString();
            reply.recycle();
            throw new IOException("NPU: " + msg);
        }
        return reply;
    }

    /**
     * Why the NPU process is gone: what Android recorded about its end (Android 11+: low memory, a native crash
     * and its signal, its memory at the end) and what it was doing then (NpuService.stage).
     */
    private String death() {
        // not under this object's lock: run() holds it while the caller thread gets here
        synchronized (caller) {
            if (death == null) death = newDeath();
            return death;
        }
    }

    private String newDeath() {
        StringBuilder sb = new StringBuilder("NPU-процесс упал");
        String why = exitReason();
        if (!why.isEmpty()) sb.append(": ").append(why);
        String stage = stage();
        if (!stage.isEmpty()) sb.append("; в это время: ").append(stage);
        return sb.toString();
    }

    private String stage() {
        File f = NpuService.stageFile(ctx);
        if (!f.exists()) return "";
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Android's record of how the NPU process ended (written shortly after the death, so waited for). */
    private String exitReason() {
        if (pid <= 0 || android.os.Build.VERSION.SDK_INT < 30) return "";
        Object am = ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "";
        for (int i = 0; i < 20; i++) {
            try {
                // ActivityManager.getHistoricalProcessExitReasons (Android 11), by reflection: built against Android 6
                java.util.List<?> l = (java.util.List<?>) am.getClass().getMethod("getHistoricalProcessExitReasons",
                        String.class, int.class, int.class).invoke(am, ctx.getPackageName(), pid, 1);
                if (l != null && !l.isEmpty()) return describe(l.get(0));
            } catch (Exception e) {
                return "";
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        return "";
    }

    /** An ApplicationExitInfo in words (its REASON_* numbers). */
    static String describe(Object info) throws Exception {
        Class<?> c = info.getClass();
        int reason = (Integer) c.getMethod("getReason").invoke(info);
        int status = (Integer) c.getMethod("getStatus").invoke(info);
        String r;
        switch (reason) {
            case 3: // REASON_LOW_MEMORY
                r = "система закрыла его из-за нехватки памяти";
                break;
            case 5: // REASON_CRASH_NATIVE
                r = "сбой в машинном коде (ONNX Runtime или QNN)";
                break;
            case 4: // REASON_CRASH
                r = "исключение Java";
                break;
            case 2: // REASON_SIGNALED
                r = "убит сигналом " + status + (status == 9 ? " (обычно — система, при нехватке памяти)" : "");
                break;
            case 1: // REASON_EXIT_SELF
                r = "завершился сам, код " + status;
                break;
            case 6: // REASON_ANR
                r = "завис (ANR)";
                break;
            case 9: // REASON_EXCESSIVE_RESOURCE_USAGE
                r = "система закрыла его за чрезмерное потребление ресурсов";
                break;
            default:
                r = "код причины " + reason + ", статус " + status;
        }
        Object d = c.getMethod("getDescription").invoke(info);
        if (d != null && !d.toString().trim().isEmpty()) r += " (" + d.toString().trim() + ")";
        long rss = (Long) c.getMethod("getRss").invoke(info);
        if (rss > 0) r += ", память процесса в конце " + (rss / 1024) + " МБ";
        return r;
    }

    private void ensure(long bytes) throws IOException {
        if (raf != null && raf.length() >= bytes) return;
        if (raf == null) raf = new RandomAccessFile(io, "rw");
        raf.setLength(bytes);
        map = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, bytes);
        map.order(ByteOrder.nativeOrder());
    }

    @Override
    public synchronized float[] run(float[] pixels, long[] positions, final int batch, final int patches, final int patchDim)
            throws IOException {
        ensure(4L * pixels.length + 8L * positions.length);
        map.position(0);
        map.slice().order(ByteOrder.nativeOrder()).asFloatBuffer().put(pixels);
        map.position(4 * pixels.length);
        map.slice().order(ByteOrder.nativeOrder()).asLongBuffer().put(positions);
        // the first run of a budget compiles the graph for the NPU (when QNN fails, up to four times)
        long timeout = compiled.contains(patches) ? 120 : 2400;
        final String path = io.getAbsolutePath();
        Parcel reply = call(NpuService.RUN, timeout, new Writer() {
            @Override
            public void write(Parcel p) {
                p.writeString(path);
                p.writeInt(batch);
                p.writeInt(patches);
                p.writeInt(patchDim);
            }
        });
        int floats = reply.readInt();
        reply.recycle();
        compiled.add(patches);
        ensure(4L * floats);
        float[] out = new float[floats];
        map.position(0);
        map.slice().order(ByteOrder.nativeOrder()).asFloatBuffer().get(out);
        return out;
    }

    /** Where the NPU graph's nodes run (ONNX Runtime profile in the NPU process). */
    String profile(final int patches, final int patchDim) throws IOException {
        Parcel reply = call(NpuService.PROFILE, 2400, new Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(patches);
                p.writeInt(patchDim);
            }
        });
        String s = reply.readString();
        reply.recycle();
        return s;
    }

    /** Where the NPU's result goes wrong, for the last run's image (NpuService.scan). */
    String scan(final int patches, final int patchDim) throws IOException {
        Parcel reply = call(NpuService.SCAN, 1800, new Writer() {
            @Override
            public void write(Parcel p) {
                p.writeInt(patches);
                p.writeInt(patchDim);
            }
        });
        String s = reply.readString();
        reply.recycle();
        return s;
    }

    @Override
    public synchronized void close() {
        try {
            ctx.unbindService(conn);
        } catch (Exception ignored) {
            // already gone
        }
        caller.shutdownNow();
        try {
            if (raf != null) raf.close();
        } catch (IOException ignored) {
            // nothing to do
        }
        io.delete();
    }
}
