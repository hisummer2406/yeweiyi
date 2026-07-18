package com.sansen.yeweiyi.utils;

/**
 * Created by PC on 2018-03-01.
 */
public class Bspatch {
    public static native int bsPatch(String oldPath, String newPatch, String patchPath);
    static {
        System.loadLibrary("timBsPatch");
    }
}
