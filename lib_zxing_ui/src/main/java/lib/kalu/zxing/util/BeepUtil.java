package lib.kalu.zxing.util;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Vibrator;

import lib.kalu.zxing.R;
import lib.kalu.zxing.contentprovider.ContextProviderZxing;
import lib.kalu.zxing.util.LogUtil;

import java.io.Closeable;
import java.io.IOException;

/**
 * @description:
 * @date: 2021-05-07 14:55
 */
public final class BeepUtil {

    // 默认无声
    public static boolean beep = false;
    private static MediaPlayer mediaPlayer = new MediaPlayer();

    public static final void beep() {
        try {

            if (null == mediaPlayer) {
                mediaPlayer = new MediaPlayer();
            }

            AssetFileDescriptor file = ContextProviderZxing.mContext.getResources().openRawResourceFd(R.raw.moudle_zxing_raw_beep);
            mediaPlayer.setDataSource(file.getFileDescriptor(), file.getStartOffset(), file.getLength());
//             mediaPlayer.setOnErrorListener(this);
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setLooping(false);
            mediaPlayer.prepare();
        } catch (Exception e) {
            LogUtil.log(e.getMessage(), e);
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    public static final void release() {
        try {
            mediaPlayer.release();
            mediaPlayer = null;
        } catch (Exception e) {
            LogUtil.log(e.getMessage(), e);
        }
    }
//
//    @Override
//    public boolean onError(MediaPlayer mp, int what, int extra) {
//        return false;
//    }
//
//    @Override
//    public void close() throws IOException {
//        try {
//            mediaPlayer.release();
//            mediaPlayer = null;
//        } catch (Exception e) {
//            LogUtil.log(e.getMessage(), e);
//        }
//    }
}