package it.belloworld.mercurygram.transcribe;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;

/**
 * [MG] Decodes a compressed audio file (voice message OGG/Opus, round-video
 * MP4/AAC, etc.) to a mono 16 kHz float PCM buffer suitable for whisper.cpp.
 *
 * Uses only platform {@link MediaExtractor}/{@link MediaCodec} APIs — no extra
 * native dependency. Down-mixes to mono and linearly resamples to 16 kHz.
 */
final class AudioDecoder {

    static final int TARGET_RATE = 16000;
    private static final long DEQUEUE_TIMEOUT_US = 10000;

    private AudioDecoder() {
    }

    /**
     * @param path absolute path to the media file
     * @return mono 16 kHz PCM samples in [-1, 1]
     * @throws IOException if the file has no decodable audio track or decoding fails
     */
    static float[] decodeTo16kMono(String path) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("no audio track in " + path);
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_RATE;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            // PCM sample format of the decoder OUTPUT. Many Android decoders
            // (notably Opus on recent devices) emit float, not 16-bit — reading
            // float bytes as int16 yields noise → whisper finds no speech.
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            // Accumulate decoded mono float samples at the source rate, then resample once.
            ArrayList<float[]> chunks = new ArrayList<>();
            int totalMono = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int sampleSize = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            float[] mono = toMonoFloat(outBuf, srcChannels, pcmEncoding);
                            if (mono.length > 0) {
                                chunks.add(mono);
                                totalMono += mono.length;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        srcChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                }
            }

            float[] mono = flatten(chunks, totalMono);
            float[] out = srcRate == TARGET_RATE ? mono : resampleLinear(mono, srcRate, TARGET_RATE);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("MgWhisper decode: mime=" + mime + " srcRate=" + srcRate
                        + " ch=" + srcChannels + " pcmEnc=" + pcmEncoding
                        + " srcSamples=" + mono.length + " out16kSamples=" + out.length
                        + " (" + String.format(java.util.Locale.US, "%.1f", out.length / (float) TARGET_RATE) + "s)");
            }
            return out;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Converts a decoded PCM buffer to mono float in [-1, 1], averaging
     * interleaved channels. Handles both 16-bit int and 32-bit float output
     * (whichever the platform decoder emits).
     */
    private static float[] toMonoFloat(ByteBuffer buf, int channels, int pcmEncoding) {
        if (channels < 1) {
            channels = 1;
        }
        buf.order(ByteOrder.LITTLE_ENDIAN);
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            java.nio.FloatBuffer floats = buf.asFloatBuffer();
            int n = floats.remaining();
            int frames = n / channels;
            float[] out = new float[frames];
            for (int f = 0; f < frames; f++) {
                float sum = 0f;
                int base = f * channels;
                for (int c = 0; c < channels; c++) {
                    sum += floats.get(base + c);
                }
                out[f] = sum / channels;
            }
            return out;
        }
        // Default / ENCODING_PCM_16BIT.
        ShortBuffer shorts = buf.asShortBuffer();
        int n = shorts.remaining();
        int frames = n / channels;
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            int base = f * channels;
            for (int c = 0; c < channels; c++) {
                sum += shorts.get(base + c) / 32768f;
            }
            out[f] = sum / channels;
        }
        return out;
    }

    private static float[] flatten(ArrayList<float[]> chunks, int total) {
        float[] out = new float[total];
        int pos = 0;
        for (float[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }

    /** Simple linear-interpolation resampler. Adequate for speech ASR input. */
    private static float[] resampleLinear(float[] in, int srcRate, int dstRate) {
        if (in.length == 0) {
            return in;
        }
        long outLenL = (long) in.length * dstRate / srcRate;
        int outLen = (int) Math.max(1, outLenL);
        float[] out = new float[outLen];
        double step = (double) srcRate / dstRate;
        double pos = 0;
        for (int i = 0; i < outLen; i++) {
            int idx = (int) pos;
            double frac = pos - idx;
            float a = in[idx];
            float b = idx + 1 < in.length ? in[idx + 1] : a;
            out[i] = (float) (a + (b - a) * frac);
            pos += step;
        }
        return out;
    }
}
