
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {

    // Custom JLine parser: preserve backslashes for our shell parser
    private static class ShellParser extends DefaultParser {
        @Override
        public boolean isEscapeChar(char ch) {
            return false;
        }
    }

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

    // Parse commands, quotes, backslashes and redirection operators
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
                } else {
                    current.append('\\');
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

            // Output and error redirection operators
            if (ch == '>' && !inSingleQuotes && !inDoubleQuotes) {
                boolean append = i + 1 < input.length()
                        && input.charAt(i + 1) == '>';

                if (append) {
                    i++;
                }

                if (current.toString().equals("1")) {
                    current.setLength(0);
                    args.add(append ? "1>>" : "1>");
                } else if (current.toString().equals("2")) {
                    current.setLength(0);
                    args.add(append ? "2>>" : "2>");
                } else {
                    if (current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                    }

                    args.add(append ? ">>" : ">");
                }

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

    private static void outputResult(
            String output,
            String outputFile,
            boolean append
    ) throws Exception {

        if (outputFile != null) {
            if (append) {
                Files.writeString(
                        new File(outputFile).toPath(),
                        output,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } else {
                Files.writeString(
                        new File(outputFile).toPath(),
                        output,
                        StandardCharsets.UTF_8
                );
            }
        } else {
            System.out.print(output);
        }
    }

    private static void prepareErrorFile(
            String errorFile,
            boolean appendError
    ) throws Exception {

        if (errorFile != null) {
            if (appendError) {
                Files.writeString(
                        new File(errorFile).toPath(),
                        "",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } else {
                Files.writeString(
                        new File(errorFile).toPath(),
                        "",
                        StandardCharsets.UTF_8
                );
            }
        }
    }

    private static void writeError(
            String error,
            String errorFile,
            boolean appendError
    ) throws Exception {

        if (errorFile != null) {
            if (appendError) {
                Files.writeString(
                        new File(errorFile).toPath(),
                        error,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } else {
                Files.writeString(
                        new File(errorFile).toPath(),
                        error,
                        StandardCharsets.UTF_8
                );
            }
        } else {
            System.err.print(error);
        }
    }

    public static void main(String[] args) throws Exception {

        // Complete builtins and executable files found in PATH.
        Completer completer = (reader, line, candidates) -> {
            if (line.wordIndex() == 0) {
                String partial = line.word();

                if ("echo".startsWith(partial)) {
                    candidates.add(new Candidate("echo"));
                }

                if ("exit".startsWith(partial)) {
                    candidates.add(new Candidate("exit"));
                }

                String pathEnv = System.getenv("PATH");

                if (pathEnv != null) {
                    Set<String> added = new HashSet<>();

                    for (String path : pathEnv.split(
                            java.util.regex.Pattern.quote(File.pathSeparator))) {

                        File directory = new File(path);

                        if (!directory.isDirectory() || !directory.canRead()) {
                            continue;
                        }

                        File[] files = directory.listFiles();

                        if (files == null) {
                            continue;
                        }

                        for (File file : files) {
                            String name = file.getName();

                            if (file.isFile()
                                    && file.canExecute()
                                    && name.startsWith(partial)
                                    && added.add(name)) {
                                candidates.add(new Candidate(name));
                            }
                        }
                    }
                }
            }
        };

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new ShellParser())
                    .completer(completer)
                    .build();

            while (true) {
                String input;

                try {
                    input = reader.readLine("$ ");
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }

                List<String> tokens = parseCommand(input);

                if (tokens.isEmpty()) {
                    continue;
                }

                // Handle redirection
                String outputFile = null;
                String errorFile = null;

                boolean appendOutput = false;
                boolean appendError = false;

                List<String> commandTokens = new ArrayList<>();

                for (int i = 0; i < tokens.size(); i++) {
                    String token = tokens.get(i);

                    if (token.equals(">")
                            || token.equals("1>")
                            || token.equals(">>")
                            || token.equals("1>>")
                            || token.equals("2>")
                            || token.equals("2>>")) {

                        if (i + 1 < tokens.size()) {
                            String filename = tokens.get(++i);

                            if (token.equals("2>") || token.equals("2>>")) {
                                errorFile = filename;
                                appendError = token.equals("2>>");
                            } else {
                                outputFile = filename;
                                appendOutput = token.equals(">>")
                                        || token.equals("1>>");
                            }
                        } else {
                            System.out.println(
                                    "syntax error: expected filename after redirection"
                            );

                            commandTokens.clear();
                            break;
                        }
                    } else {
                        commandTokens.add(token);
                    }
                }

                if (commandTokens.isEmpty()) {
                    continue;
                }

                String command = commandTokens.get(0);

                // Exit builtin
                if (command.equals("exit")) {
                    prepareErrorFile(errorFile, appendError);
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

                    prepareErrorFile(errorFile, appendError);

                    outputResult(
                            output.toString(),
                            outputFile,
                            appendOutput
                    );
                }

                // Type builtin
                else if (command.equals("type")) {
                    prepareErrorFile(errorFile, appendError);

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

                    outputResult(
                            output,
                            outputFile,
                            appendOutput
                    );
                }

                // External commands
                else {
                    File executable = findExecutable(command);

                    if (executable == null) {
                        String error = command + ": command not found\n";
                        writeError(error, errorFile, appendError);
                        continue;
                    }

                    // Preserve original command name as argv[0]
                    ProcessBuilder pb = new ProcessBuilder(commandTokens);

                    // Configure stdout
                    if (outputFile != null) {
                        if (appendOutput) {
                            pb.redirectOutput(
                                    ProcessBuilder.Redirect.appendTo(
                                            new File(outputFile)
                                    )
                            );
                        } else {
                            pb.redirectOutput(new File(outputFile));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    // Configure stderr
                    if (errorFile != null) {
                        if (appendError) {
                            pb.redirectError(
                                    ProcessBuilder.Redirect.appendTo(
                                            new File(errorFile)
                                    )
                            );
                        } else {
                            pb.redirectError(new File(errorFile));
                        }
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

                    Process process = pb.start();
                    process.waitFor();
                }
            }
        }
    }
}
