import io.github.teoplaydor.semsearch.core.StageProgress;

/**
 * Progress of the accelerator check by stages: weighted by expected time, a running stage by parts and time
 * (never at its end before it ends), skipped stages count as done, the percentage never goes back.
 */
public class StageProgressTest {
    static int bad;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) bad++;
    }

    public static void main(String[] args) {
        StageProgress p = new StageProgress("Подбор ускорения", 0);
        StageProgress.Stage cpu = p.add("Процессор", 10000), npu = p.add("NPU Snapdragon", 80000), threads = p.add("Потоки", 10000);
        check(p.percent(0) == 0 && p.line(0).equals("0%"), "nothing started: 0%");
        p.start(cpu, 0);
        check(p.percent(5000) == 4 && p.line(5000).equals("4% · этап 1 из 3: Процессор"), "half of a stage's expected time: 45% of it, 4% of all: " + p.line(5000));
        check(p.stageFraction(cpu, 60000) < 0.99 && p.stageFraction(cpu, 60000) > 0.9, "far beyond its time a stage stays below 99%: " + p.stageFraction(cpu, 60000));
        p.done(cpu, "1.37 с", 9000);
        p.start(npu, 9000);
        p.detail(npu, "QNN компилирует граф");
        check(p.line(9000).equals("10% · этап 2 из 3: NPU Snapdragon — QNN компилирует граф"), "done stage counts whole: " + p.line(9000));
        // the NPU stage turns out longer than thought: the percentage holds instead of going back
        int before = p.percent(49000);
        p.expect(npu, 400000);
        check(p.percent(49000) == before, "a longer expectation does not take the percentage back: " + before + " → " + p.percent(49000));
        p.failed(npu, "не работает", 100000);
        p.start(threads, 100000);
        p.parts(threads, 3);
        p.part(threads, 2, "потоков 8", 100000);
        check(Math.abs(p.stageFraction(threads, 100000) - 2 / 3.0) < 1e-9, "parts: 2 of 3 done → 67% of the stage");
        check(p.position()[0] == 3 && p.position()[1] == 3, "position: stage 3 of 3");
        p.finish(120000);
        check(p.percent(120000) == 100 && p.line(120000).equals("100% · готово за 2:00") && p.stages().get(2).state == StageProgress.SKIPPED,
                "finished: 100%, the running stage left as skipped: " + p.line(120000));
        StageProgress q = new StageProgress("x", 0);
        StageProgress.Stage a = q.add("a", 1000), b = q.add("b", 1000);
        q.skip(a, "нет QNN");
        check(q.percent(0) == 50 && q.position()[0] == 1, "a skipped stage counts as done: " + q.percent(0) + "%");
        q.start(b, 0);
        check(q.percent(10000000) == 99, "never 100% before the end");
        check(StageProgress.clock(3725000).equals("1:02:05") && StageProgress.clock(95000).equals("1:35"), "clock");
        if (bad > 0) {
            System.out.println(bad + " FAILED");
            System.exit(1);
        }
        System.out.println("stage progress: all ok");
    }
}
