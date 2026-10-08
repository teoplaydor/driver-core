import java.io.File;

import io.github.teoplaydor.semsearch.core.OnnxPatcher;

/** Rewrites a graph for QNN (OnnxPatcher.forQnn) and prints what it changed. usage: ForQnn <in.onnx> <out.onnx> */
public class ForQnn {
    public static void main(String[] args) throws Exception {
        int[] n = OnnxPatcher.forQnn(new File(args[0]), new File(args[1]));
        System.out.println("rewritten: attention " + n[0] + ", RMS norm " + n[1] + ", constants brought into fp16 " + n[2] + ", gathers as slices " + n[3]);
    }
}
