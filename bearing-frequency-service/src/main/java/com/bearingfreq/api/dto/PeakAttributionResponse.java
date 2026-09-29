package com.bearingfreq.api.dto;

import com.bearingfreq.attribution.Assignment;
import com.bearingfreq.attribution.AttributionResult;
import com.bearingfreq.attribution.MeasuredPeak;
import com.bearingfreq.attribution.MatchingResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 峰值归属 + 打滑估计响应。
 *
 * <p>结果分三块：{@code assignments}（已配上的峰，按族/阶次规范序）、
 * {@code peaks}（<b>与提交顺序逐根对齐</b>的归属视图，未归属者 family/order 为 null）、
 * {@code unmatchedPeaks}（未归属峰视图）。同一请求无论峰列表是否打乱，
 * 上述三块逐字段相同。
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record PeakAttributionResponse(
        String bearingName,
        double rotationFrequencyHz,
        int maxOrder,
        double relativeTolerance,
        double frequencyResolutionHz,
        double slipMin,
        double slipMax,
        Double lockedSlip,
        double slipCoefficient,
        int iterations,
        boolean converged,
        String terminationReason,
        String terminationMessage,
        double initialSlipCoefficient,
        int matchedPeakCount,
        int unmatchedPeakCount,
        double totalSquaredRelativeDeviation,
        double totalAbsDeviationHz,
        List<AssignmentView> assignments,
        List<PeakAttributionView> peaks,
        List<PeakView> unmatchedPeaks) {

    /** 一条已配上的归属：峰、族、阶次、修正目标与带符号偏差。 */
    public record AssignmentView(
            int submittedIndex,
            double peakFrequencyHz,
            double peakAmplitude,
            String family,
            int order,
            double theoreticalTargetHz,
            double adjustedTargetHz,
            double deviationHz,
            double relativeDeviation) {

        static AssignmentView of(Assignment a) {
            return new AssignmentView(
                    a.peak().index(),
                    a.peak().frequencyHz(),
                    a.peak().amplitude(),
                    a.target().family().id(),
                    a.target().order(),
                    a.target().theoreticalHz(),
                    a.adjustedTargetHz(),
                    a.deviationHz(),
                    a.relativeDeviation());
        }
    }

    /** 与提交顺序对齐的单根峰视图（未归属时 family/order 等为 null）。 */
    public record PeakAttributionView(
            int submittedIndex,
            double frequencyHz,
            double amplitude,
            boolean assigned,
            String family,
            Integer order,
            Double adjustedTargetHz,
            Double deviationHz,
            Double relativeDeviation) {
    }

    /** 未归属峰视图。 */
    public record PeakView(
            int submittedIndex,
            double frequencyHz,
            double amplitude) {

        static PeakView of(MeasuredPeak peak) {
            return new PeakView(peak.index(), peak.frequencyHz(), peak.amplitude());
        }
    }

    public static PeakAttributionResponse of(AttributionResult result) {
        MatchingResult matching = result.matching();
        var params = result.parameters();

        List<AssignmentView> assignmentViews = new ArrayList<>(matching.assignments().size());
        Map<Integer, Assignment> bySubmittedPeakIndex = new HashMap<>();
        for (Assignment a : matching.assignments()) {
            assignmentViews.add(AssignmentView.of(a));
            bySubmittedPeakIndex.put(a.peak().index(), a);
        }

        List<MeasuredPeak> submittedOrder = new ArrayList<>(params.peaks());
        submittedOrder.sort(java.util.Comparator.comparingInt(MeasuredPeak::index));

        List<PeakAttributionView> peakViews = new ArrayList<>(submittedOrder.size());
        for (MeasuredPeak peak : submittedOrder) {
            Assignment a = bySubmittedPeakIndex.get(peak.index());
            if (a != null) {
                peakViews.add(new PeakAttributionView(
                        peak.index(), peak.frequencyHz(), peak.amplitude(), true,
                        a.target().family().id(), a.target().order(),
                        a.adjustedTargetHz(), a.deviationHz(), a.relativeDeviation()));
            } else {
                peakViews.add(new PeakAttributionView(
                        peak.index(), peak.frequencyHz(), peak.amplitude(), false,
                        null, null, null, null, null));
            }
        }

        // 未归属峰按规范序（频率、幅值、原始下标）输出，与引擎内部排序口径一致
        List<MeasuredPeak> canonicalOrder = result.canonicalPeaks();
        List<PeakView> unmatchedViews = matching.unmatchedPeakIndices().stream()
                .map(canonicalIndex -> PeakView.of(canonicalOrder.get(canonicalIndex)))
                .toList();

        return new PeakAttributionResponse(
                params.bearingName(),
                params.rotationFrequencyHz(),
                params.maxOrder(),
                params.relativeTolerance(),
                params.frequencyResolutionHz(),
                params.slipMin(),
                params.slipMax(),
                params.lockedSlip(),
                result.slip(),
                result.iterations(),
                result.converged(),
                result.terminationReason(),
                result.terminationMessage(),
                result.initialSlip(),
                matching.matchCount(),
                params.peaks().size() - matching.matchCount(),
                matching.totalSquaredRelativeDeviation(),
                matching.totalAbsDeviationHz(),
                List.copyOf(assignmentViews),
                List.copyOf(peakViews),
                List.copyOf(unmatchedViews));
    }
}
