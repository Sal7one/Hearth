package com.sal7one.common_jni;

/**
 * Host-only JNI probe for the experimental Ming runtime. Not for the app: a production
 * wrapper must stream and run on a worker thread.
 */
public final class MingNative {
    static {
        String library = System.getProperty("ming.library");
        if (library == null || library.isEmpty()) {
            throw new UnsatisfiedLinkError("pass -Dming.library=/absolute/path/to/libming_jni.dylib");
        }
        System.load(library);
    }

    private MingNative() {}

    public static native int abiVersion();
    public static native String modelName();
    public static native String synthesizeWav(
            String bundleDir, String text, String voice, String outWav,
            int preset, long seed, int threads);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: MingNative <bundle-dir> <text> <out.wav> [voice] [preset] [threads]");
            System.exit(2);
        }
        String voice = args.length > 3 ? args[3] : "US_James";
        int preset = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        int threads = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        System.out.println("abi=" + abiVersion() + " model=" + modelName());
        long start = System.nanoTime();
        String stats = synthesizeWav(args[0], args[1], voice, args[2], preset, 1895L, threads);
        double wall = (System.nanoTime() - start) / 1e9;
        System.out.printf("wall=%.2fs %s%n", wall, stats);
    }
}
