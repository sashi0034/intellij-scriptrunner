package dev.sashi0034;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

/** An external process fixture requiring no Python, shell, or other installed runtime. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        if (args.length > 0 && args[0].endsWith(".script-runner-fixture")) {
            args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length > 0 && args[0].equals("sleep")) {
            System.out.println("READY");
            System.out.flush();
            Thread.sleep(120_000);
            return;
        }
        if (args.length > 0 && args[0].equals("tree")) {
            String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), "sleep").start();
            System.out.println("CHILD=" + child.pid());
            System.out.flush();
            Thread.sleep(120_000);
            return;
        }
        System.out.println("OUT=こんにちは");
        System.out.println("\u001b[31mCOLOR\u001b[0m");
        System.err.println("ERR=problem");
        System.out.println("CWD=" + Path.of("").toAbsolutePath().normalize());
        System.out.println("ENV=" + System.getenv("SCRIPT_RUNNER_TEST"));
        System.out.println("PARENT=" + (System.getenv(isWindows() ? "SystemRoot" : "PATH") != null));
        for (int i = 0; i < args.length; i++) System.out.println("ARG" + i + "=" + args[i]);
        System.out.println("READY");
        System.out.flush();
        String input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        System.out.println("INPUT=" + input);
        System.exit(7);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
