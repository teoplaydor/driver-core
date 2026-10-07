package io.github.teoplaydor.semsearch.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Summary of an ONNX Runtime profile (the Chrome-trace JSON that SessionOptions.enableProfiling writes):
 * for the last run of the session, which execution provider ran how many nodes, and which ops ran on the
 * CPU and for how long. With a GPU or NPU provider those CPU ops are the ones the accelerator could not
 * take; every switch between the devices costs a copy ("Memcpy" nodes).
 */
public final class OrtProfile {
    public static final String CPU = "CPUExecutionProvider";

    public static final class Op {
        public final String type;
        public int count;
        public long us;

        Op(String type) {
            this.type = type;
        }
    }

    public static final class Provider {
        public final String name;
        public int nodes;
        public long us;
        public final Map<String, Op> ops = new LinkedHashMap<String, Op>();

        Provider(String name) {
            this.name = name;
        }

        /** Ops by time spent, longest first. */
        public List<Op> byTime() {
            List<Op> l = new ArrayList<Op>(ops.values());
            Collections.sort(l, new Comparator<Op>() {
                @Override
                public int compare(Op a, Op b) {
                    return Long.compare(b.us, a.us);
                }
            });
            return l;
        }
    }

    /** Wall time of the last run, µs. */
    public long runUs;
    /** Transfers between devices in the last run. */
    public int copies;
    public final Map<String, Provider> providers = new LinkedHashMap<String, Provider>();

    private static final class Event {
        String cat, name, op, provider;
        long ts, dur;
    }

    public static OrtProfile parse(File f) throws IOException {
        Reader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
        try {
            return parse(r);
        } finally {
            r.close();
        }
    }

    public static OrtProfile parse(Reader in) throws IOException {
        MiniJson j = new MiniJson(in);
        List<Event> events = new ArrayList<Event>();
        j.beginArray();
        while (j.hasNext()) {
            Event e = new Event();
            j.beginObject();
            while (j.hasNext()) {
                String k = j.nextName();
                if ("cat".equals(k)) e.cat = j.nextString();
                else if ("name".equals(k)) e.name = j.nextString();
                else if ("ts".equals(k)) e.ts = (long) j.nextDouble();
                else if ("dur".equals(k)) e.dur = (long) j.nextDouble();
                else if ("args".equals(k) && j.peek() == MiniJson.Token.BEGIN_OBJECT) {
                    j.beginObject();
                    while (j.hasNext()) {
                        String a = j.nextName();
                        if ("op_name".equals(a) && j.peek() == MiniJson.Token.STRING) e.op = j.nextString();
                        else if ("provider".equals(a) && j.peek() == MiniJson.Token.STRING) e.provider = j.nextString();
                        else j.skipValue();
                    }
                    j.endObject();
                } else j.skipValue();
            }
            j.endObject();
            events.add(e);
        }
        j.endArray();

        OrtProfile p = new OrtProfile();
        Event run = null;
        for (Event e : events) if ("Session".equals(e.cat) && "model_run".equals(e.name)) run = e;
        long from = run != null ? run.ts : Long.MIN_VALUE, to = run != null ? run.ts + run.dur : Long.MAX_VALUE;
        p.runUs = run != null ? run.dur : 0;
        for (Event e : events) {
            if (!"Node".equals(e.cat) || e.name == null || !e.name.endsWith("_kernel_time")) continue;
            if (e.ts < from || e.ts > to) continue;
            String prov = e.provider != null ? e.provider : "?";
            Provider pr = p.providers.get(prov);
            if (pr == null) p.providers.put(prov, pr = new Provider(prov));
            String type = e.op != null ? e.op : "?";
            Op op = pr.ops.get(type);
            if (op == null) pr.ops.put(type, op = new Op(type));
            op.count++;
            op.us += e.dur;
            pr.nodes++;
            pr.us += e.dur;
            if (type.startsWith("Memcpy")) p.copies++;
        }
        if (run == null) {
            for (Provider pr : p.providers.values()) p.runUs += pr.us;
        }
        return p;
    }

    public static String providerName(String p) {
        if (CPU.equals(p)) return "процессор";
        if (p.startsWith("Xnnpack")) return "процессор (XNNPACK)";
        if (p.startsWith("WebGpu")) return "видеокарта (WebGPU)";
        if (p.startsWith("Nnapi")) return "NPU (NNAPI)";
        if (p.startsWith("QNN")) return "NPU (QNN)";
        return p.replace("ExecutionProvider", "");
    }

    /** Kernel time on the CPU (plain and XNNPACK), µs. */
    public long cpuUs() {
        long us = 0;
        for (Provider pr : providers.values()) if (onCpu(pr.name)) us += pr.us;
        return us;
    }

    static boolean onCpu(String provider) {
        return CPU.equals(provider) || provider.startsWith("Xnnpack");
    }

    /**
     * A short Russian report. Accelerators run asynchronously, so their kernel times say little; the CPU
     * times are real, and their share of the run is what the accelerator leaves on the table.
     */
    public String summary(int topOps) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Один прогон визуального энкодера: %.2f с", runUs / 1e6));
        boolean accel = false;
        for (Provider pr : providers.values()) {
            if (onCpu(pr.name)) continue;
            accel = true;
            sb.append("\n• ").append(providerName(pr.name)).append(": ").append(pr.nodes).append(" узлов");
        }
        for (Provider pr : providers.values()) {
            if (!onCpu(pr.name)) continue;
            sb.append("\n• ").append(providerName(pr.name)).append(": ").append(pr.nodes).append(" узлов, ")
                    .append(String.format(Locale.ROOT, "%.2f с", pr.us / 1e6));
            if (runUs > 0) sb.append(String.format(Locale.ROOT, " (%d%% прогона)", Math.round(100.0 * pr.us / runUs)));
            List<Op> ops = pr.byTime();
            int n = 0;
            for (Op op : ops) {
                if (n == topOps) break;
                sb.append(n == 0 ? " — " : ", ").append(op.type).append(" ×").append(op.count)
                        .append(String.format(Locale.ROOT, " %.2f с", op.us / 1e6));
                n++;
            }
            if (ops.size() > n) sb.append(", …");
        }
        if (accel) {
            sb.append("\n• пересылок между процессором и ускорителем: ").append(copies);
            if (cpuUs() == 0) sb.append("\nВесь граф на ускорителе — процессору ничего не отдано.");
        }
        return sb.toString();
    }
}
