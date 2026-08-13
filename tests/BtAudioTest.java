import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public final class BtAudioTest {
    public static void main(String[] args) throws Exception {
        final int sampleRate = 48000;
        final int samples = sampleRate / 2;
        final byte[] pcm = new byte[samples * 2];

        for (int i = 0; i < samples; i++) {
            double envelope = Math.sin(Math.PI * i / samples);
            short value = (short) (1200.0 * envelope
                    * Math.sin(2.0 * Math.PI * 880.0 * i / sampleRate));
            pcm[i * 2] = (byte) value;
            pcm[i * 2 + 1] = (byte) (value >> 8);
        }

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack(attributes, format,
                Math.max(minBuffer, 16384), AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack failed to initialize");
        }

        track.play();
        int written = track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
        Thread.sleep(700);
        track.stop();
        track.release();
        System.out.println("BtAudioTest written=" + written);
    }
}
