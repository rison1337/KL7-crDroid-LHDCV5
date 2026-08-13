import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public final class BtAudioStress {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        boolean lowLatency = args.length > 1 && Boolean.parseBoolean(args[1]);
        final int sampleRate = 48000;
        final int frames = 960;
        final byte[] pcm = new byte[frames * 2];
        long writtenTotal = 0;
        long sampleIndex = 0;

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(lowLatency ? AudioAttributes.USAGE_GAME : AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(16384)
                .setPerformanceMode(lowLatency
                        ? AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                        : AudioTrack.PERFORMANCE_MODE_NONE)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack failed to initialize");
        }
        track.play();
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < frames; i++, sampleIndex++) {
                short value = (short) (400.0 * Math.sin(
                        2.0 * Math.PI * 523.25 * sampleIndex / sampleRate));
                pcm[i * 2] = (byte) value;
                pcm[i * 2 + 1] = (byte) (value >> 8);
            }
            int written = track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
            if (written < 0) throw new IllegalStateException("Audio write failed: " + written);
            writtenTotal += written;
        }
        track.stop();
        track.release();
        System.out.println("BtAudioStress seconds=" + seconds + " lowLatency=" + lowLatency
                + " bytes=" + writtenTotal);
    }
}
