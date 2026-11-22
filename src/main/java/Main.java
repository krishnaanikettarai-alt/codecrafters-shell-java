
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

public class Main {

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

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

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

            if (ch == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                } else {
                    current.append('\\');
                }

                continue;
            }

            if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

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

        final String[] lastTabPrefix = {null};

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new ShellParser())
                    .build();

            reader.getWidgets().put("path-complete", () -> {
                String buffer = reader.getBuffer().toString();
                int cursor = reader.getBuffer().cursor();

                if (cursor != buffer.length()
                        || buffer.chars().anyMatch(Character::isWhitespace)) {
                    return false;
                }

                String partial = buffer;
                TreeSet<String> matches = new TreeSet<>();

                if ("echo".startsWith(partial)) {
                    matches.add("echo");
                }

                if ("exit".startsWith(partial)) {
                    matches.add("exit");
                }

                String pathEnv = System.getenv("PATH");

                if (pathEnv != null) {
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
                                    && name.startsWith(partial)) {
                                matches.add(name);
                            }
                        }
                    }
                }

                if (matches.isEmpty()) {
                    terminal.writer().write('\007');
                    terminal.writer().flush();
                    lastTabPrefix[0] = null;
                    return true;
                }

                // Exactly one match: complete the command and add a space.
                if (matches.size() == 1) {
                    String match = matches.first();

                    reader.getBuffer().write(
                            match.substring(partial.length()) + " "
                    );

                    lastTabPrefix[0] = null;
                    return true;
                }

                // Find the longest common prefix of all matches.
                String commonPrefix = matches.first();

                for (String match : matches) {
                    int commonLength = 0;
                    int limit = Math.min(
                            commonPrefix.length(),
                            match.length()
                    );

                    while (commonLength < limit
                            && commonPrefix.charAt(commonLength)
                                    == match.charAt(commonLength)) {
                        commonLength++;
                    }

                    commonPrefix = commonPrefix.substring(0, commonLength);
                }

                // Extend the command if a longer common prefix exists.
                if (commonPrefix.length() > partial.length()) {
                    reader.getBuffer().write(
                            commonPrefix.substring(partial.length())
                    );

                    lastTabPrefix[0] = null;
                    return true;
                }

                // Preserve the previous double-TAB listing behaviour
                // when no further common prefix is available.
                boolean secondTab = partial.equals(lastTabPrefix[0]);

                if (!secondTab) {
                    terminal.writer().write('\007');
                    terminal.writer().flush();
                    lastTabPrefix[0] = partial;
                    return true;
                }

                terminal.writer().println();
                terminal.writer().println(String.join("  ", matches));
                terminal.writer().print("$ " + partial);
                terminal.writer().flush();

                lastTabPrefix[0] = null;
                return true;
            });

            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference("path-complete"), "\t");

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

                if (command.equals("exit")) {
                    prepareErrorFile(errorFile, appendError);
                    break;
                } else if (command.equals("echo")) {
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
                } else if (command.equals("type")) {
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
                } else {
                    File executable = findExecutable(command);

                    if (executable == null) {
                        String error = command + ": command not found\n";
                        writeError(error, errorFile, appendError);
                        continue;
                    }

                    ProcessBuilder pb = new ProcessBuilder(commandTokens);

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
