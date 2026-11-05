package atg.jni;

/**
 * Created by Administrator on 2019-05-05.
 */

public class ATGJNI {
    static {
        System.loadLibrary("atg");
    }

    // 得到容量升
    public static native float getLitre(float oilHieght, int[] hightArray,
                                        double[] litreArray);
}
