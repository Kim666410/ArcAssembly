// ─────────────────────────────────────────────────────────────────────────
//  Mono_j11.java  —  Java port of Mono_py11.py (lexer + JSON-grammar normalizer)
//
//  Ported from the original Python implementation. Three real bugs found in
//  the Python source were fixed here (and should be fixed there too):
//
//   BUG 1 (rule backtracking): in the "token type match" branch of the rule
//   walker, when every alternative for a matched token fails, the original
//   code did `self.pos -= 1`, assuming pos was still exactly one past the
//   consumed token. A failed nested alternative can leave pos further along
//   without rolling back, so a blind -1 can over/under-backtrack. Fixed by
//   restoring to `savedPos - 1` explicitly (see Normalizer.walkRule).
//
//   BUG 2 (evalTokenSeq hardcoded bracket types): the function received
//   openType/closeType parameters but compared literal "LBRACKET"/"LBRACE"
//   strings instead of the parameters, so custom bracket token names never
//   recursed as nested list/map. Fixed to compare against the parameters.
//
//   BUG 3 (infinite loop on "warn"): when a rule matched its trigger token
//   but produced no result, pos was reset to the same token; with the
//   "rule_not_matched" policy set to "warn" the loop never advanced. Fixed
//   by consuming the token to force progress (see Normalizer.parse).
//
//  DEFAULT RULE: every other Mono port ships a built-in default grammar that
//  is used when no rule file is given (or for scripting-language use). The
//  Java port now does too — see class DefaultRule at the bottom of this file.
//  The rule.json argument is therefore optional. When used as a library,
//  `new Lexer(...)` / `new Normalizer(tokens)` load the default rule
//  automatically if GrammarState.loadRule(...) was not called first.
//
//  No external dependencies: includes a small hand-written JSON parser/
//  writer so the whole thing builds with just a JDK (15+ for the text block
//  used by DefaultRule).
//
//  Build:  javac Mono_j11.java
//  Run:    java Mono_j11 <source_file> [rule.json]
// ─────────────────────────────────────────────────────────────────────────

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

// ═════════════════════════════════════════════════════════════════════════
//  MINIMAL JSON  (Object graph: null, Boolean, Long, Double, String,
//  List<Object>, LinkedHashMap<String,Object> — insertion order preserved,
//  matching Python dict semantics used throughout the original parser)
// ═════════════════════════════════════════════════════════════════════════

final class MonoJson {
    private final String s;
    private int i = 0;

    private MonoJson(String s) { this.s = s; }

    static Object parse(String text) {
        MonoJson p = new MonoJson(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        return v;
    }

    @SuppressWarnings("unchecked")
    static Object get(Object obj, String key, Object dflt) {
        if (obj instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) obj;
            return m.containsKey(key) ? m.get(key) : dflt;
        }
        return dflt;
    }

    static boolean has(Object obj, String key) {
        return (obj instanceof Map) && ((Map<?, ?>) obj).containsKey(key);
    }

    static Map<String, Object> obj() { return new LinkedHashMap<>(); }
    static List<Object> arr() { return new ArrayList<>(); }

    static String asString(Object o, String dflt) { return o instanceof String ? (String) o : dflt; }
    static boolean asBool(Object o, boolean dflt) { return o instanceof Boolean ? (Boolean) o : dflt; }
    static long asLong(Object o) { return ((Number) o).longValue(); }
    static double asDouble(Object o) { return ((Number) o).doubleValue(); }

    static String dump(Object o) {
        StringBuilder sb = new StringBuilder();
        dumpTo(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void dumpTo(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String) { sb.append('"').append(escape((String) o)).append('"'); return; }
        if (o instanceof Boolean || o instanceof Long || o instanceof Integer) { sb.append(o); return; }
        if (o instanceof Double) {
            double d = (Double) o;
            if (d == Math.floor(d) && !Double.isInfinite(d)) sb.append((long) d).append(".0");
            else sb.append(d);
            return;
        }
        if (o instanceof List) {
            sb.append('[');
            List<Object> l = (List<Object>) o;
            for (int k = 0; k < l.size(); k++) { if (k > 0) sb.append(','); dumpTo(l.get(k), sb); }
            sb.append(']');
            return;
        }
        if (o instanceof Map) {
            sb.append('{');
            Map<String, Object> m = (Map<String, Object>) o;
            int k = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (k++ > 0) sb.append(',');
                sb.append('"').append(escape(e.getKey())).append("\":");
                dumpTo(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        sb.append('"').append(escape(String.valueOf(o))).append('"');
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── parsing ──────────────────────────────────────────────────────────

    private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object parseValue() {
        char c = s.charAt(i);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't') { expect("true"); return Boolean.TRUE; }
        if (c == 'f') { expect("false"); return Boolean.FALSE; }
        if (c == 'n') { expect("null"); return null; }
        return parseNumber();
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw new RuntimeException("Invalid JSON literal near " + i);
        i += lit.length();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; skipWs();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            if (s.charAt(i) != ':') throw new RuntimeException("Expected ':' at " + i);
            i++; skipWs();
            Object val = parseValue();
            m.put(key, val);
            skipWs();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; break; }
            throw new RuntimeException("Expected ',' or '}' at " + i);
        }
        return m;
    }

    private List<Object> parseArray() {
        List<Object> l = new ArrayList<>();
        i++; skipWs();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            skipWs();
            l.add(parseValue());
            skipWs();
            char c = s.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; break; }
            throw new RuntimeException("Expected ',' or ']' at " + i);
        }
        return l;
    }

    private String parseString() {
        if (s.charAt(i) != '"') throw new RuntimeException("Expected string at " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                char e = s.charAt(i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u':
                        String hex = s.substring(i + 1, i + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        i++;
        return sb.toString();
    }

    private Object parseNumber() {
        int start = i;
        boolean isFloat = false;
        if (s.charAt(i) == '-') i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i < s.length() && s.charAt(i) == '.') { isFloat = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            isFloat = true; i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        String num = s.substring(start, i);
        return isFloat ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  ERROR HANDLING
// ═════════════════════════════════════════════════════════════════════════

final class MonoError extends RuntimeException {
    MonoError(String message) { super(message); }
}

final class ErrorHandler {
    static Map<String, String> globalPolicy = new LinkedHashMap<>();

    static final Map<String, String> DEFAULT_POLICY = mapOf(
        "unknown_token", "exit", "type_error", "exit", "unclosed_bracket", "exit",
        "unclosed_string", "exit", "invalid_char", "exit", "unknown_operator", "warn",
        "rule_not_matched", "warn", "schema_violation", "exit"
    );

    private static Map<String, String> mapOf(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static void handle(String errorType, String message, Map<String, String> policy) {
        Map<String, String> pol = policy != null ? policy : (!globalPolicy.isEmpty() ? globalPolicy : DEFAULT_POLICY);
        String action = pol.getOrDefault(errorType, "exit");
        if (action.equals("exit")) {
            throw new MonoError(message);
        } else {
            System.err.println("[Warning] " + message);
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  TOKEN
// ═════════════════════════════════════════════════════════════════════════

final class Token {
    String type;
    Object value;   // Long / Double / String / null, as produced by the lexer
    long pos;
    int line, col, tab;

    Token(String type, Object value, long pos, int line, int col, int tab) {
        this.type = type; this.value = value; this.pos = pos; this.line = line; this.col = col; this.tab = tab;
    }

    @Override public String toString() {
        return type + ":" + MonoJson.dump(value) + "(L" + line + ",C" + col + ",tab=" + tab + ")";
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  BRACKET SPEC
// ═════════════════════════════════════════════════════════════════════════

final class BracketSpec {
    final String openType, closeType;
    BracketSpec(String o, String c) { openType = o; closeType = c; }
}

// ═════════════════════════════════════════════════════════════════════════
//  GRAMMAR STATE  (mirrors the Python module-level globals)
// ═════════════════════════════════════════════════════════════════════════

final class GrammarState {
    static Map<String, Set<String>> collectTypes = new LinkedHashMap<>();   // null value == "any allowed"
    static Set<String> collectTypesUnrestricted = new HashSet<>();          // modes whose entry was JSON null
    static Map<String, BracketSpec> collectBrackets = new LinkedHashMap<>(); // absent/null == no brackets
    static Map<String, Map<String, String>> collectDelimiters = new LinkedHashMap<>();
    static Set<String> collectExprMode = new LinkedHashSet<>();
    static Map<String, Object> customStringModes = MonoJson.obj();
    static Map<String, String> errorPolicy = new LinkedHashMap<>();
    static boolean enableCharType = true;
    static Object lexerRule = MonoJson.obj();  // {keyword, operator, symbol}
    static Map<String, Object> grammar = MonoJson.obj();  // the full parsed rule (file or default)
    static boolean loaded = false;                         // true once any rule (file or default) is loaded

    /** If no rule has been loaded yet, load the built-in default rule. */
    static void ensureLoaded() { if (!loaded) loadDefaultRule(); }

    /** Grammar of the active rule, loading the default first if needed. */
    static Map<String, Object> activeGrammar() { ensureLoaded(); return grammar; }

    static final Map<String, Map<String, String>> BUILTIN_COLLECT_DELIMITERS = new LinkedHashMap<>();
    static {
        Map<String, String> list = new LinkedHashMap<>(); list.put("item_sep", "COMMA");
        Map<String, String> map = new LinkedHashMap<>(); map.put("item_sep", "COMMA"); map.put("kv_sep", "COLON");
        Map<String, String> func = new LinkedHashMap<>(); func.put("item_sep", "COMMA");
        BUILTIN_COLLECT_DELIMITERS.put("collectlist", list);
        BUILTIN_COLLECT_DELIMITERS.put("collectmap", map);
        BUILTIN_COLLECT_DELIMITERS.put("collectfunc", func);
    }

    static final Map<String, String> ACTION_TYPE_MAP = new LinkedHashMap<>();
    static {
        ACTION_TYPE_MAP.put("LetInt_Cmd", "let"); ACTION_TYPE_MAP.put("LetString_Cmd", "let");
        ACTION_TYPE_MAP.put("LetFloat_Cmd", "let"); ACTION_TYPE_MAP.put("LetDouble_Cmd", "let");
        ACTION_TYPE_MAP.put("LetChar_Cmd", "let"); ACTION_TYPE_MAP.put("LetBool_Cmd", "let");
        ACTION_TYPE_MAP.put("LetAuto_Cmd", "let"); ACTION_TYPE_MAP.put("Assign_Cmd", "assign");
        ACTION_TYPE_MAP.put("Call_Cmd", "call"); ACTION_TYPE_MAP.put("Return_Cmd", "return");
        ACTION_TYPE_MAP.put("If_Cmd", "if"); ACTION_TYPE_MAP.put("While_Cmd", "while");
        ACTION_TYPE_MAP.put("while_loop", "while"); ACTION_TYPE_MAP.put("For_Cmd", "for");
        ACTION_TYPE_MAP.put("for_loop", "for"); ACTION_TYPE_MAP.put("Block_Cmd", "block");
    }

    static final Map<String, String> COLLECT_DATA_TYPE_MAP = new LinkedHashMap<>();
    static {
        COLLECT_DATA_TYPE_MAP.put("collectint", "int"); COLLECT_DATA_TYPE_MAP.put("collectfloat", "float");
        COLLECT_DATA_TYPE_MAP.put("collectdouble", "double"); COLLECT_DATA_TYPE_MAP.put("collectstring", "string");
        COLLECT_DATA_TYPE_MAP.put("collectchar", "char"); COLLECT_DATA_TYPE_MAP.put("collectbool", "bool");
        COLLECT_DATA_TYPE_MAP.put("collectauto", "auto"); COLLECT_DATA_TYPE_MAP.put("collectexpr", "expr");
        COLLECT_DATA_TYPE_MAP.put("collectlist", "list"); COLLECT_DATA_TYPE_MAP.put("collectmap", "map");
        COLLECT_DATA_TYPE_MAP.put("collectcode", "block"); COLLECT_DATA_TYPE_MAP.put("collectfunc", "func_args");
        COLLECT_DATA_TYPE_MAP.put("collect", "raw");
    }

    /** Load a grammar from a rule.json file on disk. */
    static void loadRule(String path) throws IOException {
        loadRuleText(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
    }

    /** Load the built-in default grammar (used when no rule file is given). */
    static void loadDefaultRule() {
        loadRuleText(DefaultRule.JSON);
    }

    @SuppressWarnings("unchecked")
    static void loadRuleText(String text) {
        Map<String, Object> ruleData = (Map<String, Object>) MonoJson.parse(text);
        grammar = ruleData;
        loaded = true;

        Map<String, Object> cfg = (Map<String, Object>) MonoJson.get(ruleData, "_config", MonoJson.obj());
        enableCharType = MonoJson.asBool(cfg.get("enable_char_type"), true);

        Map<String, Object> rawPolicy = (Map<String, Object>) MonoJson.get(ruleData, "_error_policy", MonoJson.obj());
        errorPolicy = new LinkedHashMap<>(ErrorHandler.DEFAULT_POLICY);
        for (Map.Entry<String, Object> e : rawPolicy.entrySet()) errorPolicy.put(e.getKey(), (String) e.getValue());
        ErrorHandler.globalPolicy = errorPolicy;
        validateSchema(ruleData);

        lexerRule = ruleData.get("lexer");
        Map<String, Object> norm = (Map<String, Object>) ruleData.get("normalizer");

        collectTypes.clear();
        collectTypesUnrestricted.clear();
        Map<String, Object> rawCt = (Map<String, Object>) MonoJson.get(norm, "collect_types", MonoJson.obj());
        for (Map.Entry<String, Object> e : rawCt.entrySet()) {
            if (e.getValue() == null) { collectTypesUnrestricted.add(e.getKey()); continue; }
            Set<String> set = new LinkedHashSet<>();
            for (Object t : (List<Object>) e.getValue()) set.add((String) t);
            collectTypes.put(e.getKey(), set);
        }

        collectBrackets.clear();
        Map<String, Object> rawCb = (Map<String, Object>) MonoJson.get(norm, "collect_brackets", MonoJson.obj());
        for (Map.Entry<String, Object> e : rawCb.entrySet()) {
            BracketSpec spec = parseBracketEntry(e.getValue());
            if (spec != null) collectBrackets.put(e.getKey(), spec);
        }

        collectDelimiters.clear();
        for (Map.Entry<String, Map<String, String>> e : BUILTIN_COLLECT_DELIMITERS.entrySet())
            collectDelimiters.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        Map<String, Object> rawCd = (Map<String, Object>) MonoJson.get(norm, "collect_delimiters", MonoJson.obj());
        for (Map.Entry<String, Object> e : rawCd.entrySet()) {
            Map<String, String> target = collectDelimiters.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>());
            Map<String, Object> conf = (Map<String, Object>) e.getValue();
            for (Map.Entry<String, Object> c : conf.entrySet()) target.put(c.getKey(), (String) c.getValue());
        }

        Object csm = MonoJson.get(norm, "collect_string_modes", MonoJson.obj());
        customStringModes = csm != null ? (Map<String, Object>) csm : MonoJson.obj();

        collectExprMode.clear();
        Object exprList = MonoJson.get(norm, "collect_expr_mode", MonoJson.arr());
        if (exprList instanceof List) for (Object e : (List<Object>) exprList) collectExprMode.add((String) e);
    }

    private static BracketSpec parseBracketEntry(Object entry) {
        if (entry == null) return null;
        if (entry instanceof List) {
            List<?> l = (List<?>) entry;
            if (l.size() != 2) throw new IllegalArgumentException("collect_brackets array must have exactly 2 elements");
            return new BracketSpec((String) l.get(0), (String) l.get(1));
        }
        if (entry instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) entry;
            boolean same = MonoJson.asBool(m.get("same"), false);
            String openSpec = MonoJson.asString(m.get("open"), "");
            String closeSpec = same ? openSpec : MonoJson.asString(m.get("close"), "");
            return new BracketSpec(openSpec, closeSpec);
        }
        throw new IllegalArgumentException("Invalid collect_brackets entry: " + entry);
    }

    private static void validateSchema(Map<String, Object> ruleData) {
        List<String> errors = new ArrayList<>();
        for (String key : new String[]{"_config", "rules", "lexer", "normalizer"})
            if (!ruleData.containsKey(key)) errors.add("Missing required key: '" + key + "'");
        @SuppressWarnings("unchecked")
        Map<String, Object> norm = (Map<String, Object>) MonoJson.get(ruleData, "normalizer", MonoJson.obj());
        for (String sub : new String[]{"collect_types", "collect_brackets", "collect_expr_mode"})
            if (!norm.containsKey(sub)) errors.add("normalizer missing key: '" + sub + "'");
        if (!errors.isEmpty()) {
            StringBuilder msg = new StringBuilder("[SchemaError] rule.json has schema violations:\n");
            for (String e : errors) msg.append("  \u2022 ").append(e).append("\n");
            ErrorHandler.handle("schema_violation", msg.toString(), errorPolicy);
        }
    }

    // returns null if the mode allows any token type (no restriction)
    static Set<String> allowedFor(String cmdName) {
        if (collectTypesUnrestricted.contains(cmdName)) return null;
        return collectTypes.get(cmdName);
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  LEXER — Maximal Munch
// ═════════════════════════════════════════════════════════════════════════

final class Lexer {
    private enum State { START, IDENTIFIER, NUMBER, STRING, CHAR, COMMENT_LINE, COMMENT_BLOCK, CUSTOM_STRING_BODY, DONE }

    public String code;
    private int pos = 0, line = 1, col = 0, tabSize;
    private Character currentChar;
    private StringBuilder buffer = new StringBuilder();
    private final List<Token> tokens = new ArrayList<>();
    private int lineTab = 0;
    private State state = State.START;

    @SuppressWarnings("unchecked")
    private Map<String, Object> keywords = MonoJson.obj(), operators = MonoJson.obj(), symbols = MonoJson.obj();
    private final Map<String, Map<String, Object>> customStrings = new LinkedHashMap<>();
    private final List<Object[]> munchTable = new ArrayList<>(); // {text, tokType, kind}
    private Map<String, Object> activeCustomCfg;

    @SuppressWarnings("unchecked")
    Lexer(String code, int tabSize) {
        GrammarState.ensureLoaded();   // no rule loaded yet -> use the built-in default rule
        this.code = code;
        this.tabSize = tabSize;
        if (!code.isEmpty()) currentChar = code.charAt(0);

        if (GrammarState.lexerRule != null) {
            Map<String, Object> lr = (Map<String, Object>) GrammarState.lexerRule;
            keywords = (Map<String, Object>) MonoJson.get(lr, "keyword", MonoJson.obj());
            operators = (Map<String, Object>) MonoJson.get(lr, "operator", MonoJson.obj());
            symbols = (Map<String, Object>) MonoJson.get(lr, "symbol", MonoJson.obj());
        }

        for (Map.Entry<String, Object> e : GrammarState.customStringModes.entrySet()) {
            Map<String, Object> cfg = (Map<String, Object>) e.getValue();
            String openCh = MonoJson.asString(cfg.get("open"), "");
            if (!openCh.isEmpty()) {
                Map<String, Object> c = new LinkedHashMap<>(cfg);
                c.put("_mode", e.getKey());
                customStrings.put(openCh, c);
            }
        }

        for (Map.Entry<String, Object> e : operators.entrySet()) munchTable.add(new Object[]{e.getKey(), e.getValue(), "op"});
        for (Map.Entry<String, Object> e : symbols.entrySet())   munchTable.add(new Object[]{e.getKey(), e.getValue(), "sym"});
        for (Map.Entry<String, Map<String, Object>> e : customStrings.entrySet())
            munchTable.add(new Object[]{e.getKey(), MonoJson.asString(e.getValue().get("token"), "STRING"), "custom"});

        munchTable.sort((a, b) -> {
            String ta = (String) a[0], tb = (String) b[0];
            if (ta.length() != tb.length()) return tb.length() - ta.length();
            return ta.compareTo(tb);
        });

        calcLineTab();
    }

    List<Token> run() {
        while (state != State.DONE) step();
        emit("EOF", null);
        return tokens;
    }

    private void calcLineTab() {
        int p = pos, spaces = 0;
        while (p < code.length() && (code.charAt(p) == ' ' || code.charAt(p) == '\t')) {
            spaces += (code.charAt(p) == '\t') ? tabSize : 1;
            p++;
        }
        lineTab = spaces / tabSize;
    }

    private void advance() {
        if (currentChar != null && currentChar == '\n') { line++; col = 0; }
        else { col++; }
        pos++;
        if (pos < code.length()) {
            currentChar = code.charAt(pos);
            if (col == 0) calcLineTab();
        } else {
            currentChar = null;
        }
    }

    private Character peek(int offset) {
        int nxt = pos + offset;
        return nxt < code.length() ? code.charAt(nxt) : null;
    }

    private String peekStr(int length) {
        if (pos >= code.length()) return "";
        return code.substring(pos, Math.min(pos + length, code.length()));
    }

    private void emit(String type, Object value) {
        tokens.add(new Token(type, value, pos, line, col, lineTab));
    }

    private Object[] tryMunch() {
        for (Object[] entry : munchTable) {
            String text = (String) entry[0];
            if (peekStr(text.length()).equals(text)) return entry;
        }
        return null;
    }

    private void consumeChars(int n) { for (int k = 0; k < n; k++) advance(); }

    private void step() {
        switch (state) {
            case START: stateStart(); break;
            case IDENTIFIER: stateIdentifier(); break;
            case NUMBER: stateNumber(); break;
            case STRING: stateString(); break;
            case CHAR: stateChar(); break;
            case COMMENT_LINE: stateCommentLine(); break;
            case COMMENT_BLOCK: stateCommentBlock(); break;
            case CUSTOM_STRING_BODY: stateCustomStringBody(); break;
            case DONE: break;
        }
    }

    private void stateStart() {
        if (currentChar == null) { state = State.DONE; return; }
        char c = currentChar;

        if (c == '\n') { emit("NEWLINE", "\\n"); advance(); return; }
        if (c == ' ' || c == '\t' || c == '\r') { advance(); return; }

        buffer.setLength(0);

        if (Character.isLetter(c) || c == '_') { state = State.IDENTIFIER; return; }
        if (Character.isDigit(c)) { state = State.NUMBER; return; }
        if (c == '"') { advance(); state = State.STRING; return; }
        if (c == '\'') { advance(); state = State.CHAR; return; }

        Object[] match = tryMunch();
        if (match != null) {
            String text = (String) match[0], tokType = (String) match[1], kind = (String) match[2];
            if (kind.equals("custom")) {
                activeCustomCfg = customStrings.get(text);
                consumeChars(text.length());
                state = State.CUSTOM_STRING_BODY;
                return;
            }
            if (kind.equals("op")) {
                if (text.equals("//")) { consumeChars(2); state = State.COMMENT_LINE; return; }
                if (text.equals("/*")) { consumeChars(2); state = State.COMMENT_BLOCK; return; }
            }
            emit(tokType, text);
            consumeChars(text.length());
            return;
        }

        ErrorHandler.handle("unknown_token",
            "Unknown char at line " + line + ", col " + col + ": '" + c + "'", GrammarState.errorPolicy);
        advance();
    }

    private void stateCustomStringBody() {
        String closeCh = MonoJson.asString(activeCustomCfg.get("close"), MonoJson.asString(activeCustomCfg.get("open"), ""));
        String tokenT = MonoJson.asString(activeCustomCfg.get("token"), "STRING");
        @SuppressWarnings("unchecked")
        Map<String, Object> escape = (Map<String, Object>) activeCustomCfg.get("escape");

        while (true) {
            if (currentChar == null) {
                ErrorHandler.handle("unclosed_string",
                    "Unclosed custom string (opener=" + activeCustomCfg.get("open") + ") at line " + line,
                    GrammarState.errorPolicy);
                state = State.DONE;
                return;
            }
            if (peekStr(closeCh.length()).equals(closeCh)) {
                consumeChars(closeCh.length());
                emit(tokenT, buffer.toString());
                state = State.START;
                return;
            }
            if (escape != null && !escape.isEmpty() && currentChar == '\\') {
                advance();
                String key = String.valueOf(currentChar);
                Object val = escape.getOrDefault(key, key);
                buffer.append(val instanceof String ? (String) val : String.valueOf(val));
                advance();
                continue;
            }
            buffer.append(currentChar);
            advance();
        }
    }

    private void stateIdentifier() {
        while (currentChar != null && (Character.isLetterOrDigit(currentChar) || currentChar == '_')) {
            buffer.append(currentChar);
            advance();
        }
        String tok = MonoJson.asString(keywords.get(buffer.toString()), "IDENTIFIER");
        emit(tok, buffer.toString());
        state = State.START;
    }

    private void stateNumber() {
        boolean hasDot = false, hasExp = false;
        while (currentChar != null) {
            char c = currentChar;
            if (Character.isDigit(c)) { buffer.append(c); }
            else if (c == '.' && !hasDot) { hasDot = true; buffer.append(c); }
            else if ((c == 'e' || c == 'E') && !hasExp) {
                hasExp = true; buffer.append(c);
                Character nx = peek(1);
                if (nx != null && (nx == '+' || nx == '-')) { advance(); buffer.append(currentChar); }
            } else { break; }
            advance();
        }
        if (hasDot || hasExp) emit("FLOAT", Double.parseDouble(buffer.toString()));
        else emit("INT", Long.parseLong(buffer.toString()));
        state = State.START;
    }

    private void stateString() {
        if (currentChar == null) {
            ErrorHandler.handle("unclosed_string", "Unclosed string at line " + line + ", col " + col, GrammarState.errorPolicy);
            state = State.START;
            return;
        }
        if (currentChar == '"') {
            emit("STRING", buffer.toString());
            advance();
            state = State.START;
            return;
        }
        if (currentChar == '\\') {
            advance();
            char c = currentChar != null ? currentChar : '\0';
            switch (c) {
                case 'n': buffer.append('\n'); break;
                case 't': buffer.append('\t'); break;
                case '"': buffer.append('"'); break;
                case '\\': buffer.append('\\'); break;
                default: buffer.append(c);
            }
        } else {
            buffer.append(currentChar);
        }
        advance();
    }

    private void stateChar() {
        if (currentChar == null) {
            ErrorHandler.handle("unclosed_string", "Unclosed char literal at line " + line + ", col " + col, GrammarState.errorPolicy);
            state = State.START;
            return;
        }
        String value = String.valueOf(currentChar);
        advance();
        if (currentChar == null || currentChar != '\'') {
            ErrorHandler.handle("invalid_char",
                "Invalid char literal at line " + line + ", col " + col + " -- expected closing \"'\"", GrammarState.errorPolicy);
            state = State.START;
            return;
        }
        advance();
        emit("CHAR", value);
        state = State.START;
    }

    private void stateCommentLine() {
        while (currentChar != null && currentChar != '\n') advance();
        state = State.START;
    }

    private void stateCommentBlock() {
        while (currentChar != null) {
            if (peekStr(2).equals("*/")) { advance(); advance(); break; }
            advance();
        }
        state = State.START;
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  TOKEN TYPE / IR HELPERS
// ═════════════════════════════════════════════════════════════════════════

final class TokenTypes {
    private static final Set<String> OPERATORS = new HashSet<>(Arrays.asList(
        "PLUS", "MINUS", "MUL", "DIV", "ASSIGN", "EQ", "NEQ", "GT", "LT", "GTE", "LTE"));
    private static final Set<String> BRACKETS = new HashSet<>(Arrays.asList(
        "LPAREN", "RPAREN", "LBRACKET", "RBRACKET", "LBRACE", "RBRACE"));
    private static final Set<String> DELIMS = new HashSet<>(Arrays.asList("COMMA", "SEMICOLON", "COLON"));

    static String detect(Token token) {
        String t = token.type;
        if (t.equals("INT")) return "int";
        if (t.equals("FLOAT")) return "float";
        if (t.equals("STRING")) return "string";
        if (t.equals("CHAR")) return GrammarState.enableCharType ? "char" : "string";
        if (t.equals("BOOL")) return "bool";
        if (t.equals("IDENTIFIER")) return "identifier";
        if (OPERATORS.contains(t)) return "operator";
        if (BRACKETS.contains(t)) return "bracket";
        if (DELIMS.contains(t)) return "delimiter";
        if (t.equals("NEWLINE")) return "newline";
        if (t.equals("EOF")) return "eof";
        return t.toLowerCase(Locale.ROOT);
    }

    static Object coerceValue(Token token) {
        if (token.type.equals("INT")) return ((Number) token.value).longValue();
        if (token.type.equals("FLOAT")) return ((Number) token.value).doubleValue();
        if (token.type.equals("BOOL")) return "true".equals(token.value);
        return token.value;
    }

    static Map<String, Object> coerceToken(Token token, boolean includeType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", coerceValue(token));
        result.put("line", (long) token.line);
        result.put("col", (long) token.col);
        if (includeType) result.put("type", detect(token));
        return result;
    }

    static Map<String, Object> coerceToken(Token token) { return coerceToken(token, true); }
}

final class IrTypes {
    static String resolveIrType(String action) {
        String mapped = GrammarState.ACTION_TYPE_MAP.get(action);
        if (mapped != null) return mapped;

        String name = action;
        if (name.endsWith("_Cmd")) name = name.substring(0, name.length() - 4);
        StringBuilder snake = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) snake.append('_');
            snake.append(Character.toLowerCase(c));
        }
        return snake.toString();
    }

    static Map<String, Object> resolveDataTypeDetailed(String collectMode, Object data) {
        String typeMap = collectMode != null ? GrammarState.COLLECT_DATA_TYPE_MAP.getOrDefault(collectMode, "unknown") : "unknown";
        String valueType = "unknown";

        if (data instanceof List && !((List<?>) data).isEmpty()) {
            Object first = ((List<?>) data).get(0);
            if (first instanceof Map && ((Map<?, ?>) first).containsKey("value")) {
                valueType = classifyScalar(((Map<?, ?>) first).get("value"));
            } else if (first instanceof Map) {
                valueType = "object";
            } else if (first instanceof List) {
                valueType = "list";
            } else {
                valueType = classifyScalar(first);
            }
        } else if (data instanceof Map) {
            valueType = "map";
        } else if (data != null) {
            valueType = classifyScalar(data);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", typeMap);
        out.put("value_type", valueType);
        out.put("collect_mode", collectMode);
        return out;
    }

    private static String classifyScalar(Object v) {
        if (v instanceof Boolean) return "bool";
        if (v instanceof Long || v instanceof Integer) return "int";
        if (v instanceof Double) return "float";
        if (v instanceof String) {
            String s = (String) v;
            if (s.length() == 1 && GrammarState.enableCharType) return "char";
            return "string";
        }
        return "unknown";
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  LIST / MAP TOKEN PARSING
// ═════════════════════════════════════════════════════════════════════════

final class CollectionParsing {

    static String getItemSep(String cmdName) {
        Map<String, String> m = GrammarState.collectDelimiters.get(cmdName);
        return (m != null && m.containsKey("item_sep")) ? m.get("item_sep") : "COMMA";
    }

    static String getKvSep(String cmdName) {
        Map<String, String> m = GrammarState.collectDelimiters.get(cmdName);
        return (m != null && m.containsKey("kv_sep")) ? m.get("kv_sep") : "COLON";
    }

    static List<Object> parseListTokens(List<Token> rawTokens, String openType, String closeType, String itemSep) {
        List<Object> result = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;

        for (Token tok : rawTokens) {
            if (tok.type.equals(openType)) { depth++; current.add(tok); }
            else if (tok.type.equals(closeType)) {
                depth--;
                if (depth < 0) {
                    ErrorHandler.handle("unclosed_bracket",
                        "Unbalanced brackets in list at line " + tok.line + ", col " + tok.col, GrammarState.errorPolicy);
                    break;
                }
                current.add(tok);
            } else if (tok.type.equals(itemSep) && depth == 0) {
                if (!current.isEmpty()) result.add(evalTokenSeq(current, openType, closeType, itemSep));
                current = new ArrayList<>();
            } else {
                current.add(tok);
            }
        }
        if (!current.isEmpty()) result.add(evalTokenSeq(current, openType, closeType, itemSep));
        return result;
    }

    static Map<String, Object> parseMapTokens(List<Token> rawTokens, String openType, String closeType,
                                               String itemSep, String kvSep) {
        Map<String, Object> result = new LinkedHashMap<>();
        int depth = 0;
        boolean haveKey = false;
        Object[] keyHolder = new Object[1];
        List<Token> valTokens = new ArrayList<>();
        boolean[] inVal = {false};

        for (Token tok : rawTokens) {
            if (tok.type.equals("LBRACE") || tok.type.equals("LBRACKET")) {
                depth++;
                if (inVal[0]) valTokens.add(tok);
            } else if (tok.type.equals("RBRACE") || tok.type.equals("RBRACKET")) {
                depth--;
                if (inVal[0]) valTokens.add(tok);
            } else if (tok.type.equals(kvSep) && depth == 0 && !inVal[0]) {
                inVal[0] = true;
            } else if (tok.type.equals(itemSep) && depth == 0) {
                if (haveKey) {
                    String keyStr = keyHolder[0] instanceof String ? (String) keyHolder[0] : MonoJson.dump(keyHolder[0]);
                    result.put(keyStr, evalTokenSeq(valTokens, "LBRACE", "RBRACE", "COMMA"));
                }
                haveKey = false; keyHolder[0] = null; valTokens = new ArrayList<>(); inVal[0] = false;
            } else {
                if (!inVal[0]) { keyHolder[0] = TokenTypes.coerceValue(tok); haveKey = true; }
                else { valTokens.add(tok); }
            }
        }
        if (haveKey) {
            String keyStr = keyHolder[0] instanceof String ? (String) keyHolder[0] : MonoJson.dump(keyHolder[0]);
            result.put(keyStr, evalTokenSeq(valTokens, "LBRACE", "RBRACE", "COMMA"));
        }
        return result;
    }

    // BUG FIX 2: compare against the actual openType/closeType parameters
    // (not the hard-coded "LBRACKET"/"LBRACE" strings from the Python
    // original) so custom bracket token names recurse correctly.
    static Object evalTokenSeq(List<Token> tokens, String openType, String closeType, String itemSep) {
        if (tokens.isEmpty()) return null;
        if (tokens.get(0).type.equals(openType) && tokens.get(tokens.size() - 1).type.equals(closeType)) {
            List<Token> inner = tokens.subList(1, tokens.size() - 1);
            if (openType.equals("LBRACKET")) return parseListTokens(inner, openType, closeType, itemSep);
            if (openType.equals("LBRACE")) return parseMapTokens(inner, openType, closeType, itemSep, "COLON");
        }
        if (tokens.size() == 1) return TokenTypes.coerceToken(tokens.get(0));
        List<Object> arr = new ArrayList<>();
        for (Token t : tokens) arr.add(TokenTypes.coerceToken(t));
        return arr;
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  NORMALIZER  (parser: walks the JSON grammar rule tree against the tokens)
// ═════════════════════════════════════════════════════════════════════════

@SuppressWarnings("unchecked")
final class Normalizer {
    private List<Token> tokens = new ArrayList<>();
    private int pos = 0;
    private final List<Map<String, Object>> ir = new ArrayList<>();
    private String lineTerminator;
    private String stmtSeparator;   // may be null
    private String sepTokenType;    // may be null
    private Map<String, Object> rules;
    private Integer currentTab = null;
    private Map<String, String> errorPolicy;
    private String lastCollectMode = null;

    Normalizer(List<Token> rawTokens, Map<String, Object> grammar) {
        Map<String, Object> cfg = (Map<String, Object>) MonoJson.get(grammar, "_config", MonoJson.obj());
        lineTerminator = MonoJson.asString(cfg.get("line_terminator"), ";");
        rules = (Map<String, Object>) MonoJson.get(grammar, "rules", MonoJson.obj());
        stmtSeparator = cfg.containsKey("stmt_separator") ? MonoJson.asString(cfg.get("stmt_separator"), null) : null;
        sepTokenType = resolveSepType(stmtSeparator);

        GrammarState.enableCharType = MonoJson.asBool(cfg.get("enable_char_type"), true);

        Map<String, Object> rawPolicy = (Map<String, Object>) MonoJson.get(grammar, "_error_policy", MonoJson.obj());
        errorPolicy = new LinkedHashMap<>(ErrorHandler.DEFAULT_POLICY);
        for (Map.Entry<String, Object> e : rawPolicy.entrySet()) errorPolicy.put(e.getKey(), (String) e.getValue());

        boolean keepNewline = MonoJson.asBool(cfg.get("keep_newline"), false);
        Set<String> exclude = keepNewline ? new HashSet<>(Collections.singletonList("EOF"))
                                           : new HashSet<>(Arrays.asList("NEWLINE", "EOF"));
        for (Token t : rawTokens) if (!exclude.contains(t.type)) tokens.add(t);
        for (Token t : rawTokens) if (t.type.equals("EOF")) tokens.add(t);
    }

    /** Uses the active grammar (default rule if none was loaded). */
    Normalizer(List<Token> rawTokens) { this(rawTokens, GrammarState.activeGrammar()); }

    // used for the sub-normalizer created inside collectCodeBlock()
    private Normalizer() { }

    List<Map<String, Object>> parse() {
        while (true) {
            Token tok = peek(0);
            if (tok == null || tok.type.equals("EOF")) break;

            if (currentTab == null) currentTab = tok.tab;
            int tab = currentTab;

            int stmtLine = tok.line, stmtCol = tok.col;
            String triggerNode = tok.type;

            if (!rules.containsKey(tok.type)) {
                ErrorHandler.handle("rule_not_matched",
                    "[Warning] No rule matched for token '" + tok.type + "' (value=" + MonoJson.dump(tok.value) + ") at " + loc(tok),
                    errorPolicy);
                consume();
                continue;
            }
            Map<String, Object> rule = (Map<String, Object>) rules.get(tok.type);

            lastCollectMode = null;
            int savedPos = pos;
            consume();
            List<Object> accumulated = new ArrayList<>();
            Object[] result = walkRule(rule, tab, accumulated);

            if (result != null) {
                String action = (String) result[0];
                List<Object> data = (List<Object>) result[1];
                String irType = IrTypes.resolveIrType(action);
                Map<String, Object> dataTypeInfo = IrTypes.resolveDataTypeDetailed(lastCollectMode, data);

                Map<String, Object> irNode = new LinkedHashMap<>();
                irNode.put("action", action);
                irNode.put("type", irType);
                irNode.put("node", triggerNode);
                irNode.put("data_type", dataTypeInfo);
                irNode.put("data", data);
                irNode.put("tab", (long) tab);
                irNode.put("line", (long) stmtLine);
                irNode.put("col", (long) stmtCol);
                ir.add(irNode);
            } else {
                pos = savedPos;
                ErrorHandler.handle("rule_not_matched",
                    "[Error] Rule '" + triggerNode + "' matched but produced no result at " + loc(tok), errorPolicy);
                // BUG FIX 3: the original Python resets pos to savedPos and,
                // if the "rule_not_matched" policy is "warn" (so handle()
                // doesn't throw), falls back to the top of the loop pointed
                // at the exact same token -- an infinite loop. Force forward
                // progress by consuming the token, same as the "no rule for
                // this token" branch above.
                if (pos == savedPos) consume();
            }
        }
        return ir;
    }

    private static String resolveSepType(String sepChar) {
        if (sepChar == null) return null;
        Map<String, String> rev = new LinkedHashMap<>();
        rev.put(",", "COMMA"); rev.put(";", "SEMICOLON"); rev.put(":", "COLON");
        rev.put("(", "LPAREN"); rev.put(")", "RPAREN"); rev.put("{", "LBRACE"); rev.put("}", "RBRACE");
        rev.put("[", "LBRACKET"); rev.put("]", "RBRACKET");
        return rev.get(sepChar);
    }

    private void err(String type, String message) { ErrorHandler.handle(type, message, errorPolicy); }

    private Token peek(int k) {
        int idx = pos + k;
        return idx < tokens.size() ? tokens.get(idx) : null;
    }

    private Token consume() { return tokens.get(pos++); }

    private static String loc(Token tok) {
        return tok == null ? "end of input" : "line " + tok.line + ", col " + tok.col;
    }

    private boolean isTerminator(Token tok) {
        if (tok == null) return false;
        if (lineTerminator.equals("NEWLINE")) return tok.type.equals("NEWLINE");
        return tok.value instanceof String && tok.value.equals(lineTerminator);
    }

    private boolean isSeparator(Token tok) {
        if (tok == null || sepTokenType == null) return false;
        if (sepTokenType.equals(lineTerminator)) return false;
        return tok.type.equals(sepTokenType);
    }

    private static String resolveBracketTokenType(Object spec) {
        if (spec instanceof String) return (String) spec;
        if (spec instanceof Map) return MonoJson.asString(((Map<String, Object>) spec).get("token"), "UNKNOWN");
        return String.valueOf(spec);
    }

    // ── _collect_until_close ────────────────────────────────────────────
    private Object collectUntilClose(String openType, String closeType, Set<String> allowed, String cmdName) {
        Token openTok = peek(0);
        if (openTok == null || !openTok.type.equals(openType)) {
            err("unclosed_bracket", "[SyntaxError] " + cmdName + ": expected '" + openType + "' but got "
                + (openTok == null ? "null" : openTok.toString()) + " at " + loc(openTok));
            return new ArrayList<>();
        }
        Token openTokCopy = openTok;
        consume();

        int depth = 1;
        boolean isMap = cmdName.equals("collectmap");
        boolean isList = cmdName.equals("collectlist");
        List<Token> rawTokens = new ArrayList<>();

        String itemSep = CollectionParsing.getItemSep(cmdName);
        String kvSep = CollectionParsing.getKvSep(cmdName);

        while (true) {
            Token tok = peek(0);
            if (tok == null || tok.type.equals("EOF")) {
                err("unclosed_bracket", "[SyntaxError] " + cmdName + ": unclosed '" + openType + "' (opened at "
                    + loc(openTokCopy) + ") -- reached end of input");
                break;
            }
            if (tok.type.equals(openType)) { depth++; rawTokens.add(consume()); }
            else if (tok.type.equals(closeType)) {
                depth--;
                if (depth == 0) { consume(); break; }
                rawTokens.add(consume());
            } else {
                if (allowed != null && !allowed.contains(tok.type)) {
                    err("type_error", "[TypeError] " + cmdName + ": unexpected token '" + tok.type
                        + "' (value=" + MonoJson.dump(tok.value) + ") at " + loc(tok));
                    consume();
                    continue;
                }
                rawTokens.add(consume());
            }
        }

        if (isList) return CollectionParsing.parseListTokens(rawTokens, openType, closeType, itemSep);
        if (isMap) return CollectionParsing.parseMapTokens(rawTokens, openType, closeType, itemSep, kvSep);
        List<Object> arr = new ArrayList<>();
        for (Token t : rawTokens) arr.add(TokenTypes.coerceToken(t));
        return arr;
    }

    // ── _collect_code_block ─────────────────────────────────────────────
    private List<Object> collectCodeBlock() {
        String openType = "LBRACE", closeType = "RBRACE";
        BracketSpec spec = GrammarState.collectBrackets.get("collectcode");
        if (spec != null) { openType = spec.openType; closeType = spec.closeType; }

        Token openTok = peek(0);
        if (openTok == null || !openTok.type.equals(openType)) {
            err("unclosed_bracket", "[SyntaxError] collectcode: expected '" + openType + "' but got "
                + (openTok == null ? "null" : openTok.toString()) + " at " + loc(openTok));
            return new ArrayList<>();
        }
        Token openTokCopy = openTok;
        consume();

        int depth = 1;
        List<Token> blockToks = new ArrayList<>();
        while (true) {
            Token tok = peek(0);
            if (tok == null || tok.type.equals("EOF")) {
                err("unclosed_bracket", "[SyntaxError] collectcode: unclosed '" + openType + "' (opened at "
                    + loc(openTokCopy) + ") -- reached end of input");
                break;
            }
            if (tok.type.equals(openType)) { depth++; blockToks.add(consume()); }
            else if (tok.type.equals(closeType)) {
                depth--;
                if (depth == 0) { consume(); break; }
                blockToks.add(consume());
            } else blockToks.add(consume());
        }

        Token eofSentinel;
        if (!blockToks.isEmpty()) {
            Token last = blockToks.get(blockToks.size() - 1);
            eofSentinel = new Token("EOF", null, last.pos + 1, last.line, last.col + 1, last.tab);
        } else {
            eofSentinel = new Token("EOF", null, openTokCopy.pos + 1, openTokCopy.line, openTokCopy.col + 1, openTokCopy.tab);
        }
        blockToks.add(eofSentinel);

        Normalizer sub = new Normalizer();
        sub.tokens = blockToks;
        sub.pos = 0;
        sub.lineTerminator = this.lineTerminator;
        sub.rules = this.rules;
        sub.stmtSeparator = this.stmtSeparator;
        sub.sepTokenType = this.sepTokenType;
        sub.currentTab = null;
        sub.errorPolicy = this.errorPolicy;
        sub.lastCollectMode = null;

        List<Map<String, Object>> subIr = sub.parse();
        return new ArrayList<>(subIr);
    }

    // ── _collect_expr ────────────────────────────────────────────────────
    private static final Set<String> EXPR_PASSTHROUGH = new HashSet<>(Arrays.asList(
        "LPAREN", "RPAREN", "PLUS", "MINUS", "MUL", "DIV", "EQ", "NEQ", "GT", "LT", "GTE", "LTE", "COMMA"));

    private Object collectExpr(String cmdName) {
        Set<String> allowed = GrammarState.allowedFor(cmdName);
        List<Object> data = new ArrayList<>();
        int depth = 0;
        List<Token> rawTokens = new ArrayList<>();

        while (true) {
            Token tok = peek(0);
            if (tok == null || tok.type.equals("EOF")) break;
            if (depth == 0) {
                if (isTerminator(tok)) { consume(); currentTab = null; break; }
                if (isSeparator(tok)) { consume(); break; }
            }
            if (tok.type.equals("LPAREN")) { depth++; Token c = consume(); data.add(TokenTypes.coerceToken(c)); rawTokens.add(c); continue; }
            if (tok.type.equals("RPAREN")) {
                if (depth == 0) break;
                depth--; Token c = consume(); data.add(TokenTypes.coerceToken(c)); rawTokens.add(c); continue;
            }
            if (allowed != null && !allowed.contains(tok.type) && !EXPR_PASSTHROUGH.contains(tok.type)) {
                err("type_error", "[TypeError] " + cmdName + ": unexpected token '" + tok.type
                    + "' (value=" + MonoJson.dump(tok.value) + ") at " + loc(tok));
                consume();
                continue;
            }
            Token consumed = consume();
            data.add(TokenTypes.coerceToken(consumed));
            rawTokens.add(consumed);
        }

        if (depth != 0) {
            err("unclosed_bracket", "[SyntaxError] " + cmdName + ": unbalanced parentheses near " + loc(peek(0)));
        }

        if (cmdName.equals("collectexpr")) {
            StringBuilder eq = new StringBuilder();
            for (int i = 0; i < rawTokens.size(); i++) {
                if (i > 0) eq.append(' ');
                Object v = rawTokens.get(i).value;
                eq.append(v instanceof String ? (String) v : String.valueOf(v));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tokens", data);
            out.put("equation", eq.toString());
            List<Object> rawArr = new ArrayList<>();
            for (Token t : rawTokens) rawArr.add(TokenTypes.coerceToken(t));
            out.put("raw_tokens", rawArr);
            return out;
        }
        return data;
    }

    // ── _collect_simple ─────────────────────────────────────────────────
    private List<Object> collectSimple(String cmdName) {
        Set<String> allowed = GrammarState.allowedFor(cmdName);
        List<Object> data = new ArrayList<>();
        while (true) {
            Token tok = peek(0);
            if (tok == null || tok.type.equals("EOF")) break;
            if (isTerminator(tok)) { consume(); currentTab = null; break; }
            if (isSeparator(tok)) { consume(); break; }
            if (allowed != null && !allowed.contains(tok.type)) {
                err("type_error", "[TypeError] " + cmdName + ": unexpected token '" + tok.type
                    + "' (value=" + MonoJson.dump(tok.value) + ") at " + loc(tok));
                consume();
                continue;
            }
            data.add(TokenTypes.coerceToken(consume()));
        }
        return data;
    }

    // ── _collect dispatcher ─────────────────────────────────────────────
    private Object collect(String cmdName, int tab) {
        lastCollectMode = cmdName;

        if (cmdName.equals("collectcode")) return collectCodeBlock();
        if (GrammarState.collectExprMode.contains(cmdName)) return collectExpr(cmdName);

        BracketSpec bracket = GrammarState.collectBrackets.get(cmdName);
        if (bracket != null) {
            Set<String> allowed = GrammarState.allowedFor(cmdName);
            return collectUntilClose(bracket.openType, bracket.closeType, allowed, cmdName);
        }
        return collectSimple(cmdName);
    }

    // ── rule walker ──────────────────────────────────────────────────────
    // Returns {action, data-list} as an Object[2], or null on failure.
    //
    // BUG FIX 1: when a matched token's every child alternative fails, the
    // original Python did `self.pos -= 1`, correct only if pos was still
    // exactly saved_pos (right after the single consume). A failed nested
    // walk_rule call does not always roll back its own partial consumption,
    // so pos can drift. Here we snapshot savedPos up front and restore to
    // `savedPos - 1` explicitly rather than decrementing whatever pos
    // happens to be when the loop ends.
    private Object[] walkRule(Map<String, Object> ruleNode, int tab, List<Object> accumulated) {
        if (ruleNode.containsKey("alternatives")) {
            List<Object> alts = (List<Object>) ruleNode.get("alternatives");
            for (Object altObj : alts) {
                Map<String, Object> alt = (Map<String, Object>) altObj;
                int savedPos = pos;
                Object[] result = walkRule(alt, tab, new ArrayList<>(accumulated));
                if (result != null) return result;
                pos = savedPos;
            }
            return null;
        }

        for (Map.Entry<String, Object> entry : ruleNode.entrySet()) {
            String key = entry.getKey();
            Object childObj = entry.getValue();

            if (key.equals("end")) {
                List<Object> child = (List<Object>) childObj;
                String action = child.size() > 1 ? (String) child.get(1) : "Unknown_Cmd";
                Token tok = peek(0);
                if (tok != null && isTerminator(tok)) { consume(); currentTab = null; }
                else if (tok != null && isSeparator(tok)) { consume(); }
                return new Object[]{action, accumulated};
            }

            if (key.equals("collect")) {
                Token tok = peek(0);
                if (tok == null || tok.type.equals("EOF")) return null;
                List<Object> newAcc = new ArrayList<>(accumulated);
                newAcc.add(TokenTypes.coerceToken(consume()));
                return walkRule((Map<String, Object>) childObj, tab, newAcc);
            }

            boolean isCollectType = GrammarState.collectTypes.containsKey(key) || GrammarState.collectTypesUnrestricted.contains(key);
            if (isCollectType) {
                int savedPos = pos;
                Object data;
                try {
                    data = collect(key, tab);
                } catch (MonoError | IllegalArgumentException ex) {
                    pos = savedPos;
                    continue;
                }

                List<Object> combined = new ArrayList<>(accumulated);
                if (key.equals("collectcode")) {
                    combined.add(data);
                } else if (data instanceof List) {
                    combined.addAll((List<Object>) data);
                } else {
                    combined.add(data);
                }
                return walkRule((Map<String, Object>) childObj, tab, combined);
            }

            // token type match — if matched and child has several keys, try each as alternatives
            Token tok = peek(0);
            if (tok == null) continue;
            if (tok.type.equals(key)) {
                consume();
                int savedPos = pos;   // == "right after the one consume above"
                Map<String, Object> child = (Map<String, Object>) childObj;
                for (Map.Entry<String, Object> centry : child.entrySet()) {
                    pos = savedPos;
                    Map<String, Object> singleChild = new LinkedHashMap<>();
                    singleChild.put(centry.getKey(), centry.getValue());
                    Object[] result = walkRule(singleChild, tab, new ArrayList<>(accumulated));
                    if (result != null) return result;
                }
                pos = savedPos - 1;   // BUG FIX 1: backtrack the single consume precisely
                continue;
            }
        }
        return null;
    }
}

// ═════════════════════════════════════════════════════════════════════════
//  DEFAULT RULE  (built-in grammar, used when no rule.json is given)
//  Same role as the default rule shipped in the other Mono ports.
// ═════════════════════════════════════════════════════════════════════════

final class DefaultRule {
    static final String JSON = """
{
  "_config": { "line_terminator": ";", "tab_size": 4, "keep_newline": false, "enable_char_type": true },
  "_error_policy": {
    "unknown_token": "exit", "type_error": "exit", "unclosed_bracket": "exit",
    "unclosed_string": "exit", "invalid_char": "exit", "unknown_operator": "warn",
    "rule_not_matched": "warn", "schema_violation": "exit"
  },
  "rules": {
    "KW_INT":    {"collect": {"ASSIGN": {"collectint":    {"end": [";", "LetInt_Cmd"]}}}},
    "KW_FLOAT":  {"collect": {"ASSIGN": {"collectfloat":  {"end": [";", "LetFloat_Cmd"]}}}},
    "KW_DOUBLE": {"collect": {"ASSIGN": {"collectdouble": {"end": [";", "LetDouble_Cmd"]}}}},
    "KW_STRING": {"collect": {"ASSIGN": {"collectstring": {"end": [";", "LetString_Cmd"]}}}},
    "KW_CHAR":   {"collect": {"ASSIGN": {"collectchar":   {"end": [";", "LetChar_Cmd"]}}}},
    "KW_BOOL":   {"collect": {"ASSIGN": {"collectbool":   {"end": [";", "LetBool_Cmd"]}}}},
    "KW_AUTO":   {"collect": {"ASSIGN": {"collectauto":   {"end": [";", "LetAuto_Cmd"]}}}},
    "IDENTIFIER": {"ASSIGN": {"collectexpr": {"end": [";", "Assign_Cmd"]}}},
    "KW_CALL":   {"collect": {"collectfunc": {"end": [";", "Call_Cmd"]}}},
    "KW_FN":     {"collect": {"collectfunc": {"collectcode": {"end": [";", "Fn_Cmd"]}}}},
    "KW_RETURN": {"collectexpr": {"end": [";", "Return_Cmd"]}},
    "KW_PRINT":  {"collectexpr": {"end": [";", "Print_Cmd"]}},
    "KW_IF":     {"collectcond": {"collectcode": {"end": [";", "If_Cmd"]}}},
    "KW_ELSE":   {"collectcode": {"end": [";", "Else_Cmd"]}},
    "KW_WHILE":  {"collectcond": {"collectcode": {"end": [";", "While_Cmd"]}}},
    "KW_FOR":    {"collectfor":  {"collectcode": {"end": [";", "For_Cmd"]}}}
  },
  "lexer": {
    "keyword": {
      "int": "KW_INT", "float": "KW_FLOAT", "double": "KW_DOUBLE", "string": "KW_STRING",
      "char": "KW_CHAR", "bool": "KW_BOOL", "auto": "KW_AUTO",
      "fn": "KW_FN", "call": "KW_CALL", "return": "KW_RETURN", "print": "KW_PRINT",
      "if": "KW_IF", "else": "KW_ELSE", "while": "KW_WHILE", "for": "KW_FOR",
      "true": "BOOL", "false": "BOOL"
    },
    "operator": {
      "+": "PLUS", "-": "MINUS", "*": "MUL", "/": "DIV", "%": "MOD",
      "^": "POW", "**": "POW", "=": "ASSIGN",
      "==": "EQ", "!=": "NEQ", ">": "GT", "<": "LT", ">=": "GTE", "<=": "LTE",
      "&&": "AND", "||": "OR",
      "//": "COMMENT_LINE", "/*": "COMMENT_BLOCK"
    },
    "symbol": {
      "(": "LPAREN", ")": "RPAREN", "{": "LBRACE", "}": "RBRACE",
      "[": "LBRACKET", "]": "RBRACKET", ";": "SEMICOLON", ",": "COMMA", ":": "COLON"
    }
  },
  "normalizer": {
    "collect_types": {
      "collectint": ["INT"],
      "collectfloat": ["FLOAT", "INT"],
      "collectdouble": ["FLOAT", "INT"],
      "collectstring": ["STRING"],
      "collectchar": ["CHAR"],
      "collectbool": ["BOOL"],
      "collectauto": ["INT","FLOAT","STRING","CHAR","BOOL","IDENTIFIER","LBRACKET","RBRACKET","LBRACE","RBRACE","COMMA","COLON"],
      "collectexpr": ["INT","FLOAT","STRING","CHAR","BOOL","IDENTIFIER","PLUS","MINUS","MUL","DIV","LPAREN","RPAREN","EQ","NEQ","GT","LT","GTE","LTE","AND","OR"],
      "collectlist": ["INT","FLOAT","STRING","CHAR","BOOL","IDENTIFIER","COMMA","LBRACKET","RBRACKET"],
      "collectmap": ["INT","FLOAT","STRING","CHAR","BOOL","IDENTIFIER","COMMA","COLON","LBRACE","RBRACE","LBRACKET","RBRACKET"],
      "collectfunc": ["INT","FLOAT","STRING","CHAR","BOOL","IDENTIFIER","COMMA","LPAREN","RPAREN"],
      "collectcond": ["INT","FLOAT","STRING","CHAR","BOOL","IDENTIFIER","PLUS","MINUS","MUL","DIV","LPAREN","RPAREN","EQ","NEQ","GT","LT","GTE","LTE","AND","OR"],
      "collectfor": null,
      "collectcode": null,
      "collect": null
    },
    "collect_brackets": {
      "collectlist": ["LBRACKET", "RBRACKET"],
      "collectfunc": ["LPAREN", "RPAREN"],
      "collectmap":  ["LBRACE", "RBRACE"],
      "collectcode": ["LBRACE", "RBRACE"],
      "collectcond": ["LPAREN", "RPAREN"],
      "collectfor":  ["LPAREN", "RPAREN"],
      "collectexpr": null
    },
    "collect_delimiters": {},
    "collect_string_modes": {},
    "collect_expr_mode": ["collectint","collectfloat","collectdouble","collectstring","collectchar","collectbool","collectauto","collectexpr"]
  }
}
""";
}

// ═════════════════════════════════════════════════════════════════════════
//  IR SUMMARY PRINTER + MAIN
// ═════════════════════════════════════════════════════════════════════════

public class Mono_j11 {

    @SuppressWarnings("unchecked")
    static String getDataTypeString(Map<String, Object> irNode) {
        Object dt = irNode.get("data_type");
        if (dt instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) dt;
            Object v = m.containsKey("value_type") ? m.get("value_type") : m.getOrDefault("type", "unknown");
            return String.valueOf(v);
        }
        return String.valueOf(dt);
    }

    @SuppressWarnings("unchecked")
    static void printIrSummary(List<Map<String, Object>> irList) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("IR SUMMARY WITH DATA TYPES");
        System.out.println("=".repeat(70));
        int i = 1;
        for (Map<String, Object> node : irList) {
            System.out.println("\n[" + (i++) + "] Line " + node.get("line") + ", Col " + node.get("col"));
            System.out.println("    Action: " + node.get("action"));
            System.out.println("    Type: " + node.get("type"));
            System.out.println("    Node: " + node.get("node"));
            Object dt = node.get("data_type");
            if (dt instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) dt;
                System.out.println("    Data Type: " + m.get("type"));
                System.out.println("    Value Type: " + m.get("value_type"));
            }
            Object data = node.get("data");
            if (data instanceof List) {
                for (Object item : (List<Object>) data) {
                    if (item instanceof Map && ((Map<?, ?>) item).containsKey("equation")) {
                        System.out.println("    Equation: " + ((Map<?, ?>) item).get("equation"));
                    }
                }
            }
            System.out.println("    Data: " + MonoJson.dump(data));
        }
        System.out.println("\n" + "=".repeat(70) + "\n");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java Mono_j11 <source_file> [rule.json]");
            System.err.println("       (no rule.json -> built-in default rule is used)");
            System.exit(1);
        }
        String sourcePath = args[0];
        String rulePath = args.length > 1 ? args[1] : null;
        try {
            if (rulePath != null) GrammarState.loadRule(rulePath);
            else GrammarState.loadDefaultRule();

            String code = new String(Files.readAllBytes(Paths.get(sourcePath)), StandardCharsets.UTF_8);
            Map<String, Object> cfg = (Map<String, Object>) MonoJson.get(GrammarState.grammar, "_config", MonoJson.obj());
            int tabSize = cfg.containsKey("tab_size") ? (int) MonoJson.asLong(cfg.get("tab_size")) : 4;

            Lexer lexer = new Lexer(code, tabSize);
            List<Token> tokens = lexer.run();

            Normalizer normalizer = new Normalizer(tokens, GrammarState.grammar);
            List<Map<String, Object>> ir = normalizer.parse();

            printIrSummary(ir);
        } catch (MonoError | IOException e) {
            System.err.println("[Fatal] " + e.getMessage());
            System.exit(1);
        }
    }
}