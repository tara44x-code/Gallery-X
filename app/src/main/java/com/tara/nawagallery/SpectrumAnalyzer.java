package com.tara.nawagallery;

/**
 * Turns blocks of audio samples into a small spectrum (pure Java, no Android).
 * The music visualizer analyses the song file itself with this, so it needs no microphone permission.
 */
public final class SpectrumAnalyzer {
    public static final int FFT = 1024;
    public static final int BANDS = 48;
    private static final float MIN_DB = -62f;

    private final float[] window = new float[FFT];
    private final float[] re = new float[FFT];
    private final float[] im = new float[FFT];
    private final int[] bandFrom = new int[BANDS];
    private final int[] bandTo = new int[BANDS];
    private final float[] tilt = new float[BANDS];
    public final int sampleRate;

    public SpectrumAnalyzer(int sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < FFT; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1)));
        // Bands spaced like hearing (logarithmic) from 40 Hz up to 16 kHz.
        double low = 40, high = Math.min(16000, sampleRate / 2.0 - 1);
        int previous = 1;
        for (int b = 0; b < BANDS; b++) {
            double f0 = low * Math.pow(high / low, (double) b / BANDS);
            double f1 = low * Math.pow(high / low, (double) (b + 1) / BANDS);
            int from = Math.max(previous, (int) Math.floor(f0 * FFT / sampleRate));
            int to = Math.max(from + 1, (int) Math.ceil(f1 * FFT / sampleRate));
            to = Math.min(to, FFT / 2);
            from = Math.min(from, to - 1);
            bandFrom[b] = from;
            bandTo[b] = to;
            previous = to;
            double centre = Math.sqrt(f0 * f1);
            tilt[b] = (float) (3.0 * Math.log(centre / 1000.0) / Math.log(2)); // +3 dB per octave, so highs show up
        }
    }

    /**
     * @param samples FFT mono samples in -1..1
     * @param bands   receives BANDS values 0..255
     * @return loudness 0..255
     */
    public int analyze(float[] samples, byte[] bands) {
        double energy = 0;
        for (int i = 0; i < FFT; i++) {
            float s = samples[i];
            energy += s * s;
            re[i] = s * window[i];
            im[i] = 0f;
        }
        fft(re, im);
        for (int b = 0; b < BANDS; b++) {
            float peak = 0f;
            for (int k = bandFrom[b]; k < bandTo[b]; k++) {
                float m = re[k] * re[k] + im[k] * im[k];
                if (m > peak) peak = m;
            }
            float magnitude = (float) Math.sqrt(peak) * 4f / FFT; // Hann window halves the amplitude
            float db = (float) (20 * Math.log10(magnitude + 1e-9)) + tilt[b];
            float level = (db - MIN_DB) / -MIN_DB;
            bands[b] = (byte) Math.round(255 * Math.max(0f, Math.min(1f, level)));
        }
        double rms = Math.sqrt(energy / FFT);
        double rmsDb = 20 * Math.log10(rms + 1e-9);
        return (int) Math.round(255 * Math.max(0, Math.min(1, (rmsDb + 48) / 48)));
    }

    /** In-place iterative radix-2 FFT. */
    static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            float wr = (float) Math.cos(angle), wi = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float cr = 1f, ci = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float tr = re[b] * cr - im[b] * ci;
                    float ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    float next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }

    /** Index of the band that contains {@code hz} (for tests and tuning). */
    int bandOf(double hz) {
        int bin = (int) Math.round(hz * FFT / sampleRate);
        for (int b = 0; b < BANDS; b++) if (bin >= bandFrom[b] && bin < bandTo[b]) return b;
        return -1;
    }

    /**
     * Analysed frames of one song, appended by the decoder thread and read by the drawing thread.
     * One frame per FFT samples (about 23 ms at 44.1 kHz).
     */
    public static final class Frames {
        private static final int CHUNK = 2048;
        private final java.util.ArrayList<byte[]> bandChunks = new java.util.ArrayList<>();
        private final java.util.ArrayList<byte[]> levelChunks = new java.util.ArrayList<>();
        private volatile int count;
        public final int sampleRate;

        public Frames(int sampleRate) { this.sampleRate = sampleRate; }

        public int count() { return count; }

        synchronized void add(byte[] bands, int level) {
            int chunk = count / CHUNK, offset = count % CHUNK;
            if (chunk == bandChunks.size()) {
                bandChunks.add(new byte[CHUNK * BANDS]);
                levelChunks.add(new byte[CHUNK]);
            }
            System.arraycopy(bands, 0, bandChunks.get(chunk), offset * BANDS, BANDS);
            levelChunks.get(chunk)[offset] = (byte) level;
            count++;
        }

        /** Frame index for a playback position, or -1 if that part is not analysed yet. */
        public int indexAt(long positionMs) {
            long index = positionMs * sampleRate / 1000L / FFT;
            return index >= 0 && index < count ? (int) index : -1;
        }

        /** Copies frame {@code index} into {@code out} as 0..1 values; returns its loudness 0..1. */
        public synchronized float read(int index, float[] out) {
            if (index < 0 || index >= count) return 0f;
            byte[] chunk = bandChunks.get(index / CHUNK);
            int base = (index % CHUNK) * BANDS;
            for (int b = 0; b < BANDS && b < out.length; b++) out[b] = (chunk[base + b] & 0xff) / 255f;
            return (levelChunks.get(index / CHUNK)[index % CHUNK] & 0xff) / 255f;
        }
    }

    /** Collects decoded mono samples into FFT-sized blocks and analyses each one. */
    public static final class Feeder {
        private final SpectrumAnalyzer analyzer;
        private final Frames frames;
        private final float[] block = new float[FFT];
        private final byte[] bands = new byte[BANDS];
        private int filled;

        public Feeder(int sampleRate) {
            analyzer = new SpectrumAnalyzer(sampleRate);
            frames = new Frames(sampleRate);
        }

        public Frames frames() { return frames; }

        public void push(float sample) {
            block[filled++] = sample;
            if (filled == FFT) {
                int level = analyzer.analyze(block, bands);
                frames.add(bands, level);
                filled = 0;
            }
        }
    }
}
