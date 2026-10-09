import java.util.Arrays;

import io.github.teoplaydor.semsearch.core.NoteText;

/**
 * A note's text: list items with boxes (its own «☐/☑» and pasted «- [ ]»), ticked and unticked, a line made an item
 * and back; what it says for its meaning without the boxes (ticking one off changes nothing there); its title, the
 * boxes ticked; the words a query has found as they are (a code, the start of a word).
 * usage: NoteTextTest
 */
public class NoteTextTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    public static void main(String[] args) {
        String note = "Купить\n☐ молоко\n☑ хлеб\n- [ ] сыр\n- [x] кофе\n\nпотом позвонить";
        check(NoteText.box("☐ молоко") == 1 && NoteText.box("☑ хлеб") == 2 && NoteText.box("- [ ] сыр") == 1
                && NoteText.box("  [x] кофе") == 2 && NoteText.box("Купить") == 0, "boxes: its own and pasted ones, to do and done");
        check(NoteText.rest("☐ молоко").equals("молоко") && NoteText.rest("- [x] кофе").equals("кофе")
                && NoteText.rest("просто текст").equals("просто текст"), "a line without its box");
        String ticked = NoteText.toggle(note, 1);
        check(NoteText.lines(ticked)[1].equals("☑ молоко"), "ticked: " + NoteText.lines(ticked)[1]);
        check(NoteText.toggle(ticked, 1).equals(note), "and unticked: the note as it was");
        check(NoteText.lines(NoteText.toggle(note, 3))[3].equals("☑ сыр"), "a pasted box ticked becomes the note's own");
        check(NoteText.toggle(note, 0).equals(note), "a line with no box: nothing to tick");
        String listed = NoteText.toggleList(note, 6);
        check(NoteText.lines(listed)[6].equals("☐ потом позвонить") && NoteText.toggleList(listed, 6).equals(note),
                "a line made an item, and back");
        check(NoteText.plain(note).equals("Купить\nмолоко\nхлеб\nсыр\nкофе\nпотом позвонить")
                && NoteText.plain(ticked).equals(NoteText.plain(note)), "its meaning: no boxes, no blank lines — the same ticked or not");
        check(Arrays.equals(NoteText.progress(note), new int[]{2, 4}), "two of four ticked");
        check(NoteText.title("\n☐ молоко и хлеб на завтрак", 12).equals("молоко и хл…") && NoteText.title("", 10).isEmpty(),
                "the title: the first line with something, short");
        check(NoteText.lineAt("a\nbc\nd", 3) == 1 && NoteText.lineStart("a\nbc\nd", 2) == 5, "lines by place and back");
        String code = "Код домофона у Саши: 47К1290";
        check(NoteText.hasWords(code, "47к1290") && NoteText.hasWords(code, "домофон") && NoteText.hasWords(code, "КОД саши"),
                "words as they are: a code, the start of a word, case aside");
        check(!NoteText.hasWords(code, "пароль") && !NoteText.hasWords(code, "к") && NoteText.hasWords("Ёлка", "елка"),
                "not a word it lacks, not a single letter; «ё» as «е»");
        System.out.println(bad == 0 ? "NOTE TEXT OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
