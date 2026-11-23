package com.fadcam.root;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal, dependency-free root shell bridge (plain {@code su}).
 * Everything here is best-effort: when root is missing or denied, every call
 * returns a failed {@link Result} and the app behaves exactly as before.
 */
public final class RootBridge {

    private static final String TAG = "RootBridge";

    public static final class Result {
        public final int exitCode;
        @NonNull public final String out;
        @NonNull public final String err;

        Result(int exitCode, String out, String err) {
            this.exitCode = exitCode;
            this.out = out == null ? "" : out.trim();
            this.err = err == null ? "" : err.trim();
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private static final ExecutorService IO = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RootBridge-io");
            t.setDaemon(true);
            return t;
        }
    });

    private static volatile Boolean rootAvailable = null;

    private RootBridge() {}

    /** Cached for the lifetime of the process; the first call may show the root-grant prompt. */
    public static boolean isRootAvailable() {
        Boolean cached = rootAvailable;
        if (cached != null) return cached;
        synchronized (RootBridge.class) {
            if (rootAvailable == null) {
                Result r = exec(8000, "id");
                rootAvailable = r.ok() && r.out.contains("uid=0");
                FLog.i(TAG, "Root available: " + rootAvailable);
            }
            return rootAvailable;
        }
    }

    /** Runs the commands in one {@code su} session. Never throws. */
    @NonNull
    public static Result exec(long timeoutMs, String... commands) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").start();
            final Process proc = p;
            try (DataOutputStream os = new DataOutputStream(proc.getOutputStream())) {
                for (String c : commands) {
                    os.write((c + "\n").getBytes(StandardCharsets.UTF_8));
                }
                os.write("exit\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            Future<String> outF = IO.submit(() -> readAll(proc.getInputStream()));
            Future<String> errF = IO.submit(() -> readAll(proc.getErrorStream()));
            Future<Integer> exitF = IO.submit(proc::waitFor);
            int code = exitF.get(timeoutMs, TimeUnit.MILLISECONDS);
            return new Result(code, outF.get(1, TimeUnit.SECONDS), errF.get(1, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            FLog.w(TAG, "su command timed out");
            if (p != null) p.destroy();
            return new Result(-2, "", "timeout");
        } catch (IOException e) {
            // su binary missing / permission denied
            return new Result(-1, "", String.valueOf(e.getMessage()));
        } catch (Exception e) {
            if (p != null) p.destroy();
            return new Result(-3, "", String.valueOf(e));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
