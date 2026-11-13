package io.github.teoplaydor.semsearch.secretary;

import java.util.Map;

/**
 * The secretary's model, as the server's llm.py offers it: an answer as JSON of a schema, or as text. «smart» — the
 * server's smart_model (talking with the owner, drafts), otherwise fast_model (sorting); on the phone both are the one
 * local model. urgent — the owner is waiting right now (the screen filter in «Тишина»): it goes before the rest.
 */
public interface Brain {
    Map<String, Object> json(boolean smart, boolean urgent, String system, String user, Prompts.Schema schema) throws LlmError;

    String text(boolean smart, String system, String user) throws LlmError;
}
