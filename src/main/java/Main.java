
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            String command = scanner.nextLine();

            if (command.equals("exit")) {
                break;
            } else if (command.startsWith("echo ")) {
                System.out.println(command.substring(5));
            } else if (command.startsWith("type ")) {

                String arg = command.substring(5);

                if (arg.equals("echo")
                        || arg.equals("exit")
                        || arg.equals("type")) {

                    System.out.println(arg + " is a shell builtin");
                } else {
                    System.out.println(arg + ": not found");
                }

            } else {
                System.out.println(command + ": command not found");
            }
        }
    }
}
