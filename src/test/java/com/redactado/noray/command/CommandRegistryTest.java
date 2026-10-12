package com.redactado.noray.command;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    @Test
    void testCommandDispatchAndTokenization() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return "Echo text"; }
            @Override public List<String> aliases() { return List.of("print"); }
            @Override public int execute(CommandInvocation invocation) {
                invocation.out().println(String.join(" ", invocation.args()));
                return 0;
            }
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);

        int code = registry.dispatch("print \"hello world\" 'from noray'", null, System.in, ps, ps);
        assertEquals(0, code);
        assertEquals("hello world from noray" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void singleLetterFlagsAreSwitchesUnlessDeclared() {
        Command cmd = new Command() {
            @Override public String name() { return "t"; }
            @Override public String description() { return ""; }
            @Override public java.util.Set<String> valueFlags() { return java.util.Set.of("n"); }
            @Override public java.util.Set<String> switchFlags() { return java.util.Set.of("values"); }
            @Override public int execute(CommandInvocation invocation) { return 0; }
        };
        record Parsed(List<String> args, java.util.Map<String, String> flags, java.util.Set<String> switches) {}
        java.util.function.Function<String, Parsed> parse = line -> {
            List<String> args = new java.util.ArrayList<>();
            java.util.Map<String, String> flags = new java.util.LinkedHashMap<>();
            java.util.Set<String> switches = new java.util.LinkedHashSet<>();
            CommandRegistry.parseArguments(cmd, CommandRegistry.parseTokens(line), args, flags, switches);
            return new Parsed(args, flags, switches);
        };

        Parsed p = parse.apply("t -x key");                  // undeclared short flag: switch, key stays an argument
        assertEquals(List.of("key"), p.args());
        assertTrue(p.switches().contains("x"));

        p = parse.apply("t -n 20 -n5 file");                 // declared: valued, also glued
        assertEquals("5", p.flags().get("n"));
        assertEquals(List.of("file"), p.args());

        p = parse.apply("t --values key --role peer");       // declared long switch vs legacy valued long flag
        assertEquals(List.of("key"), p.args());
        assertEquals("peer", p.flags().get("role"));

        p = parse.apply("t --help topic -- -literal");       // --help is always a switch; `--` ends options
        assertTrue(p.switches().contains("help"));
        assertEquals(List.of("topic", "-literal"), p.args());
    }

    @Test
    void singleQuotesKeepDollarSignsLiteral() {
        java.util.Map<String, String> env = java.util.Map.of("HOME", "/home/x");
        assertEquals("cd /home/x '$HOME' \"/home/x\"", CommandRegistry.expandVariables("cd $HOME '$HOME' \"$HOME\"", env, 0));
        assertEquals("echo 'a$b' 3", CommandRegistry.expandVariables("echo 'a$b' $?", env, 3));
        assertEquals("run 'Outer$Inner'", CommandRegistry.expandVariables("run 'Outer$Inner'", env, 0));
    }
}
