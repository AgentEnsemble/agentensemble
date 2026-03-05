package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MapReduceBinPacker}.
 *
 * <p>Tests verify the first-fit-decreasing (FFD) bin-packing algorithm against a variety
 * of input distributions: standard varied sizes, all-equal, single oversized item,
 * empty input, and ceil(N/capacity) correctness.
 */
class MapReduceBinPackerTest {

    // ========================
    // Edge cases
    // ========================

    @Test
    void pack_emptyInputs_returnsEmptyBins() {
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(), 100);

        assertThat(bins).isEmpty();
    }

    @Test
    void pack_singleItemFitsInBudget_producesOneBin() {
        TaskOutput output = stubOutput("A", 50);
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(output), 100);

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0)).containsExactly(output);
    }

    @Test
    void pack_singleItemExceedsBudget_producesOneBinAnyway() {
        // An output that alone exceeds the budget gets its own single-item bin
        TaskOutput output = stubOutput("A", 200);
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(output), 100);

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0)).containsExactly(output);
    }

    // ========================
    // All items equal size
    // ========================

    @Test
    void pack_allEqual_fitsExactly_producesOneFullBin() {
        // 2 items of 50 each, budget 100 -> fits in 1 bin
        List<TaskOutput> outputs = List.of(stubOutput("A", 50), stubOutput("B", 50));
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(outputs, 100);

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0)).hasSize(2);
    }

    @Test
    void pack_allEqual_twoPerBin_exactDivision() {
        // 4 items of 50 each, budget 100 -> 2 bins of 2
        List<TaskOutput> outputs =
                List.of(stubOutput("A", 50), stubOutput("B", 50), stubOutput("C", 50), stubOutput("D", 50));
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(outputs, 100);

        assertThat(bins).hasSize(2);
        assertThat(bins.get(0)).hasSize(2);
        assertThat(bins.get(1)).hasSize(2);
    }

    @Test
    void pack_allEqual_ceilNdivCapacity_nonDivisible() {
        // 5 items of 40 each, budget 100: capacity = 2 per bin, ceil(5/2) = 3 bins
        List<TaskOutput> outputs = List.of(
                stubOutput("A", 40),
                stubOutput("B", 40),
                stubOutput("C", 40),
                stubOutput("D", 40),
                stubOutput("E", 40));
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(outputs, 100);

        assertThat(bins).hasSize(3);
        // First 2 bins have 2 items each, last bin has 1 item
        int total = bins.stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(5);
    }

    // ========================
    // Varied sizes
    // ========================

    @Test
    void pack_variedSizes_outputsSortedDescending_ffdPlacement() {
        // Items: 80, 70, 50, 30, budget=100
        // After sort desc: [80, 70, 50, 30]
        // Bin 0: 80 -> try 70 (80+70=150 > 100, no) -> try 50 (80+50=130 > 100, no)
        //          -> try 30 (80+30=110 > 100, no) -- Actually 80+30=110, so no.
        // Hmm, budget=100 with 80: bin0=[80], try 70: 80+70>100 skip, try 50: 80+50>100 skip,
        //   try 30: 80+30>100 skip -- so bin0=[80] only.
        // Wait, FFD: sorted descending [80, 70, 50, 30]
        // 80: bin0=[80]
        // 70: bin0 has 20 remaining, 70>20 -> new bin1=[70]
        // 50: bin0 has 20, 50>20 -> bin1 has 30, 50>30 -> new bin2=[50]
        // 30: bin0 has 20, 30>20 -> bin1 has 30, 30<=30 -> bin1=[70,30]
        // Result: bin0=[80], bin1=[70,30], bin2=[50] => 3 bins
        TaskOutput o80 = stubOutput("A", 80);
        TaskOutput o70 = stubOutput("B", 70);
        TaskOutput o50 = stubOutput("C", 50);
        TaskOutput o30 = stubOutput("D", 30);

        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(o80, o70, o50, o30), 100);

        assertThat(bins).hasSize(3);
        int total = bins.stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(4);
    }

    @Test
    void pack_twoItemsFitTogether_producedOneBin() {
        // 60 + 40 = 100 fits exactly in budget 100
        TaskOutput o60 = stubOutput("A", 60);
        TaskOutput o40 = stubOutput("B", 40);

        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(o60, o40), 100);

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0)).hasSize(2);
    }

    @Test
    void pack_multipleOversizedItems_eachInOwnBin() {
        // All items exceed budget individually
        List<TaskOutput> outputs = List.of(stubOutput("A", 200), stubOutput("B", 150), stubOutput("C", 300));
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(outputs, 100);

        assertThat(bins).hasSize(3);
        bins.forEach(bin -> assertThat(bin).hasSize(1));
    }

    @Test
    void pack_nineItems_k3Budget_producesThreeBins() {
        // 9 items of size 30 each, budget 100 (fits 3 per bin), expect 3 bins of 3
        List<TaskOutput> outputs = List.of(
                stubOutput("A", 30),
                stubOutput("B", 30),
                stubOutput("C", 30),
                stubOutput("D", 30),
                stubOutput("E", 30),
                stubOutput("F", 30),
                stubOutput("G", 30),
                stubOutput("H", 30),
                stubOutput("I", 30));
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(outputs, 100);

        // 9 items at 30 tokens each, budget 100: 3 items fit per bin (90 <= 100), so 3 bins
        assertThat(bins).hasSize(3);
        bins.forEach(bin -> assertThat(bin).hasSize(3));
    }

    @Test
    void pack_allOutputsPreserved_noOutputsLost() {
        // Verify every input appears in exactly one bin
        List<TaskOutput> outputs = List.of(
                stubOutput("A", 10),
                stubOutput("B", 25),
                stubOutput("C", 50),
                stubOutput("D", 75),
                stubOutput("E", 100),
                stubOutput("F", 110));
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(outputs, 100);

        int total = bins.stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(outputs.size());
    }

    // ========================
    // Budget boundary conditions
    // ========================

    @Test
    void pack_itemExactlyEqualsBudget_fitsAlone() {
        TaskOutput output = stubOutput("A", 100);
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(output), 100);

        assertThat(bins).hasSize(1);
        assertThat(bins.get(0)).containsExactly(output);
    }

    @Test
    void pack_twoItemsExactlyFillBudget_secondInNewBin() {
        // 100 + 100 > 100, so each gets its own bin
        TaskOutput a = stubOutput("A", 100);
        TaskOutput b = stubOutput("B", 100);
        List<List<TaskOutput>> bins = MapReduceBinPacker.pack(List.of(a, b), 100);

        assertThat(bins).hasSize(2);
    }

    // ========================
    // Helpers
    // ========================

    /** Builds a TaskOutput with a known provider output token count. */
    private static TaskOutput stubOutput(String role, long outputTokens) {
        TaskMetrics metrics = TaskMetrics.builder()
                .outputTokens(outputTokens)
                .inputTokens(0L)
                .totalTokens(outputTokens)
                .build();
        return TaskOutput.builder()
                .raw("output-" + role)
                .taskDescription("task " + role)
                .agentRole(role)
                .completedAt(Instant.now())
                .duration(Duration.ZERO)
                .metrics(metrics)
                .build();
    }
}
