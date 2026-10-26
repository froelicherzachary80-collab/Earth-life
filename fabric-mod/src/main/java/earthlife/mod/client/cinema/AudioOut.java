package earthlife.mod.client.cinema;

import earthlife.mod.EarthLifeMod;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Streams a film's sound (48 kHz stereo 16-bit PCM from FFmpeg) through Minecraft's own OpenAL device with a queue of
 * buffers. The number of samples played so far is the film's master clock (the picture follows it). Without a sound
 * device (e.g. a headless test machine) it reports {@link #ok} = false and the film runs on the wall clock silently.
 */
final class AudioOut implements AutoCloseable {
    static final int RATE = 48_000, CHUNK_FRAMES = 4096, BUFFERS = 10;
    private int source;
    private final int[] buffers = new int[BUFFERS];
    private final int[] bufferFrames = new int[BUFFERS];
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private long processedFrames;
    private boolean playing, closed;
    final boolean ok;

    AudioOut() {
        boolean good;
        try {
            source = AL10.alGenSources();
            AL10.alGenBuffers(buffers);
            for (int i = 0; i < BUFFERS; i++) free.add(i);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
            good = AL10.alGetError() == AL10.AL_NO_ERROR;
        } catch (Throwable t) {
            EarthLifeMod.LOG.info("Cinema sound unavailable ({}); films play silently", t.toString());
            good = false;
        }
        ok = good;
    }

    private int index(int buffer) { for (int i = 0; i < BUFFERS; i++) if (buffers[i] == buffer) return i; return -1; }

    private void reclaim() {
        int n = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int k = 0; k < n; k++) {
            int b = AL10.alSourceUnqueueBuffers(source);
            int i = index(b);
            if (i >= 0) { processedFrames += bufferFrames[i]; free.add(i); }
        }
    }

    /** Queues PCM; blocks (real-time pacing) while all buffers are in use. Returns false once closed. */
    boolean write(byte[] pcm, int len) throws InterruptedException {
        while (true) {
            synchronized (this) {
                if (closed || !ok) return false;
                reclaim();
                if (!free.isEmpty()) {
                    int i = free.poll();
                    ByteBuffer bb = MemoryUtil.memAlloc(len);
                    try {
                        bb.put(pcm, 0, len).flip();
                        AL10.alBufferData(buffers[i], AL10.AL_FORMAT_STEREO16, bb, RATE);
                    } finally { MemoryUtil.memFree(bb); }
                    bufferFrames[i] = len / 4;
                    AL10.alSourceQueueBuffers(source, buffers[i]);
                    int st = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                    if (st != AL10.AL_PLAYING && (BUFFERS - free.size() >= 3 || playing)) { AL10.alSourcePlay(source); playing = true; }
                    return true;
                }
            }
            Thread.sleep(8);
        }
    }

    /** Milliseconds of sound actually played. */
    synchronized long playedMs() {
        if (!ok || closed) return 0;
        reclaim();
        int off = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        return (processedFrames + Math.max(0, off)) * 1000 / RATE;
    }

    synchronized boolean started() { return ok && playing; }

    synchronized void gain(float g) { if (ok && !closed) AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0, Math.min(1, g))); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!ok) return;
        try {
            AL10.alSourceStop(source);
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(source);
            AL10.alDeleteBuffers(buffers);
        } catch (Throwable ignored) {}
    }
}
