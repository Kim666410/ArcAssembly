import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {
    static Path MotherPath;
    static Path CurrentPath;
    static String OSname;
    static boolean running = true;

    static final Map<String, List<String>> emitConfigMap = Map.of(
            "-d", List.of("bool", "debug"),
            "--name", List.of("string", "name")
    );

    // ใช้ Json.JsonObject แทน Map เดิม
    static Json.JsonObject MainConfig = new Json.JsonObject();

    static String input(String prompt) {
        System.out.print(prompt);
        Scanner scanner = new Scanner(System.in);
        return scanner.hasNextLine() ? scanner.nextLine() : "";
    }

    static List<String> split(String data, char target) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == target) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    // อ่าน config/config.json แล้ว parse เข้า MainConfig
    static void ReadConfig() throws IOException {
        Path configPath = MotherPath.resolve("config").resolve("config.json");
        if (!Files.exists(configPath)) {
            throw new IOException("Unable to read configuration file: " + configPath);
        }
        String raw = Files.readString(configPath);
        if (raw.isBlank()) {
            return; // ไฟล์ว่าง ใช้ค่าเริ่มต้น (object ว่าง)
        }
        Object parsed;
        try {
            parsed = Json.parse(raw);
        } catch (Json.JsonParseException e) {
            throw new IOException("Invalid JSON in " + configPath + ": " + e.getMessage());
        }
        if (!(parsed instanceof Json.JsonObject)) {
            throw new IOException("config.json must be a JSON object: " + configPath);
        }
        MainConfig = (Json.JsonObject) parsed;
    }

    // อาร์กิวเมนต์จาก command line จะเขียนทับค่าที่อ่านมาจาก config.json
    static List<String> emitConfig(String[] args) {
        List<String> arg = new ArrayList<>();
        for (String current : args) {
            List<String> parse = split(current, '=');
            if (parse.isEmpty()) {
                continue;
            }
            if (!emitConfigMap.containsKey(parse.get(0))) {
                arg.add(parse.get(0));
                continue;
            }
            String type = emitConfigMap.get(parse.get(0)).get(0);
            String key = emitConfigMap.get(parse.get(0)).get(1);
            if (parse.size() < 2) {
                if (!type.equals("bool")) {
                    System.out.println("[Error] When providing parameters that are not Boolean, the format must be <key>=<value>");
                    System.out.println("[Error] " + current);
                    System.exit(1);
                }
                MainConfig.put(key, true);
            } else {
                String val = parse.get(1);
                switch (type) {
                    case "bool" -> MainConfig.put(key, val.equals("true") || val.equals("1"));
                    case "int" -> MainConfig.put(key, Integer.parseInt(val));
                    case "char" -> {
                        if (!val.isEmpty()) {
                            MainConfig.put(key, String.valueOf(val.charAt(0)));
                        }
                    }
                    case "string" -> MainConfig.put(key, val);
                    default -> { }
                }
            }
        }
        return arg;
    }

    static String readFile(String path) throws IOException {
        return Files.readString(Paths.get(path));
    }

    static void mainExecute(List<String> args) throws IOException {
        ArcRuntime rt = new ArcRuntime();
        rt.debug = MainConfig.getBoolean("debug");
        for (String filePath:args) {
            Path TargetPath = Paths.get(filePath);
            if (!Files.exists(TargetPath)) {
                System.err.println("[Errpr] Path %s not found".formatted(TargetPath.toAbsolutePath().toString()));
                System.exit(1);
            }
            if (Files.isDirectory(TargetPath)) {
                System.err.println("[Error] Path %s is Directory".formatted(TargetPath.toAbsolutePath().toString()));
                System.exit(1);
            }
            rt.doString(Files.readString(TargetPath));
        }
    }

    public static void main(String[] args) {
        try {
            Path codePath = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            MotherPath = codePath.getParent() != null && codePath.getParent().getParent() != null
                    ? codePath.getParent()
                    : codePath;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            MotherPath = Paths.get("").toAbsolutePath();
        }
        CurrentPath = Paths.get("").toAbsolutePath();
        OSname = System.getProperty("os.name");

        try {
            ReadConfig();
            List<String> arg = emitConfig(args);
            if (MainConfig.optBoolean("debug", false)) {
                System.out.println("[Debug] Debug Mode");
                System.out.println("[Debug] Config: " + MainConfig.toString(2));
            }
            mainExecute(arg);
        } catch (Exception e) {
            System.err.println("[Error] " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }
}