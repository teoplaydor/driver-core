import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.QnnLog;

/**
 * Reading why QNN could not compile the graph for the NPU: the NPU process reads its own log (logcat "-v tag"
 * lines in ONNX Runtime's Android format) and finds how much QNN took, what it refused and why, the error code,
 * and QNN's errors — those while compiling apart from those while it checked single nodes (seen on the phone:
 * a ReduceMax on integers it refuses, which stays on the CPU anyway). The node a compile error names is found in
 * the graph (every node carries a unique token), with the same node in every layer. The bisection finds the
 * node that breaks a compilation; the tiny graph that tells whether the NPU compiles anything runs.
 * usage: QnnLogTest <graph rewritten for QNN (two encoder layers)>
 */
public class QnnLogTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static final String ORT = "[V:onnxruntime:, qnn_backend_manager.cc:466 QnnLogging] ";

    public static void main(String[] args) throws Exception {
        File graph = new File(args[0]);
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(graph, new HashMap<String, Long>());
        String softmax = null;
        for (OnnxPatcher.Node n : nodes) if (n.opType.equals("Softmax") && softmax == null) softmax = n.name;
        java.util.Set<String> names = new java.util.HashSet<String>();
        boolean tokens = true;
        for (OnnxPatcher.Node n : nodes) tokens &= n.name.matches(".*_N\\d+N") && names.add(n.name.replaceAll(".*(_N\\d+N)$", "$1"));
        check(tokens, "every node name carries a unique token (" + nodes.size() + " nodes), e.g. " + softmax);
        List<String> log = Arrays.asList(
                "I/onnxruntime: [I:onnxruntime:, inference_session.cc:1690 TransformGraph] LayeringIndex created. Matched 1 out of 1 rules to available Execution Providers.",
                "I/onnxruntime: [I:onnxruntime:, qnn_execution_provider.cc:807 GetSupportedNodes] Validation FAILED for 1 nodes in NodeUnit (Gelu) :",
                "I/onnxruntime: \tOperator type: Gelu Node name: /encoder/layers.0/act Node index: 12",
                "I/onnxruntime: \tREASON : QNN EP: Gelu with approximate=tanh is not supported",
                "I/onnxruntime: ",
                "I/onnxruntime: [I:onnxruntime:, qnn_execution_provider.cc:807 GetSupportedNodes] Validation FAILED for 1 nodes in NodeUnit (Gelu) :",
                "I/onnxruntime: \tOperator type: Gelu Node name: /encoder/layers.1/act Node index: 31",
                "I/onnxruntime: \tREASON : QNN EP: Gelu with approximate=tanh is not supported",
                "I/onnxruntime: [I:onnxruntime:, qnn_execution_provider.cc:807 GetSupportedNodes] Validation PASSED for 1 nodes in NodeUnit (MatMul) :",
                "I/onnxruntime: \tOperator type: MatMul Node name: /encoder/layers.0/q_proj Node index: 3",
                "V/onnxruntime: " + ORT + "QnnDsp <E> Unsupported input/output datatypes requested for the HTP Op 'ReduceMax' in the node 'node_max_1_N7N__0_5'.",
                "I/onnxruntime: [I:onnxruntime:, qnn_execution_provider.cc:1152 GetCapability] Number of partitions supported by QNN EP: 3, number of nodes in the graph: 60, number of nodes supported by QNN: 58",
                "V/onnxruntime: " + ORT + "QnnDsp <I> Graph prepare started",
                "V/onnxruntime: " + ORT + "QnnDsp <E> graph_prepare.cc:217::ERROR:could not create op: q::Softmax",
                "V/onnxruntime( 4321): " + ORT + "QnnDsp <E> \"" + softmax + "_reshape\" generated: could not create op",
                "V/onnxruntime: " + ORT + "QnnDsp <E> Failed to finalize graph (id: 1) with err 1002",
                "V/onnxruntime: " + ORT + "QnnDsp <E> Failed to finalize graph (id: 1) with err 1002",
                "V/onnxruntime: " + ORT + "QnnDsp <W> Spill-fill buffer is large",
                "E/onnxruntime: [E:onnxruntime:, qnn_model.cc:387 FinalizeGraphs] Failed to finalize QNN graph. Error code: 1002",
                "E/adsprpc: remote_handle64_open: dynamic loading failed for libQnnHtpV81Skel.so",
                "E/io.github.teoplaydor.semsearch:npu: log_config.c:687:Error : Unable to add watcher for folder /vendor/lib/rfsa/adsp",
                "W/System  : A resource failed to call close.");
        QnnLog q = QnnLog.parse(log);
        check(q.partitions == 3 && q.nodes == 60 && q.supported == 58, "partitions " + q.partitions + ", nodes " + q.supported + "/" + q.nodes);
        check("1002".equals(q.finalizeCode), "finalize error code " + q.finalizeCode);
        check(q.layeringMatched == 1, "node assignment applied: " + q.layeringMatched);
        check(q.refused.size() == 1 && q.refused.get("Gelu").size() == 2 && q.reasons.get("Gelu").contains("approximate=tanh"),
                "refused " + q.refused + " " + q.reasons);
        check(q.errors.size() == 3 && q.warnings.size() == 1, "QNN errors while compiling " + q.errors.size() + " (info lines and repeats dropped), warnings " + q.warnings.size());
        check(q.checkErrors.size() == 1 && q.checkErrors.get(0).contains("ReduceMax"), "while checking nodes, kept apart: " + q.checkErrors);
        check(q.system.size() == 1 && q.system.get(0).startsWith("adsprpc: "), "other errors of the process: " + q.system);
        String summary = q.summary(4);
        System.out.println(summary);
        check(summary.startsWith("QNN взял 58 из 60 узлов, частей 3; не взял: Gelu ×2 (QNN EP: Gelu with approximate=tanh")
                && summary.contains("код ошибки сборки 1002") && summary.contains("\n  QNN при сборке: QnnDsp <E> \"" + softmax)
                && summary.contains("при проверке узлов QNN сообщил 1 раз") && summary.contains("назначение на процессор применено")
                && !summary.contains("watcher"), "summary for the report");

        List<OnnxPatcher.Node> failing = q.failingNodes(nodes);
        check(failing.size() == 1 && failing.get(0).name.equals(softmax), "QNN's error points at " + (failing.isEmpty() ? "nothing" : failing.get(0).name));
        List<String> every = QnnLog.inEveryLayer(nodes, failing.get(0));
        check(every.size() == 2 && every.contains(softmax), "the same node in every layer: " + every);
        check(QnnLog.parse(Arrays.asList("V/onnxruntime: " + ORT + "QnnDsp <E> no names here")).failingNodes(nodes).isEmpty(),
                "no node named: nothing to move");

        // bisection: the node whose addition breaks the compilation, in about log2(n) compilations
        boolean found = true;
        for (int n : new int[]{1, 2, 7, 1526}) {
            for (final int c : new int[]{0, n / 3, n - 1}) {
                final int[] calls = {0};
                int got = QnnLog.firstBreaking(n, new QnnLog.Probe() {
                    @Override
                    public boolean compiles(int k) {
                        calls[0]++;
                        return k <= c;
                    }
                });
                found &= got == c && calls[0] <= 32 - Integer.numberOfLeadingZeros(n) + 1;
                if (got != c) System.out.println("  n=" + n + " culprit " + c + " found " + got);
            }
        }
        check(found, "bisection finds the breaking node (1526 nodes: ≤ 12 compilations)");

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        // the tiny graph: valid, and softmax rows sum to one
        File canary = new File(graph.getParentFile(), "canary.onnx");
        java.nio.file.Files.write(canary.toPath(), OnnxPatcher.canaryModel());
        OrtSession cs = env.createSession(canary.getPath(), new OrtSession.SessionOptions());
        float[] cx = new float[64 * 64];
        for (int i = 0; i < cx.length; i++) cx[i] = (float) Math.cos(i * 0.11);
        OnnxTensor ct = OnnxTensor.createTensor(env, FloatBuffer.wrap(cx), new long[]{1, 64, 64});
        OrtSession.Result cr = cs.run(java.util.Collections.singletonMap("x", ct));
        float[] cy = ((OnnxTensor) cr.get(0)).getFloatBuffer().array();
        float row = 0;
        for (int i = 0; i < 64; i++) row += cy[i];
        check(cy.length == 64 * 64 && Math.abs(row - 1) < 1e-4, "tiny graph runs: " + cy.length + " values, first row sums to " + row);
        cr.close();
        ct.close();
        cs.close();

        // QNN's own profile of a run (profiling_level=detailed, the CSV ONNX Runtime 1.29 writes): ops by type,
        // slowest nodes, overall timings — per run (two runs here)
        String matmul = null;
        for (OnnxPatcher.Node n : nodes) if (n.opType.equals("MatMul") && matmul == null) matmul = n.name;
        List<String> csv = new java.util.ArrayList<String>();
        csv.add("Msg Timestamp,Message,Time,Unit of Measurement,Timing Source,Event Level,Event Identifier");
        for (int run = 0; run < 2; run++) {
            csv.add("UNKNOWN,EXECUTE,3100,US,BACKEND,ROOT,NULL");
            csv.add("UNKNOWN,BACKEND,2800,US,BACKEND,SUB-EVENT,Accelerator (execute) time");
            csv.add("UNKNOWN,NODE,6000,CYCLES,BACKEND,SUB-EVENT," + matmul);
            csv.add("UNKNOWN,NODE,3000,CYCLES,BACKEND,SUB-EVENT," + softmax + "_reshape");
            csv.add("UNKNOWN,NODE,1000,CYCLES,BACKEND,SUB-EVENT,input_0_cast");
        }
        String prof = QnnLog.profile(csv, nodes, 2);
        System.out.println("  " + prof.replace("\n", "\n  "));
        check(prof.contains("EXECUTE 3100 us; Accelerator (execute) time 2800 us") && prof.contains("по операциям (3 операций QNN, всего 10000 cycles): MatMul 60%, Softmax 30%, ? 10%")
                        && prof.contains("дольше всего: ") && prof.contains("(MatMul) 60%") && prof.contains("\nбез узла графа (?): input_0_cast 10.0%"),
                "QNN's profile: ops by type (named after the graph's nodes), slowest nodes, per run");
        check(QnnLog.profile(Arrays.asList("Msg Timestamp,Message,Time,Unit of Measurement,Timing Source,Event Level,Event Identifier"),
                nodes, 1).equals("QNN не дал профиля по операциям"), "an empty profile says so");
        // the DSP loader tries several folders before it finds its library: those lines are not errors to report
        QnnLog noise = QnnLog.parse(Arrays.asList(
                "E/io.github.teoplaydor.semsearch: npu: vendor/qcom/proprietary/adsprpc/src/fastrpc_apps_user.c:6192: Error 0xd: open_shell failed for domain 3 search paths used are /usr/lib/dsp/ (errno Permission denied)",
                "E/io.github.teoplaydor.semsearch: npu: vendor/qcom/proprietary/adsprpc/src/apps_std_imp.c:377: Error 0x2: apps_std_fopen_fd failed for /data/user/0/x/files/qnn/lib/cdsp/./libQnnHtpV81Skel.so (No such file or directory)",
                "E/io.github.teoplaydor.semsearch: npu: vendor/qcom/proprietary/adsprpc/src/log_config.c:223:Enabled adspmsgd with mask 8",
                "E/adsprpc: remote_handle_open failed: 0x80000406"));
        check(noise.system.size() == 1 && noise.system.get(0).contains("remote_handle_open"), "the DSP loader's search is not reported: " + noise.system);
        if (bad > 0) {
            System.out.println(bad + " FAILED");
            System.exit(1);
        }
        System.out.println("QNN log: all ok");
    }
}
