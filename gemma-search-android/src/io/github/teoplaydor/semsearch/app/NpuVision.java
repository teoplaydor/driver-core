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
    private ServiceConnection conn;
    private int pid;
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
                if (b == null) throw new Crashed("NPU-процесс завершился");
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(NpuService.DESCRIPTOR);
                    w.write(data);
                    b.transact(code, data, reply, 0);
                } catch (DeadObjectException e) {
                    reply.recycle();
                    throw new Crashed("NPU-процесс упал (сбой драйвера NPU)");
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
            if (pid > 0) android.os.Process.killProcess(pid);
            throw new IOException("NPU не ответил за " + timeoutSec + " с");
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
