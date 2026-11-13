package io.github.teoplaydor.semsearch.secretary;

import io.github.teoplaydor.semsearch.secretary.Sql.Row;

/**
 * The server version's drafts.py: reply drafts. Into a messenger only the owner's own hands send (the draft is copied
 * with a tap); a mail goes only after «Отправить» — and on the phone there is no mailbox yet, so none goes.
 */
public final class Drafts {
    private final Secretary app;

    Drafts(Secretary app) {
        this.app = app;
    }

    public void create(long mailId, String instruction, Long previousId, String only) throws LlmError {
        Row row = app.db.getMail(mailId);
        if (row == null) {
            app.send("Не нашёл это письмо.", null, only);
            return;
        }
        String previous = "";
        if (previousId != null) {
            Row old = app.db.getDraft(previousId);
            if (old != null) {
                previous = "\nПредыдущий вариант ответа:\n<<<\n" + old.text("text") + "\n>>>\n";
                app.db.setDraftStatus(previousId, "replaced");
            }
        }
        String ins = instruction == null || instruction.isEmpty() ? "нет" : instruction;
        String system, user;
        if (Views.isChat(row)) {
            system = Prompts.fill(Prompts.CHAT_DRAFT_SYSTEM, "about_me", app.cfg.aboutMe);
            user = Prompts.fill(Prompts.CHAT_DRAFT_USER, "app", row.text("account"), "chat", row.text("from_name"), "body",
                    row.text("body"), "previous", previous, "instruction", ins);
        } else {
            system = Prompts.fill(Prompts.DRAFT_SYSTEM, "about_me", app.cfg.aboutMe, "my_name",
                    app.cfg.myName == null || app.cfg.myName.isEmpty() ? "(без подписи)" : app.cfg.myName);
            user = Prompts.fill(Prompts.DRAFT_USER, "from_name", row.text("from_name"), "from_addr", row.text("from_addr"),
                    "subject", row.text("subject"), "body", row.text("body"), "previous", previous, "instruction", ins);
        }
        String text = app.llm.text(true, system, user);
        long draftId = app.db.addDraft(mailId, text);
        app.send(Views.draftView(row, text), Views.draftKb(draftId, !Views.isChat(row)), only);
    }

    public String send(long draftId) {
        Row draft = app.db.getDraft(draftId);
        if (draft == null || !"draft".equals(draft.text("status"))) return "Этот черновик уже отправлен или отменён.";
        Row row = app.db.getMail(draft.lng("mail_id"));
        if (row != null && Views.isChat(row)) return "Это ответ для мессенджера — скопируй его и отправь сам.";
        return "Не знаю, через какой ящик отправить ответ.";
    }
}
