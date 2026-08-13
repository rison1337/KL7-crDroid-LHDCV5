import android.os.FileObserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LhdcControlDaemon {
    private static final String DIRECTORY =
            "/data/user/0/com.rison.lhdccontrol/files";
    private static final String REQUEST = "codec-request";
    private static final String RESPONSE = "codec-response";
    private static final String TOKEN = "KL7-1776559493-LHDC-114";
    private static final Object REQUEST_LOCK = new Object();
    private static String lastId = "";

    private static boolean valid(String[] args) {
        if (args.length == 1 && "list".equals(args[0])) return true;
        if (args.length != 9 || !"set".equals(args[0])) return false;
        if (!args[1].matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) return false;
        try {
            for (int i = 2; i < args.length; i++) Long.parseLong(args[i]);
            return true;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String readFirstLine(File file) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }

    private static void handle(File requestFile, File responseFile, String bridge) {
        synchronized (REQUEST_LOCK) {
            String id = null;
            PrintWriter writer = null;
            try {
                String request = readFirstLine(requestFile);
                if (request == null) return;
                String[] fields = request.split("\\t", 3);
                if (fields.length != 3 || !TOKEN.equals(fields[0])
                        || !fields[1].matches("[0-9a-f]{8,32}")) {
                    System.err.println("Rejected malformed or unauthorized request");
                    return;
                }
                id = fields[1];
                if (id.equals(lastId)) return;
                lastId = id;
                writer = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(responseFile, false), StandardCharsets.UTF_8), true);
                writer.println("RESULT\t" + id);

                String[] args = fields[2].trim().split(" +");
                if (!valid(args)) {
                    writer.println("ERROR\tinvalid request");
                    writer.println("EXIT\t2");
                    return;
                }
                List<String> command = new ArrayList<>();
                command.add("/system/bin/app_process");
                command.add("/system/bin");
                command.add("LhdcControlBridge");
                command.addAll(Arrays.asList(args));
                ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
                processBuilder.environment().put("CLASSPATH", bridge);
                Process process = processBuilder.start();
                process.getOutputStream().close();
                try (BufferedReader processReader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = processReader.readLine()) != null) writer.println(line);
                }
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    writer.println("ERROR\ttimeout");
                    writer.println("EXIT\t124");
                } else {
                    writer.println("EXIT\t" + process.exitValue());
                }
            } catch (Throwable error) {
                error.printStackTrace(System.err);
                if (writer != null) {
                    writer.println("ERROR\t" + error.getClass().getSimpleName()
                            + ": " + error.getMessage());
                    writer.println("EXIT\t127");
                }
            } finally {
                if (writer != null) writer.close();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("bridge jar path required");
        final long started = System.currentTimeMillis();
        final File directory = new File(DIRECTORY);
        while (!directory.isDirectory()) {
            Thread.sleep(500);
        }
        final File request = new File(directory, REQUEST);
        final File response = new File(directory, RESPONSE);
        FileObserver observer = new FileObserver(directory,
                FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override public void onEvent(int event, String path) {
                if (REQUEST.equals(path)) handle(request, response, args[0]);
            }
        };
        observer.startWatching();
        System.out.println("READY private-file-channel");
        if (request.isFile() && request.lastModified() >= started) {
            handle(request, response, args[0]);
        }
        while (true) Thread.sleep(TimeUnit.HOURS.toMillis(24));
    }
}
