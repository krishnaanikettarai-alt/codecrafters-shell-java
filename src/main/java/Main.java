
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {

    private static boolean isBuiltin(String command) {
        return command.equals("echo")
                || command.equals("exit")
                || command.equals("type");
    }

    private static File findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] paths = pathEnv.split(File.pathSeparator);

        for (String path : paths) {
            File file = new File(path, command);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file;
            }
        }

        return null;
    }

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            // Backslash handling inside double quotes
            if (ch == '\\' && inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);

                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
                continue;
            }

            // Backslash escaping outside quotes
            if (ch == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
                continue;
            }

            // Single quotes
            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            // Double quotes
            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            // Output redirection operator
            if (ch == '>' && !inSingleQuotes && !inDoubleQuotes) {

                // Handle explicit stdout descriptor: 1>
                if (current.toString().equals("1")) {
                    current.setLength(0);
                } else if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }

                args.add(">");
                continue;
            }

            // Argument separator
            if (Character.isWhitespace(ch)
                    && !inSingleQuotes
                    && !inDoubleQuotes) {

                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    private static void outputResult(String output, String outputFile)
            throws Exception {

        if (outputFile != null) {
            Files.writeString(
                    new File(outputFile).toPath(),
                    output,
                    StandardCharsets.UTF_8
            );
        } else {
            System.out.print(output);
        }
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String input = scanner.nextLine();

            List<String> tokens = parseCommand(input);

            if (tokens.isEmpty()) {
                continue;
            }

            // Handle output redirection
            String outputFile = null;
            List<String> commandTokens = new ArrayList<>();

            for (int i = 0; i < tokens.size(); i++) {

                if (tokens.get(i).equals(">")) {

                    if (i + 1 < tokens.size()) {
                        outputFile = tokens.get(i + 1);
                        i++;
                    } else {
                        System.out.println(
                                "syntax error: expected filename after '>'"
                        );
                        commandTokens.clear();
                        break;
                    }

                } else {
                    commandTokens.add(tokens.get(i));
                }
            }

            if (commandTokens.isEmpty()) {
                continue;
            }

            String command = commandTokens.get(0);

            // Exit builtin
            if (command.equals("exit")) {
                break;
            }

            // Echo builtin
            else if (command.equals("echo")) {

                StringBuilder output = new StringBuilder();

                for (int i = 1; i < commandTokens.size(); i++) {
                    if (i > 1) {
                        output.append(" ");
                    }
                    output.append(commandTokens.get(i));
                }

                output.append("\n");

                outputResult(output.toString(), outputFile);
            }

            // Type builtin
            else if (command.equals("type")) {

                if (commandTokens.size() < 2) {
                    continue;
                }

                String cmd = commandTokens.get(1);
                String output;

                if (isBuiltin(cmd)) {
                    output = cmd + " is a shell builtin\n";
                } else {
                    File executable = findExecutable(cmd);

                    if (executable != null) {
                        output = cmd + " is "
                                + executable.getAbsolutePath() + "\n";
                    } else {
                        output = cmd + ": not found\n";
                    }
                }

                outputResult(output, outputFile);
            }

            // External commands
            else {

                File executable = findExecutable(command);

                if (executable == null) {
                    System.out.println(command + ": command not found");
                    continue;
                }

                // Preserve the original command name as argv[0].
                // ProcessBuilder resolves the executable through PATH.
                ProcessBuilder pb = new ProcessBuilder(commandTokens);

                if (outputFile != null) {
                    pb.redirectOutput(new File(outputFile));
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.inheritIO();
                }

                Process process = pb.start();
                process.waitFor();
            }
        }

        scanner.close();
    }
}
