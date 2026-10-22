package com.agenticengineering.urlshortener.orchestration.reliability;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * MTTR per the constitution (Evidence and Integrity Constraints): total recovery duration across recovered failure
 * events divided by their number. Unrecovered failures are counted separately and never enter the denominator;
 * events excluded with a recorded reason are listed, not counted. Governance wait inside an episode is excluded from
 * the primary figure and reported separately (human decision 2026-10-19).
 */
public final class MttrCalculator {

    private MttrCalculator() {
    }

    public static MttrReport compute(List<FailureEvent> events) {
        int recovered = 0;
        int unrecovered = 0;
        Duration primary = Duration.ZERO;
        Duration withWait = Duration.ZERO;
        Duration wait = Duration.ZERO;
        List<String> excluded = new ArrayList<>();
        Map<String, Integer> byMechanism = new TreeMap<>();
        for (FailureEvent event : events) {
            if (event.mttrExcluded()) {
                excluded.add("failure " + event.id() + ": " + event.mttrExclusionReason());
                continue;
            }
            Optional<Duration> episode = event.recoveryDuration();
            if (episode.isEmpty()) {
                unrecovered++;
                continue;
            }
            if (event.governanceWait().compareTo(episode.get()) > 0) {
                throw new IllegalStateException("failure " + event.id() + ": governance wait " + event.governanceWait()
                        + " exceeds its recovery episode " + episode.get());
            }
            recovered++;
            withWait = withWait.plus(episode.get());
            wait = wait.plus(event.governanceWait());
            primary = primary.plus(episode.get().minus(event.governanceWait()));
            if (event.mechanism() != null) {
                byMechanism.merge(event.mechanism().code(), 1, Integer::sum);
            }
        }
        Optional<Duration> mttr = recovered == 0 ? Optional.empty() : Optional.of(primary.dividedBy(recovered));
        Optional<Duration> secondary = recovered == 0 ? Optional.empty() : Optional.of(withWait.dividedBy(recovered));
        return new MttrReport(recovered, primary, mttr, wait, secondary, unrecovered, List.copyOf(excluded), Map.copyOf(byMechanism));
    }
}
