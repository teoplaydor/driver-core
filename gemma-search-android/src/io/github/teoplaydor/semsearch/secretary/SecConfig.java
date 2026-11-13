package io.github.teoplaydor.semsearch.secretary;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The settings the server took from config.toml — the ones that matter on the phone, with its defaults. */
public final class SecConfig {
    public ZoneId tz = ZoneId.systemDefault();
    /** Who the owner is and what matters to them — the model weighs everything by it. */
    public String aboutMe = "";
    public String myName = "";
    public List<LocalTime> digestTimes = new ArrayList<LocalTime>(Arrays.asList(LocalTime.of(8, 30), LocalTime.of(19, 0)));
    /** Weekly spending report: day of week (1 = Monday … 7 = Sunday) and time; null — none. */
    public Integer weeklyDay = 7;
    public LocalTime weeklyAt = LocalTime.of(20, 0);
    public List<Integer> remindBefore = new ArrayList<Integer>(Arrays.asList(60, 10));
    public boolean skipEmptyDigest = true;
    public double largeExpense = 10000;
    /** Apps (names or packages) whose notifications are not needed, and more banks. */
    public List<String> ignoreApps = new ArrayList<String>(), bankApps = new ArrayList<String>();
    /** A chat is read when it has been quiet this long; but no later than max wait. */
    public int quietSeconds = 90, maxWaitSeconds = 600;
    public List<String> vip = new ArrayList<String>(), mute = new ArrayList<String>();
}
