
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

    private static List<String> invokeCompleter(
            String completerPath,
            String commandName,
            String currentWord,
            String previousWord,
            String compLine,
            int compPoint
    ) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    completerPath,
                    commandName,
                    currentWord,
                    previousWord
            );

            processBuilder.environment().put("COMP_LINE", compLine);
            processBuilder.environment().put(
                    "COMP_POINT",
                    String.valueOf(compPoint)
            );

            Process process = processBuilder
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            process.waitFor();

            TreeSet<String> sortedCandidates = new TreeSet<>();

            for (String line : output.split("\\R")) {
                String candidate = line.trim();

                if (!candidate.isEmpty()) {
                    sortedCandidates.add(candidate);
                }
            }

            return new ArrayList<>(sortedCandidates);

        } catch (Exception e) {
            return new ArrayList<>();
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

    private static Completer createShellCompleter() {
        return (reader, parsedLine, candidates) -> {
            String buffer = parsedLine.line();
            String currentWord = parsedLine.word();
            List<String> words = parsedLine.words();
            int wordIndex = parsedLine.wordIndex();

            // Complete command names when the cursor is in the first word.
            if (wordIndex == 0) {
                TreeSet<String> commandMatches = new TreeSet<>();

                for (String builtin : List.of(
                        "echo", "exit", "type", "complete"
                )) {
                    if (builtin.startsWith(currentWord)) {
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
                                    && name.startsWith(currentWord)) {
                                commandMatches.add(name);
                            }
                        }
                    }
                }

                for (String match : commandMatches) {
                    candidates.add(new Candidate(match));
                }

                return;
            }

            if (words.isEmpty()) {
                return;
            }

            String commandName = words.get(0);
            String completerPath = completionSpecs.get(commandName);

            // Programmable completion registered using `complete -C`.
            if (completerPath != null) {
                String previousWord = wordIndex > 0
                        ? words.get(wordIndex - 1)
                        : "";

                int cursor = Math.min(parsedLine.cursor(), buffer.length());
                int compPoint = buffer.substring(0, cursor)
                        .getBytes(StandardCharsets.UTF_8).length;

                List<String> scriptCandidates = invokeCompleter(
                        completerPath,
                        commandName,
                        currentWord,
                        previousWord,
                        buffer,
                        compPoint
                );

                for (String candidate : scriptCandidates) {
                    if (candidate.startsWith(currentWord)) {
                        candidates.add(new Candidate(candidate));
                    }
                }

                return;
            }

            // Filename and directory completion for command arguments.
            int lastSlash = currentWord.lastIndexOf('/');
            String directoryPath = lastSlash >= 0
                    ? currentWord.substring(0, lastSlash + 1)
                    : "";
            String filenamePrefix = lastSlash >= 0
                    ? currentWord.substring(lastSlash + 1)
                    : currentWord;

            File directory = directoryPath.isEmpty()
                    ? new File(".")
                    : new File(directoryPath);

            File[] entries = directory.listFiles();

            if (entries == null) {
                return;
            }

            for (File entry : entries) {
                if (!entry.getName().startsWith(filenamePrefix)) {
                    continue;
                }

                String completedPath = directoryPath + entry.getName();

                if (entry.isDirectory()) {
                    candidates.add(new Candidate(
                            completedPath,
                            completedPath,
                            null,
                            null,
                            "/",
                            null,
                            false
                    ));
                } else {
                    candidates.add(new Candidate(completedPath));
                }
            }
        };
    }

    public static void main(String[] args) throws Exception {
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build()) {

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new ShellParser())
                    .completer(createShellCompleter())
                    .option(LineReader.Option.AUTO_LIST, true)
                    .option(LineReader.Option.LIST_PACKED, true)
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
