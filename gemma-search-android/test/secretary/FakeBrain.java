import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.secretary.Brain;
import io.github.teoplaydor.semsearch.secretary.LlmError;
import io.github.teoplaydor.semsearch.secretary.Prompts;

/** The server tests' FakeLLM: prepared answers in turn (a Map, or an exception to throw), and the requests it got. */
public final class FakeBrain implements Brain {
    public final LinkedList<Object> jsonResponses = new LinkedList<Object>();
    public String textResponse = "Добрый день! Договор посмотрю до пятницы.\nИван";
    /** [smart, system, user, schema name] */
    public final List<String[]> calls = new ArrayList<String[]>();

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> json(boolean smart, boolean urgent, String system, String user, Prompts.Schema schema)
            throws LlmError {
        calls.add(new String[]{String.valueOf(smart), system, user, schema.name});
        Object r = jsonResponses.removeFirst();
        if (r instanceof LlmError) throw (LlmError) r;
        if (r instanceof RuntimeException) throw (RuntimeException) r;
        return (Map<String, Object>) r;
    }

    public synchronized String text(boolean smart, String system, String user) {
        calls.add(new String[]{String.valueOf(smart), system, user, ""});
        return textResponse;
    }
}
