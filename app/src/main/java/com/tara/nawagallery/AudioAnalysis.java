package com.tara.nawagallery;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes a song in the background and analyses it with {@link SpectrumAnalyzer}, so the
 * visualizer reacts to the real music without the microphone permission Android's Visualizer needs.
 */
final class AudioAnalysis {
    final Uri uri;
    private volatile boolean cancelled;
    private volatile SpectrumAnalyzer.Frames frames;

    AudioAnalysis(Context context, Uri uri) {
        this.uri = uri;
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> run(app), "gx-audio-analysis");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    SpectrumAnalyzer.Frames frames() { return frames; }

    void cancel() { cancelled = true; }

    private void run(Context context) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = candidate;
                    break;
                }
            }
            if (format == null) return;
            int channels = Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            SpectrumAnalyzer.Feeder feeder = new SpectrumAnalyzer.Feeder(sampleRate);
            frames = feeder.frames();
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (!cancelled) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    channels = Math.max(1, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    int rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (rate != sampleRate) {
                        sampleRate = rate;
                        feeder = new SpectrumAnalyzer.Feeder(rate);
                        frames = feeder.frames();
                    }
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        ByteBuffer pcm = buffer.slice().order(ByteOrder.nativeOrder());
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer samples = pcm.asFloatBuffer();
                            int count = samples.remaining() / channels;
                            for (int i = 0; i < count; i++) {
                                float sum = 0f;
                                for (int c = 0; c < channels; c++) sum += samples.get();
                                feeder.push(sum / channels);
                            }
                        } else {
                            ShortBuffer samples = pcm.asShortBuffer();
                            int count = samples.remaining() / channels;
                            for (int i = 0; i < count; i++) {
                                float sum = 0f;
                                for (int c = 0; c < channels; c++) sum += samples.get();
                                feeder.push(sum / channels / 32768f);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } catch (Exception ignored) {
            // Unsupported or damaged file: the visualizer falls back to a calm idle animation.
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            extractor.release();
        }
    }
}
