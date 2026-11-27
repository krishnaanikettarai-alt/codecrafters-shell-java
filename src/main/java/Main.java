
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final Map<String, String> completionSpecs =
            new HashMap<>();

    private static class ShellParser extends DefaultParser {
        @Override
        public boolean isEscapeChar(char ch) {
            return false;
        }
    }

    private static boolean isBuiltin(String command) {
        return command.equals("echo")
                || command.equals("exit")
                || command.equals("type")
                || command.equals("complete");
    }

    private static File findExecutable(String command) {
        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        for (String path : pathEnv.split(File.pathSeparator)) {
            File file = new File(path, command);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file;
            }
        }

        return null;
    }

    private static String invokeCompleter(
            String completerPath,
            String commandName,
            String currentWord,
            String previousWord
    ) {
        try {
            Process process = new ProcessBuilder(
                    completerPath,
                    commandName,
                    currentWord,
                    previousWord
            )
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            process.waitFor();

            String candidate = output.lines()
                    .findFirst()
                    .orElse("")
                    .trim();

            return candidate.isEmpty() ? null : candidate;

        } catch (Exception e) {
            return null;
        }
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

        if (outputFile == null) {
            System.out.print(output);
            return;
        }

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
    }

    private static void prepareErrorFile(
            String errorFile,
            boolean appendError
    ) throws Exception {

        if (errorFile == null) {
            return;
        }

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

    private static void writeError(
            String error,
            String errorFile,
            boolean appendError
    ) throws Exception {

        if (errorFile == null) {
            System.err.print(error);
            return;
        }

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

                if (cursor != buffer.length()) {
                    return false;
                }

                // Invoke the registered completer with command context.
                int completerSpace = buffer.lastIndexOf(' ');

                if (completerSpace >= 0) {
                    String commandPart =
                            buffer.substring(0, completerSpace).trim();
                    String currentWord =
                            buffer.substring(completerSpace + 1);

                    List<String> enteredTokens =
                            parseCommand(commandPart);

                    if (!enteredTokens.isEmpty()) {
                        String enteredCommand = enteredTokens.get(0);
                        String completerPath =
                                completionSpecs.get(enteredCommand);

                        if (completerPath != null) {
                            String previousWord = "";

                            if (enteredTokens.size() > 1) {
                                previousWord = enteredTokens.get(
                                        enteredTokens.size() - 1
                                );
                            }

                            String candidate = invokeCompleter(
                                    completerPath,
                                    enteredCommand,
                                    currentWord,
                                    previousWord
                            );

                            if (candidate != null) {
                                reader.getBuffer().backspace(
                                        currentWord.length()
                                );
                                reader.getBuffer().write(candidate + " ");
                            } else {
                                // Empty output: ring bell and leave input
                                // unchanged. Do not fall back to file completion.
                                terminal.writer().write('\007');
                                terminal.writer().flush();
                            }

                            lastTabPrefix[0] = null;
                            return true;
                        }
                    }
                }

                // Filename and directory completion.
                int lastSpace = buffer.lastIndexOf(' ');

                if (lastSpace >= 0) {
                    String partialPath =
                            buffer.substring(lastSpace + 1);

                    int lastSlash = partialPath.lastIndexOf('/');

                    String directoryPath = lastSlash >= 0
                            ? partialPath.substring(0, lastSlash + 1)
                            : "";

                    String filenamePrefix = lastSlash >= 0
                            ? partialPath.substring(lastSlash + 1)
                            : partialPath;

                    File directory = directoryPath.isEmpty()
                            ? new File(".")
                            : new File(directoryPath);

                    File[] entries = directory.listFiles();
                    List<File> matches = new ArrayList<>();

                    if (entries != null) {
                        for (File entry : entries) {
                            if (entry.getName()
                                    .startsWith(filenamePrefix)) {
                                matches.add(entry);
                            }
                        }
                    }

                    matches.sort(
                            (a, b) -> a.getName().compareTo(b.getName())
                    );

                    if (matches.size() == 1) {
                        File match = matches.get(0);

                        String completedPath =
                                directoryPath + match.getName();

                        String suffix = match.isDirectory() ? "/" : " ";

                        reader.getBuffer().backspace(partialPath.length());
                        reader.getBuffer().write(completedPath + suffix);

                        lastTabPrefix[0] = null;
                        return true;
                    }

                    if (matches.size() > 1) {
                        String commonPrefix = matches.get(0).getName();

                        for (File match : matches) {
                            String name = match.getName();
                            int commonLength = 0;

                            int limit = Math.min(
                                    commonPrefix.length(),
                                    name.length()
                            );

                            while (commonLength < limit
                                    && commonPrefix.charAt(commonLength)
                                            == name.charAt(commonLength)) {
                                commonLength++;
                            }

                            commonPrefix =
                                    commonPrefix.substring(0, commonLength);
                        }

                        if (commonPrefix.length()
                                > filenamePrefix.length()) {

                            String completedPath =
                                    directoryPath + commonPrefix;

                            reader.getBuffer().backspace(partialPath.length());
                            reader.getBuffer().write(completedPath);

                            lastTabPrefix[0] = null;
                            return true;
                        }

                        boolean secondTab =
                                buffer.equals(lastTabPrefix[0]);

                        if (!secondTab) {
                            terminal.writer().write('\007');
                            terminal.writer().flush();

                            lastTabPrefix[0] = buffer;
                            return true;
                        }

                        TreeSet<String> displayMatches = new TreeSet<>();

                        for (File match : matches) {
                            String name = match.getName();

                            if (match.isDirectory()) {
                                name += "/";
                            }

                            displayMatches.add(name);
                        }

                        terminal.writer().println();
                        terminal.writer().println(
                                String.join("  ", displayMatches)
                        );
                        terminal.writer().print("$ " + buffer);
                        terminal.writer().flush();

                        lastTabPrefix[0] = null;
                        return true;
                    }

                    terminal.writer().write('\007');
                    terminal.writer().flush();

                    lastTabPrefix[0] = null;
                    return true;
                }

                // Command completion.
                String partial = buffer;
                TreeSet<String> commandMatches = new TreeSet<>();

                for (String builtin : List.of(
                        "echo", "exit", "type", "complete"
                )) {
                    if (builtin.startsWith(partial)) {
                        commandMatches.add(builtin);
                    }
                }

                String pathEnv = System.getenv("PATH");

                if (pathEnv != null) {
                    for (String path : pathEnv.split(
                            java.util.regex.Pattern.quote(
                                    File.pathSeparator
                            ))) {

                        File directory = new File(path);

                        if (!directory.isDirectory()
                                || !directory.canRead()) {
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
                                commandMatches.add(name);
                            }
                        }
                    }
                }

                if (commandMatches.isEmpty()) {
                    terminal.writer().write('\007');
                    terminal.writer().flush();

                    lastTabPrefix[0] = null;
                    return true;
                }

                if (commandMatches.size() == 1) {
                    String match = commandMatches.first();

                    reader.getBuffer().write(
                            match.substring(partial.length()) + " "
                    );

                    lastTabPrefix[0] = null;
                    return true;
                }

                String commonPrefix = commandMatches.first();

                for (String match : commandMatches) {
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

                    commonPrefix =
                            commonPrefix.substring(0, commonLength);
                }

                if (commonPrefix.length() > partial.length()) {
                    reader.getBuffer().write(
                            commonPrefix.substring(partial.length())
                    );

                    lastTabPrefix[0] = null;
                    return true;
                }

                boolean secondTab =
                        partial.equals(lastTabPrefix[0]);

                if (!secondTab) {
                    terminal.writer().write('\007');
                    terminal.writer().flush();

                    lastTabPrefix[0] = partial;
                    return true;
                }

                terminal.writer().println();
                terminal.writer().println(
                        String.join("  ", commandMatches)
                );
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

                            if (token.equals("2>")
                                    || token.equals("2>>")) {

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
                                    + executable.getAbsolutePath()
                                    + "\n";
                        } else {
                            output = cmd + ": not found\n";
                        }
                    }

                    outputResult(
                            output,
                            outputFile,
                            appendOutput
                    );

                } else if (command.equals("complete")) {
                    if (commandTokens.size() >= 4
                            && commandTokens.get(1).equals("-C")) {

                        String completerPath = commandTokens.get(2);
                        String completionCommand = commandTokens.get(3);

                        completionSpecs.put(
                                completionCommand,
                                completerPath
                        );

                        prepareErrorFile(errorFile, appendError);

                    } else if (commandTokens.size() >= 3
                            && commandTokens.get(1).equals("-p")) {

                        String completionCommand = commandTokens.get(2);

                        String completerPath =
                                completionSpecs.get(completionCommand);

                        if (completerPath == null) {
                            String error = "complete: "
                                    + completionCommand
                                    + ": no completion specification\n";

                            writeError(error, errorFile, appendError);
                        } else {
                            prepareErrorFile(errorFile, appendError);

                            String output = "complete -C '"
                                    + completerPath
                                    + "' "
                                    + completionCommand
                                    + "\n";

                            outputResult(
                                    output,
                                    outputFile,
                                    appendOutput
                            );
                        }
                    } else {
                        prepareErrorFile(errorFile, appendError);
                    }

                } else {
                    File executable = findExecutable(command);

                    if (executable == null) {
                        String error =
                                command + ": command not found\n";

                        writeError(error, errorFile, appendError);
                        continue;
                    }

                    ProcessBuilder pb =
                            new ProcessBuilder(commandTokens);

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
                        pb.redirectOutput(
                                ProcessBuilder.Redirect.INHERIT
                        );
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
                        pb.redirectError(
                                ProcessBuilder.Redirect.INHERIT
                        );
                    }

                    pb.redirectInput(
                            ProcessBuilder.Redirect.INHERIT
                    );

                    Process process = pb.start();
                    process.waitFor();
                }
            }
        }
    }
}
