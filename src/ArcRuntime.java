import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class ArcRuntime {

    // =========================================================================
    // Runtime State
    // =========================================================================
    private Lexer lexer;
    public boolean debug = false;

    private HashMap<String, Integer> Label2PC = new HashMap<>();
    private ArrayList<ArrayList<String>> Program = new ArrayList<>();
    private HashMap<String, Object> Data = new HashMap<>();      // This is RAM!
    private HashMap<String, String> DataType = new HashMap<>();  // RAM type
    private int pc = 0;
    private String StartLabel = "_start";
    private String SectionState = "start";
    private ArrayList<String> SectionList =
        new ArrayList<>(Arrays.asList("bss", "data", "text"));

    private static final String[] REGISTER_NAMES = {
        "r0", "ra", "sp", "gp",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9", "t10", "t11",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7"
    };
    private HashMap<String, Object> _register = new HashMap<>();

    // Stack หลัก (push / pop)
    private Stack<Object> GlobalStack = new Stack<>();
    // Stack แยกตามชื่อ (lpush / lpop) แต่ละชื่อ = 1 stack
    private HashMap<String, Stack<Object>> LabelStack = new HashMap<>();
    // Stack เก็บ return address (call / ret)
    private Stack<Integer> CallStack = new Stack<>();
    private static final int MAX_CALL_DEPTH = 100000;

    // ผลของ cmp : <0 น้อยกว่า, 0 เท่ากัน, >0 มากกว่า
    private int flag = 0;

    // =========================================================================
    // Private Functions
    // =========================================================================
    private ArrayList<String> CurrentLine() {
        if (pc >= Program.size()) {
            return new ArrayList<>(Arrays.asList("EOF"));
        }

        return Program.get(pc);
    }

    private void Next() {
        pc++;
    }

    private void Next(int k) {
        pc += k;
    }

    private void Error(String name, String content) {
        System.err.println(
            "[%s] %s InLine %s".formatted(name, content, pc)
        );

        System.exit(1);
    }

    private int getLabelPC(String label) {
        return Label2PC.getOrDefault(label, -1);
    }

    private boolean isQuoted(String value) {
        return value.length() >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) ||
             (value.startsWith("'") && value.endsWith("'")));
    }

    private String unescape(String value) {
        return value
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\");
    }

    private String parseString(String value) {
        return unescape(value.substring(1, value.length() - 1));
    }

    private void resetState() {
        Program.clear();
        Label2PC.clear();
        Data.clear();
        DataType.clear();
        GlobalStack.clear();
        LabelStack.clear();
        CallStack.clear();

        for (String key : REGISTER_NAMES) {
            _register.put(key, 0);
        }

        flag = 0;
        pc = 0;
        StartLabel = "_start";
        SectionState = "start";
    }

    private void halt() {
        pc = Program.size();
    }

    private void needArgs(String cmd, ArrayList<String> args, int n) {
        if (args.size() != n) {
            Error("SyntaxError",
                cmd + " requires " + n + " operand(s), got " + args.size());
        }
    }


    // =========================================================================
    // Tokenizer  (ใช้ , คั่น operand ได้ เช่น  mov t0, 5)
    // =========================================================================

    private ArrayList<String> tokenize(String line) {
        ArrayList<String> tokens = new ArrayList<>();

        StringBuilder token = new StringBuilder();

        boolean inString = false;
        boolean inChar = false;
        boolean escape = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (!inString && !inChar && c == ';') {
                break;
            }

            if (escape) {
                token.append(c);
                escape = false;
                continue;
            }

            if ((inString || inChar) && c == '\\') {
                token.append(c);
                escape = true;
                continue;
            }

            if (c == '"' && !inChar) {
                inString = !inString;
                token.append(c);
                continue;
            }

            if (c == '\'' && !inString) {
                inChar = !inChar;
                token.append(c);
                continue;
            }

            // whitespace และ ',' เป็นตัวคั่น (นอก string / char)
            if ((Character.isWhitespace(c) || c == ',') && !inString && !inChar) {
                if (!token.isEmpty()) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }

            token.append(c);
        }

        if (inString || inChar) {
            Error("TokenizerError", "Unclosed string or character");
        }

        if (!token.isEmpty()) {
            tokens.add(token.toString());
        }

        return tokens;
    }


    // =========================================================================
    // Data Type Functions
    // =========================================================================

    private boolean isDataType(String type) {
        return type.equals("string") || type.equals("char") ||
               type.equals("int") || type.equals("float") ||
               type.equals("double");
    }

    private Object parseData(String type, String value) {
        try {
            switch (type) {

                case "string": {
                    if (!value.startsWith("\"") || !value.endsWith("\"")) {
                        Error("TypeError", "Invalid string value " + value);
                    }

                    return parseString(value);
                }

                case "char": {
                    if (!value.startsWith("'") || !value.endsWith("'")) {
                        Error("TypeError", "Invalid char value " + value);
                    }

                    String parsed = parseString(value);

                    if (parsed.length() != 1) {
                        Error("TypeError", "Char must contain exactly one character");
                    }

                    return parsed.charAt(0);
                }

                case "int": {
                    return Integer.parseInt(value);
                }

                case "float": {
                    if (value.endsWith("f") || value.endsWith("F")) {
                        value = value.substring(0, value.length() - 1);
                    }

                    return Float.parseFloat(value);
                }

                case "double": {
                    return Double.parseDouble(value);
                }

                default: {
                    Error("TypeError", "Unknown data type " + type);
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            Error("TypeError", "Invalid " + type + " value");
            return null;
        }
    }

    private void defineData(String name, String type, String value) {

        if (Data.containsKey(name)) {
            Error("DataError", "Variable already exists: " + name);
        }

        Object parsed = parseData(type, value);

        Data.put(name, parsed);
        DataType.put(name, type);

        if (debug) {
            System.out.println(
                "[Debug] Data " + name + " : " + type + " = " + parsed
            );
        }
    }

    private Object getData(String name) {

        if (!Data.containsKey(name)) {
            Error("DataError", "Variable not found: " + name);
        }

        return Data.get(name);
    }

    private Object defaultValue(String type) {
        switch (type) {
            case "int":    return 0;
            case "float":  return 0f;
            case "double": return 0.0;
            case "char":   return '\0';
            case "string": return "";
            default:
                Error("TypeError", "Unknown data type " + type);
                return null;
        }
    }


    // =========================================================================
    // Number / Operand Helpers
    // =========================================================================

    private boolean isFloating(Object o) {
        return o instanceof Double || o instanceof Float;
    }

    private double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Character) return (double) ((Character) o).charValue();
        Error("TypeError", "Value is not a number: " + o);
        return 0;
    }

    private int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof Character) return (int) ((Character) o).charValue();
        Error("TypeError", "Value is not a number: " + o);
        return 0;
    }

    // แปลงค่าให้ตรงกับ type ของตัวแปรใน RAM
    private Object coerce(String type, Object v) {
        switch (type) {
            case "int":
                return toInt(v);
            case "float":
                return (float) toDouble(v);
            case "double":
                return toDouble(v);
            case "char":
                if (v instanceof Character) return v;
                if (v instanceof Number) return (char) ((Number) v).intValue();
                Error("TypeError", "Cannot convert to char: " + v);
                return null;
            case "string":
                return String.valueOf(v);
            default:
                Error("TypeError", "Unknown data type " + type);
                return null;
        }
    }

    // literal: ตัวเลข (10, -5, 0xFF, 0b101, 3.14), "string", 'c'
    private Object parseLiteral(String t) {
        if (t.isEmpty()) return null;

        if (isQuoted(t)) {
            if (t.startsWith("\"")) {
                return parseString(t);
            }

            String s = parseString(t);

            if (s.length() != 1) {
                Error("TypeError", "Char must contain exactly one character");
                return null;
            }

            return s.charAt(0);
        }

        char c = t.charAt(0);

        if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.')) {
            return null;
        }

        try {
            String s = t;
            boolean neg = false;

            if (s.startsWith("-")) {
                neg = true;
                s = s.substring(1);
            } else if (s.startsWith("+")) {
                s = s.substring(1);
            }

            if (s.startsWith("0x") || s.startsWith("0X")) {
                int v = Integer.parseInt(s.substring(2), 16);
                return neg ? -v : v;
            }

            if (s.startsWith("0b") || s.startsWith("0B")) {
                int v = Integer.parseInt(s.substring(2), 2);
                return neg ? -v : v;
            }

            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return Double.parseDouble(t);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isRegister(String t) {
        return _register.containsKey(t);
    }

    // [name] = เข้าถึง RAM ตรง ๆ, [t0] = t0 เก็บชื่อตัวแปร (pointer)
    private boolean isMem(String t) {
        return t.length() > 2 && t.startsWith("[") && t.endsWith("]");
    }

    private String memName(String t) {
        String inner = t.substring(1, t.length() - 1).trim();

        if (isRegister(inner)) {
            return String.valueOf(_register.get(inner));
        }

        return inner;
    }

    // อ่านค่าจาก operand: register / RAM / [mem] / literal
    private Object resolve(String t) {

        if (isMem(t)) {
            return getData(memName(t));
        }

        if (isRegister(t)) {
            return _register.get(t);
        }

        if (Data.containsKey(t)) {
            return Data.get(t);
        }

        Object lit = parseLiteral(t);

        if (lit == null) {
            Error("DataError", "Unknown variable, register or value: " + t);
        }

        return lit;
    }

    // เขียนค่าลง operand: register / RAM / [mem]
    private void writeTo(String dst, Object value) {

        if (isMem(dst)) {
            String name = memName(dst);

            if (!Data.containsKey(name)) {
                Error("DataError", "Variable not found: " + name);
            }

            Data.put(name, coerce(DataType.get(name), value));
            return;
        }

        if (isRegister(dst)) {
            _register.put(dst, value);

            if (dst.equals("sp")) {
                Error("RegisterError", "sp is read-only (it mirrors the stack size)");
            }

            return;
        }

        if (Data.containsKey(dst)) {
            Data.put(dst, coerce(DataType.get(dst), value));
            return;
        }

        Error("DataError", "Unknown destination: " + dst);
    }

    private Object arith(String op, Object a, Object b) {

        // add กับ string = ต่อ string
        if (op.equals("add") && (a instanceof String || b instanceof String)) {
            return String.valueOf(a) + String.valueOf(b);
        }

        if (isFloating(a) || isFloating(b)) {
            double x = toDouble(a);
            double y = toDouble(b);

            switch (op) {
                case "add": return x + y;
                case "sub": return x - y;
                case "mul": return x * y;
                case "div":
                    if (y == 0) Error("MathError", "Division by zero");
                    return x / y;
                case "mod":
                    if (y == 0) Error("MathError", "Modulo by zero");
                    return x % y;
            }
        } else {
            int x = toInt(a);
            int y = toInt(b);

            switch (op) {
                case "add": return x + y;
                case "sub": return x - y;
                case "mul": return x * y;
                case "div":
                    if (y == 0) Error("MathError", "Division by zero");
                    return x / y;
                case "mod":
                    if (y == 0) Error("MathError", "Modulo by zero");
                    return x % y;
            }
        }

        Error("SyntaxError", "Unknown arithmetic op " + op);
        return null;
    }


    // =========================================================================
    // Parser / Loader
    // =========================================================================

    public void loadProgram(String Code) {

        resetState();

        String[] lines = Code.split("\\r?\\n");

        for (String line : lines) {

            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            ArrayList<String> tokens = tokenize(line);

            if (!tokens.isEmpty()) {
                Program.add(tokens);
            }
        }

        if (debug) {
            for (int i = 0; i < Program.size(); i++) {
                System.out.println("PC " + i + ": " + Program.get(i));
            }
        }
    }

    public void scanLabel() {

        for (int i = 0; i < Program.size(); i++) {

            ArrayList<String> line = Program.get(i);

            if (line.isEmpty()) {
                continue;
            }

            String firstToken = line.get(0);

            if (firstToken.endsWith(":")) {

                String labelName = firstToken.substring(
                    0, firstToken.length() - 1
                );

                if (Label2PC.containsKey(labelName)) {
                    Error("LabelError", "Duplicate label " + labelName);
                }

                Label2PC.put(labelName, i);

                if (debug) {
                    System.out.println(
                        "[Debug] Found Label '" + labelName +
                        "' at PC: " + i
                    );
                }
            }
        }
    }


    // =========================================================================
    // Statement Handlers : Directives
    // =========================================================================

    private void handleGlobal(ArrayList<String> args) {

        if (args.isEmpty()) {
            Error("SyntaxError", "global requires a label");
        }

        StartLabel = args.get(0);

        Next();
    }

    private void handleSection(ArrayList<String> args) {

        if (args.isEmpty()) {
            Error("SyntaxError", "section requires a name");
        }

        String sectionMode = args.get(0);

        if (!SectionList.contains(sectionMode)) {
            Error("SectionError", sectionMode + " not found");
        }

        SectionState = sectionMode;

        if (sectionMode.equals("text")) {

            int tempPC = getLabelPC(StartLabel);

            if (tempPC == -1) {
                Error(
                    "LabelError",
                    "Not global " + StartLabel + " in Label2PC"
                );
            }

            pc = tempPC;
            return;
        }

        Next();
    }

    private void handleData(ArrayList<String> args, String type, String name) {
        if (!SectionState.equals("data")) {
            Error("SectionError", type + " requires section data");
        }

        if (args.size() != 1) {
            Error("SyntaxError", type + " requires a value");
        }

        String value = args.get(0);

        defineData(name, type, value);

        Next();
    }


    // =========================================================================
    // Statement Handlers : Output
    // =========================================================================

    private void handlePuts(ArrayList<String> args) {

        if (args.isEmpty()) {
            Error("SyntaxError", "puts requires an argument");
        }

        StringBuilder output = new StringBuilder();

        for (String arg : args) {
            output.append(resolve(arg));
        }

        System.out.println(output);
        Next();
    }


    // =========================================================================
    // Statement Handlers : Load / Move
    // =========================================================================

    // lim dst, literal   (load immediate)
    private void handleLim(ArrayList<String> args) {
        needArgs("lim", args, 2);

        Object lit = parseLiteral(args.get(1));

        if (lit == null) {
            Error("SyntaxError", "lim requires a literal value, got " + args.get(1));
        }

        writeTo(args.get(0), lit);
        Next();
    }

    // mov dst, src   (ld / st เป็นชื่อเรียกอีกแบบของ mov)
    private void handleMov(ArrayList<String> args) {
        needArgs("mov", args, 2);

        writeTo(args.get(0), resolve(args.get(1)));
        Next();
    }

    // lea dst, name   (เอา "address" ของตัวแปรใน RAM = ชื่อ ไปเก็บใน dst)
    private void handleLea(ArrayList<String> args) {
        needArgs("lea", args, 2);

        String name = args.get(1);

        if (!Data.containsKey(name)) {
            Error("DataError", "Variable not found: " + name);
        }

        writeTo(args.get(0), name);
        Next();
    }


    // =========================================================================
    // Statement Handlers : Arithmetic
    // =========================================================================

    // add dst, a, b   ->  dst = a + b
    // add dst, a      ->  dst = dst + a
    private void handleArith(String op, ArrayList<String> args) {

        if (args.size() != 2 && args.size() != 3) {
            Error("SyntaxError", op + " requires 2 or 3 operands");
        }

        String dst = args.get(0);
        Object a;
        Object b;

        if (args.size() == 2) {
            a = resolve(dst);
            b = resolve(args.get(1));
        } else {
            a = resolve(args.get(1));
            b = resolve(args.get(2));
        }

        writeTo(dst, arith(op, a, b));
        Next();
    }

    private void handleIncDec(String cmd, ArrayList<String> args) {
        needArgs(cmd, args, 1);

        String target = args.get(0);
        String op = cmd.equals("inc") ? "add" : "sub";

        writeTo(target, arith(op, resolve(target), 1));
        Next();
    }


    // =========================================================================
    // Statement Handlers : Compare / Jump
    // =========================================================================

    private void handleCmp(ArrayList<String> args) {
        needArgs("cmp", args, 2);

        Object a = resolve(args.get(0));
        Object b = resolve(args.get(1));
        int result;

        if (a instanceof String || b instanceof String) {
            result = String.valueOf(a).compareTo(String.valueOf(b));
        } else if (isFloating(a) || isFloating(b)) {
            result = Double.compare(toDouble(a), toDouble(b));
        } else {
            result = Integer.compare(toInt(a), toInt(b));
        }

        flag = Integer.signum(result);
        Next();
    }

    private void jumpTo(String label) {
        int target = getLabelPC(label);

        if (target == -1) {
            Error("LabelError", "Label not found: " + label);
        }

        pc = target;
    }

    private void handleJump(String cmd, ArrayList<String> args) {
        needArgs(cmd, args, 1);

        boolean take;

        switch (cmd) {
            case "jump":
            case "jmp": take = true;      break;
            case "je":
            case "jz":  take = flag == 0; break;
            case "jne":
            case "jnz": take = flag != 0; break;
            case "jl":  take = flag < 0;  break;
            case "jle": take = flag <= 0; break;
            case "jg":  take = flag > 0;  break;
            case "jge": take = flag >= 0; break;
            default:    take = false;     break;
        }

        if (take) {
            jumpTo(args.get(0));
        } else {
            Next();
        }
    }

    private void handleCall(ArrayList<String> args) {
        needArgs("call", args, 1);

        if (CallStack.size() >= MAX_CALL_DEPTH) {
            Error("StackError", "Call stack overflow");
        }

        CallStack.push(pc + 1);
        jumpTo(args.get(0));
    }

    private void handleRet(ArrayList<String> args) {
        needArgs("ret", args, 0);

        // ret ตอนไม่มีอะไรให้กลับ = จบโปรแกรม (เหมือน return จาก _start)
        if (CallStack.isEmpty()) {
            halt();
            return;
        }

        pc = CallStack.pop();
    }


    // =========================================================================
    // Statement Handlers : Stack
    // =========================================================================

    private void syncSp() {
        _register.put("sp", GlobalStack.size());
    }

    private void handlePush(ArrayList<String> args) {
        needArgs("push", args, 1);

        GlobalStack.push(resolve(args.get(0)));
        syncSp();
        Next();
    }

    private void handlePop(ArrayList<String> args) {
        needArgs("pop", args, 1);

        if (GlobalStack.isEmpty()) {
            Error("StackError", "Stack underflow");
        }

        Object v = GlobalStack.pop();
        syncSp();
        writeTo(args.get(0), v);
        Next();
    }

    private void handlePeek(ArrayList<String> args) {
        needArgs("peek", args, 1);

        if (GlobalStack.isEmpty()) {
            Error("StackError", "Stack is empty");
        }

        writeTo(args.get(0), GlobalStack.peek());
        Next();
    }

    private void handleDup(ArrayList<String> args) {
        needArgs("dup", args, 0);

        if (GlobalStack.isEmpty()) {
            Error("StackError", "Stack is empty");
        }

        GlobalStack.push(GlobalStack.peek());
        syncSp();
        Next();
    }

    private void handleSwap(ArrayList<String> args) {
        needArgs("swap", args, 0);

        if (GlobalStack.size() < 2) {
            Error("StackError", "swap requires at least 2 values");
        }

        Object a = GlobalStack.pop();
        Object b = GlobalStack.pop();
        GlobalStack.push(a);
        GlobalStack.push(b);
        Next();
    }


    // =========================================================================
    // Statement Handlers : LabelStack (stack แยกตามชื่อ)
    // =========================================================================

    private Stack<Object> getLabelStack(String name, boolean create) {
        Stack<Object> s = LabelStack.get(name);

        if (s == null) {
            if (create) {
                s = new Stack<>();
                LabelStack.put(name, s);
            } else {
                Error("StackError", "Label stack not found: " + name);
            }
        }

        return s;
    }

    // lpush name, src
    private void handleLPush(ArrayList<String> args) {
        needArgs("lpush", args, 2);

        getLabelStack(args.get(0), true).push(resolve(args.get(1)));
        Next();
    }

    // lpop dst, name
    private void handleLPop(ArrayList<String> args) {
        needArgs("lpop", args, 2);

        Stack<Object> s = getLabelStack(args.get(1), false);

        if (s.isEmpty()) {
            Error("StackError", "Label stack underflow: " + args.get(1));
        }

        writeTo(args.get(0), s.pop());
        Next();
    }

    // lpeek dst, name
    private void handleLPeek(ArrayList<String> args) {
        needArgs("lpeek", args, 2);

        Stack<Object> s = getLabelStack(args.get(1), false);

        if (s.isEmpty()) {
            Error("StackError", "Label stack is empty: " + args.get(1));
        }

        writeTo(args.get(0), s.peek());
        Next();
    }

    // lsize dst, name
    private void handleLSize(ArrayList<String> args) {
        needArgs("lsize", args, 2);

        Stack<Object> s = LabelStack.get(args.get(1));

        writeTo(args.get(0), s == null ? 0 : s.size());
        Next();
    }

    // lclear name
    private void handleLClear(ArrayList<String> args) {
        needArgs("lclear", args, 1);

        Stack<Object> s = LabelStack.get(args.get(0));

        if (s != null) {
            s.clear();
        }

        Next();
    }


    // =========================================================================
    // Statement Handlers : RAM
    // =========================================================================

    // alloc name type [value]
    private void handleAlloc(ArrayList<String> args) {

        if (args.size() != 2 && args.size() != 3) {
            Error("SyntaxError", "alloc requires: name type [value]");
        }

        String name = args.get(0);
        String type = args.get(1);

        if (!isDataType(type)) {
            Error("TypeError", "Unknown data type " + type);
        }

        if (isRegister(name) || isMem(name)) {
            Error("DataError", "Invalid variable name: " + name);
        }

        if (Data.containsKey(name)) {
            Error("DataError", "Variable already exists: " + name);
        }

        Object value = args.size() == 3
            ? coerce(type, resolve(args.get(2)))
            : defaultValue(type);

        Data.put(name, value);
        DataType.put(name, type);
        Next();
    }

    // free name
    private void handleFree(ArrayList<String> args) {
        needArgs("free", args, 1);

        String name = args.get(0);

        if (!Data.containsKey(name)) {
            Error("DataError", "Variable not found: " + name);
        }

        Data.remove(name);
        DataType.remove(name);
        Next();
    }

    // dump  (debug: แสดง register / RAM / stack ทั้งหมด)
    private void handleDump(ArrayList<String> args) {
        needArgs("dump", args, 0);

        System.out.println("---------------- DUMP ----------------");
        System.out.println("PC=" + pc + "  FLAG=" + flag);

        System.out.println("[Registers] (non-zero)");
        for (String key : REGISTER_NAMES) {
            Object v = _register.get(key);

            if (v instanceof Integer && ((Integer) v) == 0) {
                continue;
            }

            System.out.println("  " + key + " = " + v);
        }

        System.out.println("[RAM]");
        for (Map.Entry<String, Object> e : Data.entrySet()) {
            System.out.println(
                "  " + e.getKey() + " : " + DataType.get(e.getKey()) +
                " = " + e.getValue()
            );
        }

        System.out.println("[Stack] " + GlobalStack);

        for (Map.Entry<String, Stack<Object>> e : LabelStack.entrySet()) {
            System.out.println("[LabelStack " + e.getKey() + "] " + e.getValue());
        }

        System.out.println("[CallStack] " + CallStack);
        System.out.println("--------------------------------------");

        Next();
    }


    // =========================================================================
    // Statement Functions
    // =========================================================================
    private void Statement() {

        // copy ไว้ก่อน เพราะต้อง remove label/command ออก
        // (ถ้าแก้ Program ตรง ๆ โปรแกรมที่วน loop / call ซ้ำจะพัง)
        ArrayList<String> Line = new ArrayList<>(CurrentLine());

        if (Line.isEmpty()) {
            Next();
            return;
        }

        String Label = null;

        // -------------------------------------------------------------
        // Label Handling
        // -------------------------------------------------------------

        if (Line.get(0).endsWith(":")) {
            Label = Line.get(0).substring(
                0,
                Line.get(0).length() - 1
            );

            Line.remove(0);

            if (Line.isEmpty()) {
                Next();
                return;
            }
        }

        String Command = Line.get(0).trim().toLowerCase();
        Line.remove(0);

        // คำสั่งที่ไม่ใช่ directive / data ต้องอยู่ใน section text
        boolean directive =
            Command.equals("pass") || Command.equals("global") ||
            Command.equals("section") || isDataType(Command);

        if (!directive && !SectionState.equals("text")) {
            Error("SectionError", Command + " requires section text");
        }

        switch (Command) {
            case "pass": {
                Next();
                break;
            }

            case "global": {
                handleGlobal(Line);
                break;
            }

            case "section": {
                handleSection(Line);
                break;
            }

            // ---------------------------------------------------------
            // Data Types
            // ---------------------------------------------------------
            case "string":
            case "char":
            case "int":
            case "float":
            case "double": {

                if (Label == null) {
                    Error("SyntaxError", Command + " requires a label");
                }

                handleData(Line, Command, Label);
                break;
            }

            // ---------------------------------------------------------
            // Output
            // ---------------------------------------------------------
            case "puts": {
                handlePuts(Line);
                break;
            }

            // ---------------------------------------------------------
            // Load / Move
            // ---------------------------------------------------------
            case "lim": {
                handleLim(Line);
                break;
            }

            case "mov":
            case "ld":
            case "st": {
                handleMov(Line);
                break;
            }

            case "lea": {
                handleLea(Line);
                break;
            }

            // ---------------------------------------------------------
            // Arithmetic
            // ---------------------------------------------------------
            case "add":
            case "sub":
            case "mul":
            case "div":
            case "mod": {
                handleArith(Command, Line);
                break;
            }

            case "inc":
            case "dec": {
                handleIncDec(Command, Line);
                break;
            }

            // ---------------------------------------------------------
            // Compare / Jump / Call
            // ---------------------------------------------------------
            case "cmp": {
                handleCmp(Line);
                break;
            }

            case "jump":
            case "jmp":
            case "je":
            case "jz":
            case "jne":
            case "jnz":
            case "jl":
            case "jle":
            case "jg":
            case "jge": {
                handleJump(Command, Line);
                break;
            }

            case "call": {
                handleCall(Line);
                break;
            }

            case "ret": {
                handleRet(Line);
                break;
            }

            // ---------------------------------------------------------
            // Stack
            // ---------------------------------------------------------
            case "push": {
                handlePush(Line);
                break;
            }

            case "pop": {
                handlePop(Line);
                break;
            }

            case "peek": {
                handlePeek(Line);
                break;
            }

            case "dup": {
                handleDup(Line);
                break;
            }

            case "swap": {
                handleSwap(Line);
                break;
            }

            // ---------------------------------------------------------
            // LabelStack
            // ---------------------------------------------------------
            case "lpush": {
                handleLPush(Line);
                break;
            }

            case "lpop": {
                handleLPop(Line);
                break;
            }

            case "lpeek": {
                handleLPeek(Line);
                break;
            }

            case "lsize": {
                handleLSize(Line);
                break;
            }

            case "lclear": {
                handleLClear(Line);
                break;
            }

            // ---------------------------------------------------------
            // RAM
            // ---------------------------------------------------------
            case "alloc": {
                handleAlloc(Line);
                break;
            }

            case "free": {
                handleFree(Line);
                break;
            }

            case "dump": {
                handleDump(Line);
                break;
            }

            // ---------------------------------------------------------
            // Control
            // ---------------------------------------------------------
            case "halt":
            case "exit": {
                halt();
                break;
            }

            default: {
                Error(
                    "SyntaxError",
                    "Commands not found " + Command
                );
                break;
            }
        }
    }

    // =========================================================================
    // Execution
    // =========================================================================

    public void run() {
        while (!CurrentLine().get(0).equals("EOF")) {
            Statement();
        }
    }

    public void doString(String Code) {
        loadProgram(Code);
        scanLabel();
        run();
    }


    // =========================================================================
    // Constructor
    // =========================================================================

    ArcRuntime() {
        GrammarState.loadDefaultRule();
        lexer = new Lexer("", 4);
        resetState();
    }
}