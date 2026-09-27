package com.openai.pulsecalm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public class PpgAnalyzer {
    public static class Result {
        public final int bpm;
        public final double rmssd;
        public final int stress;
        public final boolean reliable;
        Result(int bpm, double rmssd, int stress, boolean reliable) {
            this.bpm=bpm; this.rmssd=rmssd; this.stress=stress; this.reliable=reliable;
        }
    }

    private static class Sample { long t; double v; Sample(long t,double v){this.t=t;this.v=v;} }
    private final Deque<Sample> window = new ArrayDeque<>();
    private final List<Long> peaks = new ArrayList<>();
    private double baseline = Double.NaN;
    private double prev2 = 0, prev1 = 0;
    private long prev1t = 0;
    private long lastPeak = 0;

    public synchronized void reset() {
        window.clear(); peaks.clear(); baseline=Double.NaN; prev2=prev1=0; prev1t=lastPeak=0;
    }

    public synchronized Result add(long timeMs, double signal) {
        if (Double.isNaN(baseline)) baseline = signal;
        baseline = baseline*0.965 + signal*0.035;
        double filtered = signal - baseline;
        window.addLast(new Sample(timeMs, filtered));
        while (!window.isEmpty() && timeMs - window.peekFirst().t > 8000) window.removeFirst();

        double std = stdDev(window);
        double threshold = Math.max(0.8, std * 0.45);
        if (prev1 > prev2 && prev1 >= filtered && prev1 > threshold && prev1t - lastPeak > 380) {
            if (lastPeak == 0 || prev1t - lastPeak < 1500) {
                peaks.add(prev1t);
                lastPeak = prev1t;
            } else {
                peaks.clear();
                peaks.add(prev1t);
                lastPeak = prev1t;
            }
        }
        prev2 = prev1;
        prev1 = filtered;
        prev1t = timeMs;

        while (peaks.size() > 24) peaks.remove(0);
        List<Double> intervals = validIntervals(peaks);
        int bpm = 0;
        double rmssd = 0;
        boolean reliable = intervals.size() >= 5;
        if (intervals.size() >= 2) {
            List<Double> sorted = new ArrayList<>(intervals);
            Collections.sort(sorted);
            double median = sorted.get(sorted.size()/2);
            bpm = (int)Math.round(60000.0 / median);
            rmssd = rmssd(intervals);
        }
        int stress = computeStress(bpm, rmssd, reliable);
        return new Result(bpm, rmssd, stress, reliable);
    }

    private static List<Double> validIntervals(List<Long> p) {
        List<Double> out = new ArrayList<>();
        for (int i=1;i<p.size();i++) {
            double d = p.get(i)-p.get(i-1);
            if (d >= 330 && d <= 1500) out.add(d);
        }
        if (out.size() < 3) return out;
        List<Double> sorted = new ArrayList<>(out); Collections.sort(sorted);
        double med = sorted.get(sorted.size()/2);
        List<Double> clean = new ArrayList<>();
        for (double d:out) if (Math.abs(d-med) <= med*0.22) clean.add(d);
        return clean;
    }

    private static double rmssd(List<Double> ints) {
        if (ints.size()<2) return 0;
        double sum=0; int n=0;
        for (int i=1;i<ints.size();i++) { double d=ints.get(i)-ints.get(i-1); sum += d*d; n++; }
        return Math.sqrt(sum/Math.max(1,n));
    }

    private static int computeStress(int bpm, double rmssd, boolean reliable) {
        if (!reliable || bpm <= 0 || rmssd <= 0) return -1;
        double score = 70.0 - Math.min(45.0, rmssd*0.75) + Math.max(0, bpm-65)*0.65;
        return (int)Math.round(Math.max(0, Math.min(100, score)));
    }

    private static double stdDev(Deque<Sample> samples) {
        if (samples.size()<4) return 1;
        double mean=0; for(Sample s:samples) mean+=s.v; mean/=samples.size();
        double q=0; for(Sample s:samples){double d=s.v-mean;q+=d*d;}
        return Math.sqrt(q/samples.size());
    }
}
