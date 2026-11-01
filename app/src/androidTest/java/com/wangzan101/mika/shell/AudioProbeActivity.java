package com.wangzan101.mika.shell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;

/**
 * 正在出声的探针页:打开后一直循环播放一段无声的声音(播放器状态是「在播」,听不到),关掉时停下。
 * 其余同 {@link ShellProbeActivity}(把屏、任务报给测试)。任务结束时「正在出声的 App 默认关掉」用它来测。
 */
public class AudioProbeActivity extends ShellProbeActivity {
    private static final int RATE = 8000;
    private AudioTrack track;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        short[] silence = new short[RATE / 2];
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.length * 2)
                .build();
        track.write(silence, 0, silence.length);
        track.setLoopPoints(0, silence.length, -1);
        track.play();
    }

    @Override
    protected void onDestroy() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // 没开始播放就被关掉了
            }
            track.release();
            track = null;
        }
        super.onDestroy();
    }
}
